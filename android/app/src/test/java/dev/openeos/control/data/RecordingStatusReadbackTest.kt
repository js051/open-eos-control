package dev.openeos.control.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RecordingStatusReadbackTest {
    private fun json(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun http() = OkHttpClient.Builder().retryOnConnectionFailure(false)
        .callTimeout(2, TimeUnit.SECONDS).readTimeout(1, TimeUnit.SECONDS).build()

    @Test fun bridgeMapsOnlyExactAcknowledgedCodeAndCarriesBothDirectionsWithoutReplay() = runBlocking {
        val server = MockWebServer()
        val code = AtomicReference("RECORDING_STATUS_READBACK_FAILED")
        val actions = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                request.path.orEmpty().contains("/recording/") -> {
                    actions += request.path!!.substringAfterLast('/')
                    json("""{"error":{"code":"${code.get()}","message":"Synthetic failure"}}""").setResponseCode(502)
                }
                else -> json()
            }
        }
        server.start()
        val client = DesktopBridgeClient(server.url("/").toString())
        try {
            client.initialize()
            for (error in listOf("RECORDING_STATUS_READBACK_FAILED", "CCAPI_UNREACHABLE", "RECORDING_FAILED",
                "RECORDING_STATUS_READBACK_FAILED_extra", "recording_status_readback_failed")) {
                code.set(error)
                for (command in RecordingCommand.entries) {
                    val before = actions.size
                    val failure = runCatching {
                        if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                    }.exceptionOrNull()
                    assertEquals(error == "RECORDING_STATUS_READBACK_FAILED", failure is RecordingStatusReadbackException)
                    if (failure is RecordingStatusReadbackException) assertEquals(command, failure.command)
                    assertEquals(before + 1, actions.size)
                    assertEquals(if (command == RecordingCommand.START) "start" else "stop", actions.last())
                }
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun requiredSimulatorReadFailureIsTypedOnlyAfterCommandAcknowledgement() = runBlocking {
        val server = MockWebServer()
        val reject = AtomicBoolean(false)
        val actions = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/ccapi/record/") -> {
                    actions += request.path!!.substringAfterLast('/')
                    if (reject.get()) MockResponse().setResponseCode(503) else json()
                }
                request.path == "/ccapi/status" -> MockResponse().setResponseCode(503)
                else -> json()
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http())
        try {
            client.initialize()
            for (rejected in listOf(false, true)) {
                reject.set(rejected)
                for (command in RecordingCommand.entries) {
                    val before = actions.size
                    val failure = runCatching {
                        if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                    }.exceptionOrNull()
                    assertEquals(!rejected, failure is RecordingStatusReadbackException)
                    if (failure is RecordingStatusReadbackException) assertEquals(command, failure.command)
                    assertEquals(before + 1, actions.size)
                }
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun nativeOptionalStatusFailuresKeepExistingSuccessfulCommandSemantics() = runBlocking {
        val server = MockWebServer()
        val actions = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json("""{"ver110":[{"path":"/shooting/control/recbutton","post":true}]}""")
                request.path.orEmpty().endsWith("/recbutton") -> {
                    actions += request.body.readUtf8()
                    json()
                }
                else -> MockResponse().setResponseCode(503)
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http())
        try {
            client.initialize()
            assertEquals(true, client.startRecording().recording)
            assertEquals(false, client.stopRecording().recording)
            assertEquals(2, actions.size)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun lostCommandAcknowledgementRetainsUnknownWithoutAckTypingOrReplay() = runBlocking {
        val allActions = mutableListOf<String>()
        // Each direction gets an independent transport: the contract here is
        // loss after a complete command, not recovery of a prior broken socket.
        for (command in RecordingCommand.entries) {
            val server = MockWebServer()
            val actions = CopyOnWriteArrayList<String>()
            val reads = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path.orEmpty().startsWith("/ccapi/record/") -> {
                        actions += request.path!!
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    else -> { reads += request.path.orEmpty(); json() }
                }
            }
            server.start()
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http().newBuilder().retryOnConnectionFailure(true).build())
            try {
                client.initialize()
                val failure = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }.exceptionOrNull()
                val detail = "$command: ${failure?.javaClass?.name}: ${failure?.message}"
                assertNotNull(detail, failure)
                assertFalse(detail, failure is RecordingStatusReadbackException)
                assertTrue(detail, failure is RecordingCommandUnconfirmedException)
                assertEquals(command, (failure as RecordingCommandUnconfirmedException).command)
                assertFalse(detail, failure is CancellationException)
                val request = server.takeRequest(2, TimeUnit.SECONDS)
                assertNotNull(detail, request)
                val received = requireNotNull(request)
                assertNull("$detail; incomplete request: ${received.failure}", received.failure)
                assertEquals(detail, "POST", received.method)
                val expectedPath = if (command == RecordingCommand.START) "/ccapi/record/start" else "/ccapi/record/stop"
                assertEquals(detail, expectedPath, received.path)
                assertEquals(detail, listOf(expectedPath), actions.toList())
                assertEquals(detail, 1, server.requestCount)
                assertTrue(detail, reads.isEmpty())
                allActions += actions
            } finally { client.close(); server.shutdown() }
        }
        assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), allActions)
    }

    @Test fun temperatureRestrictionBeforeTheRecRequestDoesNotCreateUncertainty() = runBlocking {
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/ccapi" -> json("""{"ver110":[{"path":"/devicestatus/temperature","get":true},{"path":"/shooting/control/recbutton","post":true}]}""")
                "/ccapi/ver110/devicestatus/temperature" -> json("""{"status":"restrictionmovierecording"}""")
                else -> json()
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, httpClient = http())
        val evidence = RecordingRequestEvidence(RecordingCommand.START)
        try {
            client.initialize()
            val failure = runCatching { withContext(evidence) { client.startRecording() } }.exceptionOrNull()
            assertNotNull(failure)
            assertFalse(failure is RecordingCommandUnconfirmedException)
            assertFalse(failure is RecordingStatusReadbackException)
            assertFalse(evidence.attempted)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)?.method) }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun bridgeCommandUnconfirmedRequiresExactCodeStatusFeatureAndEngine() = runBlocking {
        data class Case(val code: String = "RECORDING_COMMAND_UNCONFIRMED", val status: Int = 502,
            val feature: String = "VIDEO_RECORDING", val engine: String = "ccapi", val unknown: Boolean = false)
        val server = MockWebServer()
        val current = AtomicReference(Case(unknown = true))
        val actions = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                request.path.orEmpty().contains("/recording/") -> {
                    actions += request.path!!.substringAfterLast('/')
                    val c = current.get()
                    json("""{"error":{"code":"${c.code}","message":"Synthetic failure","feature":"${c.feature}","engine":"${c.engine}"}}""").setResponseCode(c.status)
                }
                else -> json()
            }
        }
        server.start()
        val client = DesktopBridgeClient(server.url("/").toString())
        try {
            client.initialize()
            for (case in listOf(Case(unknown = true), Case(code = "CCAPI_UNREACHABLE"), Case(code = "CCAPI_HTTP_ERROR"),
                Case(code = "RECORDING_COMMAND_UNCONFIRMED_extra"), Case(code = "recording_command_unconfirmed"),
                Case(status = 503), Case(code = "RECORDING_STATUS_READBACK_FAILED", status = 503),
                Case(feature = "STILL_CAPTURE"), Case(engine = "ptp"))) {
                current.set(case)
                for (command in RecordingCommand.entries) {
                    val before = actions.size
                    val failure = runCatching {
                        if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                    }.exceptionOrNull()
                    assertEquals(case.toString(), case.unknown, failure is RecordingCommandUnconfirmedException)
                    assertFalse(failure is RecordingStatusReadbackException)
                    if (failure is RecordingCommandUnconfirmedException) assertEquals(command, failure.command)
                    assertEquals(before + 1, actions.size)
                }
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun invalidSuccessBodyIsUnknownButExplicitHttpRejectionIsNot() = runBlocking {
        for (bridge in listOf(false, true)) {
            val server = MockWebServer()
            val reject = AtomicBoolean(false)
            val actions = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                    request.path.orEmpty().contains("/recording/") || request.path.orEmpty().startsWith("/ccapi/record/") -> {
                        actions += request.path!!
                        MockResponse().setResponseCode(if (reject.get()) 503 else 200).setBody("invalid response")
                    }
                    else -> json()
                }
            }
            server.start()
            val direct = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http())
            val bridgeClient = DesktopBridgeClient(server.url("/").toString(), httpClient = http())
            try {
                if (bridge) bridgeClient.initialize() else direct.initialize()
                for (rejected in listOf(false, true)) {
                    reject.set(rejected)
                    val failure = runCatching { if (bridge) bridgeClient.startRecording() else direct.startRecording() }.exceptionOrNull()
                    val detail = "bridge=$bridge rejected=$rejected; failure=" + generateSequence(failure) { it.cause }
                        .take(8).joinToString(" -> ") { "${it.javaClass.name}: ${it.message}" }
                    assertEquals(detail, !rejected, failure is RecordingCommandUnconfirmedException)
                    assertFalse(detail, failure is RecordingStatusReadbackException)
                }
                assertEquals(2, actions.size)
            } finally { direct.close(); bridgeClient.close(); server.shutdown() }
        }
    }

    @Test fun malformedBridge502AfterAppliedCommandIsUnknownButCompleteRejectionRemainsOrdinary() = runBlocking {
        data class Case(val body: String, val status: Int = 502, val unknown: Boolean = true)
        val cases = listOf(Case(""), Case("invalid"), Case("{}"),
            Case("""{"error":{"code":5,"message":"Synthetic failure"}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR"}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":""}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":"   "}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":5}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":null}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":{}}}"""),
            Case("""{"error":{"code":"RECORDING_STATUS_READBACK_FAILED"}}"""),
            Case("""{"error":{"code":"CCAPI_HTTP_ERROR","message":"Synthetic rejection"}}""", unknown = false),
            Case("""{"error":{"code":"CCAPI_UNREACHABLE","message":"Synthetic preflight failure"}}""", unknown = false),
            Case("""{"error":{"code":"RECORDING_FAILED","message":"Synthetic rejection"}}""", status = 503, unknown = false))
        val current = AtomicReference(cases.first())
        val peerRecording = AtomicBoolean(false)
        val actions = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                request.path.orEmpty().contains("/recording/") -> {
                    val action = request.path!!.substringAfterLast('/')
                    actions += action
                    val case = current.get()
                    if (case.unknown) peerRecording.set(action == "start")
                    json(case.body).setResponseCode(case.status)
                }
                else -> json()
            }
        }
        server.start()
        val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http())
        try {
            client.initialize()
            for (case in cases) for (command in RecordingCommand.entries) {
                current.set(case)
                peerRecording.set(command == RecordingCommand.STOP)
                val before = actions.size
                val failure = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }.exceptionOrNull()
                assertEquals(case.toString(), case.unknown, failure is RecordingCommandUnconfirmedException)
                assertFalse(failure is RecordingStatusReadbackException)
                assertEquals(if (case.unknown) command == RecordingCommand.START else command == RecordingCommand.STOP,
                    peerRecording.get())
                assertEquals(before + 1, actions.size)
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun interruptedBridgeRejectionBodyKeepsHeaderEvidenceWhile502RemainsUnknown() = runBlocking {
        // Each broken response gets its own peer and connection, independent of socket recovery.
        for (status in listOf(401, 403, 503, 502)) for (command in RecordingCommand.entries) {
            val server = MockWebServer()
            val actions = CopyOnWriteArrayList<String>()
            val received = AtomicReference<RecordedRequest?>()
            val peerRecording = AtomicBoolean(command == RecordingCommand.STOP)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                    request.path.orEmpty().contains("/recording/") -> {
                        received.set(request)
                        actions += request.path!!.substringAfterLast('/')
                        if (status == 502) peerRecording.set(command == RecordingCommand.START)
                        json("""{"error":{"code":"SYNTHETIC_ERROR","message":"${"x".repeat(4096)}"}}""")
                            .setResponseCode(status).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                    }
                    else -> json()
                }
            }
            server.start()
            val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http())
            val evidence = RecordingRequestEvidence(command)
            try {
                client.initialize()
                val before = server.requestCount
                val failure = runCatching {
                    withContext(evidence) {
                        if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                    }
                }.exceptionOrNull()
                val detail = "$status $command: $failure"
                assertNotNull(detail, failure)
                assertEquals(detail, status == 502, failure is RecordingCommandUnconfirmedException)
                assertFalse(detail, failure is RecordingStatusReadbackException)
                assertFalse(detail, failure is CancellationException)
                if (status != 502) assertTrue(detail, failure is java.io.IOException)
                assertTrue(detail, evidence.attempted)
                assertEquals(detail, status != 502, evidence.rejected)
                assertEquals(detail, if (status == 502) command == RecordingCommand.START else command == RecordingCommand.STOP,
                    peerRecording.get())
                val request = requireNotNull(received.get())
                assertNull(detail, request.failure)
                assertEquals(detail, "POST", request.method)
                assertEquals(detail, listOf(if (command == RecordingCommand.START) "start" else "stop"), actions.toList())
                assertEquals(detail, before + 1, server.requestCount)
            } finally { client.close(); server.shutdown() }
        }
    }

    @Test fun bridgeHopLossIsUnknownAfterOneCompleteRequestWithNoReplay() = runBlocking {
        for (command in RecordingCommand.entries) {
            val server = MockWebServer()
            val received = AtomicReference<RecordedRequest?>()
            val appliedRecording = AtomicBoolean(command == RecordingCommand.STOP)
            val actions = CopyOnWriteArrayList<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                    request.path == "/v1/session" -> json("""{"id":"synthetic-recording","engine":"ccapi"}""")
                    request.path.orEmpty().contains("/recording/") -> {
                        received.set(request)
                        actions += request.path!!.substringAfterLast('/')
                        appliedRecording.set(command == RecordingCommand.START)
                        MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                    }
                    else -> json()
                }
            }
            server.start()
            val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http())
            try {
                client.initialize()
                val failure = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }.exceptionOrNull()
                assertTrue(failure.toString(), failure is RecordingCommandUnconfirmedException)
                assertFalse(failure is RecordingStatusReadbackException)
                assertEquals(command == RecordingCommand.START, appliedRecording.get())
                val request = requireNotNull(received.get())
                assertNull(request.failure)
                assertEquals("POST", request.method)
                assertEquals(listOf(if (command == RecordingCommand.START) "start" else "stop"), actions.toList())
            } finally { client.close(); server.shutdown() }
        }
    }

    @Test fun cancelledRequiredReadbackRemainsCancellation() = runBlocking {
        val server = MockWebServer()
        val statusEntered = CountDownLatch(1)
        val actions = CopyOnWriteArrayList<String>()
        val failure = AtomicReference<Throwable?>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/ccapi/record/") -> { actions += request.path!!; json() }
                request.path == "/ccapi/status" -> {
                    statusEntered.countDown()
                    MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                }
                else -> json()
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http())
        try {
            client.initialize()
            val job = launch { failure.set(runCatching { client.startRecording() }.exceptionOrNull()) }
            assertTrue(withContext(Dispatchers.IO) { statusEntered.await(3, TimeUnit.SECONDS) })
            job.cancel()
            job.join()
            assertTrue(failure.get() is CancellationException)
            assertFalse(failure.get() is RecordingStatusReadbackException)
            assertEquals(listOf("/ccapi/record/start"), actions.toList())
        } finally { client.close(); server.shutdown() }
    }
}

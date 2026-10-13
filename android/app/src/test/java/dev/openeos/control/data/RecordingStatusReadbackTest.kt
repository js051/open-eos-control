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

    @Test fun lostCommandAcknowledgementNeverBecomesTypedRecoveryOrGetsReplayed() = runBlocking {
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
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http())
        try {
            client.initialize()
            for (command in RecordingCommand.entries) {
                val failure = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }.exceptionOrNull()
                assertNotNull(failure)
                assertFalse(failure is RecordingStatusReadbackException)
            }
            assertEquals(listOf("/ccapi/record/start", "/ccapi/record/stop"), actions.toList())
            assertTrue(reads.isEmpty())
        } finally { client.close(); server.shutdown() }
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

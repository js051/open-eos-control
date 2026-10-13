package dev.openeos.control.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RecordingResponseShapeTest {
    private data class Case(val body: String, val valid: Boolean = false, val recording: Boolean? = null)
    private fun status(value: String?) = """{"connected":true,"battery":{},"media":{},"exposure":{}${value?.let { ",\"recording\":$it" }.orEmpty()}}"""
    private fun cases(): List<Case> = listOf(
        Case(status("true"), valid = true, recording = true),
        Case(status("false"), valid = true, recording = false),
        Case(status("null"), valid = true), Case(status(null), valid = true),
    ) + listOf("0", "1", "2", "\"true\"", "\"false\"", "\"unknown\"", "[]", "{}")
        .map { Case(status(it)) } + listOf("0", "1", "2", "\"false\"", "[]", "null").map { Case(it) }
    private fun json(body: String = "{}") = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun http() = OkHttpClient.Builder().retryOnConnectionFailure(false).callTimeout(2, TimeUnit.SECONDS).build()

    @Test fun bridgeRecResponsesAndStatusReadsRequireBooleanRecordingWithoutCoercion() = runBlocking {
        val current = AtomicReference(cases().first())
        val actions = CopyOnWriteArrayList<String>()
        val peerRecording = AtomicBoolean(false)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/health" -> json("""{"ok":true,"service":"open-eos-control-bridge","version":"0.13.0"}""")
                request.path == "/v1/session" -> json("""{"id":"synthetic-shape","engine":"ccapi"}""")
                request.path.orEmpty().contains("/recording/") -> {
                    val action = request.path!!.substringAfterLast('/')
                    actions += action
                    peerRecording.set(action == "start")
                    json(current.get().body)
                }
                request.path.orEmpty().endsWith("/status") -> json(current.get().body)
                else -> json()
            }
        }
        server.start()
        val client = DesktopBridgeClient(server.url("/").toString(), httpClient = http())
        try {
            client.initialize()
            for (case in cases()) for (command in RecordingCommand.entries) {
                current.set(case)
                val before = actions.size
                val result = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }
                val detail = "$command ${case.body}"
                assertEquals(detail, case.valid, result.isSuccess)
                if (case.valid) assertEquals(detail, case.recording, result.getOrThrow().recording)
                else {
                    assertTrue(detail, result.exceptionOrNull() is RecordingCommandUnconfirmedException)
                    assertFalse(detail, result.exceptionOrNull() is RecordingStatusReadbackException)
                }
                assertEquals(detail, command == RecordingCommand.START, peerRecording.get())
                val read = runCatching { client.status() }
                assertEquals(detail, case.valid, read.isSuccess)
                if (case.valid) assertEquals(detail, case.recording, read.getOrThrow().recording)
                else {
                    assertFalse(detail, read.exceptionOrNull() is RecordingCommandUnconfirmedException)
                    assertFalse(detail, read.exceptionOrNull() is RecordingStatusReadbackException)
                }
                assertEquals(detail, before + 1, actions.size)
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun simulatorInvalidRequiredStatusRemainsAckReadbackFailureInBothDirections() = runBlocking {
        val current = AtomicReference(cases().first())
        val actions = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path.orEmpty().startsWith("/ccapi/record/") -> {
                    actions += request.path!!.substringAfterLast('/')
                    json() // A complete command ACK, separate from the required status read.
                }
                request.path == "/ccapi/status" -> json(current.get().body)
                else -> json()
            }
        }
        server.start()
        val client = CcapiClient(server.url("/").toString(), treatAsSimulator = true, httpClient = http())
        try {
            client.initialize()
            for (case in cases()) for (command in RecordingCommand.entries) {
                current.set(case)
                val before = actions.size
                val result = runCatching {
                    if (command == RecordingCommand.START) client.startRecording() else client.stopRecording()
                }
                val detail = "$command ${case.body}"
                assertEquals(detail, case.valid, result.isSuccess)
                if (case.valid) assertEquals(detail, case.recording, result.getOrThrow().recording)
                else {
                    assertTrue(detail, result.exceptionOrNull() is RecordingStatusReadbackException)
                    assertEquals(command, (result.exceptionOrNull() as RecordingStatusReadbackException).command)
                    assertFalse(detail, result.exceptionOrNull() is RecordingCommandUnconfirmedException)
                }
                assertEquals(detail, before + 1, actions.size)
            }
        } finally { client.close(); server.shutdown() }
    }

    @Test fun canonicalNative204CommandAckKeepsEstablishedRecordingCacheSemantics() = runBlocking {
        val actions = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path == "/ccapi" -> json("""{"ver110":[{"path":"/shooting/control/recbutton","post":true}]}""")
                request.path.orEmpty().endsWith("/recbutton") -> {
                    actions += request.body.clone().readUtf8()
                    MockResponse().setResponseCode(204)
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
}

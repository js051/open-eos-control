package dev.openeos.control.data

import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Authored native JVM/MockWebServer coverage. Execution is tracked separately from the host extraction. */
@RunWith(Parameterized::class)
class CcapiFocusAckReceiptTest(
    private val route: Int,
    private val label: String,
    private val code: Int,
    private val range: String?,
    private val body: String,
    private val accepted: Boolean,
) {
    @Test fun originalReceiptControlsSuccessAndObservedFeatureWithoutReplay() = runBlocking {
        val mutations = CopyOnWriteArrayList<RecordedRequest>()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path.endsWith(paths[route]) && request.method == (if (route == 1) "PUT" else "POST")) {
                    mutations += request
                    return MockResponse().setResponseCode(code).setBody(body).apply {
                        range?.let { setHeader("cOnTeNt-RaNgE", it) }
                    }
                }
                if (path == "/ccapi") return json("""{"ver110":[
                    {"path":"/shooting/control/drivefocus","post":true},
                    {"path":"/shooting/liveview/afframeposition","put":true},
                    {"path":"/shooting/liveview/clickwb","post":true},
                    {"path":"/shooting/liveview/flipdetail","get":true},
                    {"path":"/shooting/liveview","post":true,"delete":true},
                    {"path":"/shooting/settings","get":true}]}""")
                if (path.endsWith("/flipdetail")) {
                    val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
                    val info = """{"liveview":{"image":{"positionx":100,"positiony":200,"positionwidth":6000,"positionheight":4000}}}"""
                    return MockResponse().setHeader("Content-Type", "application/octet-stream")
                        .setBody(Buffer().write(packet(0, jpeg) + packet(1, info.toByteArray())))
                }
                return json("{}")
            }
        }
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        try {
            val client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, jpegFrameValidator = { true })
            client.initialize()
            if (route != 0) client.liveViewFrame(1)
            val outcome = runCatching {
                when (route) {
                    0 -> assertTrue(client.driveFocus(FocusDriveDirection.NEAR, FocusDriveStep.MEDIUM).ok)
                    1 -> assertTrue(client.tapFocus(0.4, 0.6).ok)
                    else -> client.clickWhiteBalance(0.4, 0.6)
                }
            }
            assertEquals(label, accepted, outcome.isSuccess)
            if (!accepted) {
                val failure = outcome.exceptionOrNull()!!
                if (code == 503) {
                    assertTrue(failure is ConnectionHttpFailure)
                    assertEquals(503, (failure as ConnectionHttpFailure).statusCode)
                    assertTrue(failure.message!!.contains("HTTP 503"))
                }
                else assertTrue(failure.message!!.contains("complete control acknowledgement"))
            }
            assertEquals(accepted, features[route] in client.capabilities().evidence.observedFeatures)
            client.status()
            val command = mutations.single()
            assertEquals(if (route == 1) "PUT" else "POST", command.method)
            assertEquals("/ccapi/ver110" + paths[route], command.path)
            val payload = JSONObject(command.body.readUtf8())
            if (route == 0) assertEquals("near2", payload.getString("value"))
            else {
                assertEquals(2500, payload.getInt("positionx"))
                assertEquals(2600, payload.getInt("positiony"))
            }
        } finally {
            server.shutdown()
        }
    }

    companion object {
        private val paths = listOf("/shooting/control/drivefocus", "/shooting/liveview/afframeposition", "/shooting/liveview/clickwb")
        private val features = listOf(CameraFeature.FOCUS_DRIVE, CameraFeature.TAP_FOCUS, CameraFeature.CLICK_WHITE_BALANCE)
        @JvmStatic @Parameterized.Parameters(name = "route={0} receipt={1}")
        fun cases(): Collection<Array<Any?>> = (0..2).flatMap { route -> listOf(
            arrayOf<Any?>(route, "partial206", 206, null, "{}", false),
            arrayOf<Any?>(route, "ranged200", 200, "bytes 0-1/4", "{}", false),
            arrayOf<Any?>(route, "emptyRange200", 200, "", "{}", false),
            arrayOf<Any?>(route, "fullJson200", 200, null, "{}", true),
            arrayOf<Any?>(route, "fullEmpty204", 204, null, "", true),
            arrayOf<Any?>(route, "fullPlain200", 200, null, "Accepted", true),
            arrayOf<Any?>(route, "rejected503", 503, "", "busy", false),
        ) }
        private fun json(text: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(text)
        private fun packet(type: Int, payload: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
            output.write(byteArrayOf(0xFF.toByte(), 0, type.toByte()))
            output.write(byteArrayOf((payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte()))
            output.write(payload)
            output.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte()))
            output.toByteArray()
        }
    }
}

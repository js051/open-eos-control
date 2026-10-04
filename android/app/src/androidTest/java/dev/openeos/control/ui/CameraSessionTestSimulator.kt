package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.Color
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

internal const val SESSION_TEST_TIMEOUT_SECONDS = 15L
internal const val SESSION_TEST_TIMEOUT_MILLIS = SESSION_TEST_TIMEOUT_SECONDS * 1_000

/** Synthetic simulator-contract HTTP fixture; never connects to a physical camera. */
internal class CameraSessionTestSimulator(val label: String, val initialIso: String = "100") {
    val server = MockWebServer()
    lateinit var baseUrl: String
        private set
    val recording = AtomicBoolean(false)
    val iso = AtomicReference(initialIso)
    val statusReads = AtomicInteger()
    val mediaReads = AtomicInteger()
    val eventPolls = AtomicInteger()
    val deliveredEvents = AtomicInteger()
    val mutations = CopyOnWriteArrayList<String>()
    val deletes = CopyOnWriteArrayList<String>()
    val recordingWrites = CopyOnWriteArrayList<String>()
    val previewReads = CopyOnWriteArrayList<String>()
    val originalReads = CopyOnWriteArrayList<String>()
    val itemIds = listOf("$label-first", "$label-second")
    val model = "Synthetic session $label"
    val serial = "TEST-SESSION-$label"
    val imageBytes: ByteArray = Bitmap.createBitmap(16, 12, Bitmap.Config.ARGB_8888).let { bitmap ->
        try {
            bitmap.eraseColor(Color.GRAY)
            ByteArrayOutputStream().apply {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this))
            }.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
    private val events = LinkedBlockingQueue<List<String>>()
    private val gates = CopyOnWriteArrayList<CameraSessionTestGate>()

    @Volatile var intercept: (RecordedRequest) -> MockResponse? = { null }

    init {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                val path = url.encodedPath
                if (request.method != "GET") mutations += "${request.method} $path"
                if (request.method == "DELETE") deletes += path
                if (request.method == "POST" && path.startsWith("/ccapi/record/")) recordingWrites += path
                if (request.method == "GET" && path == "/ccapi/status") statusReads.incrementAndGet()
                if (request.method == "GET" && path == "/ccapi/media") mediaReads.incrementAndGet()
                if (request.method == "GET" && url.queryParameter("kind") == "display") previewReads += path
                if (request.method == "GET" && path.startsWith("/ccapi/media/") && url.query == null) originalReads += path
                intercept(request)?.let { return it }
                return when {
                    request.method == "GET" && path == "/ccapi/info" -> json(
                        JSONObject().put("connected", true).put("model", model)
                            .put("serial", serial).put("api", "simulator").toString(),
                    )
                    request.method == "GET" && path == "/ccapi/status" -> json(statusJson())
                    request.method == "GET" && path == "/ccapi/capabilities" -> json(
                        """{"iso":["100","200","25600"],"shutter":["1/125"],
                            "aperture":["4.0"],"white_balance":["auto"]}""",
                    )
                    request.method == "GET" && path == "/ccapi/events" -> {
                        eventPolls.incrementAndGet()
                        val keys = events.poll(250, TimeUnit.MILLISECONDS).orEmpty()
                        if (keys.isNotEmpty()) deliveredEvents.incrementAndGet()
                        json(JSONObject().put("sequence", deliveredEvents.get())
                            .put("keys", JSONArray(keys)).toString())
                    }
                    request.method == "GET" && path == "/ccapi/media" -> json(mediaJson())
                    request.method == "GET" && path.startsWith("/ccapi/media/") &&
                        url.queryParameter("kind") in setOf("thumbnail", "display") -> imageResponse()
                    request.method == "GET" && path.startsWith("/ccapi/media/") && url.query == null -> imageResponse()
                    request.method == "DELETE" && path.startsWith("/ccapi/media/") ->
                        MockResponse().setResponseCode(204)
                    request.method == "PATCH" && path == "/ccapi/exposure" -> {
                        iso.set(JSONObject(request.body.readUtf8()).getString("iso"))
                        json(statusJson())
                    }
                    request.method == "POST" && path == "/ccapi/record/start" -> {
                        recording.set(true)
                        json("{}")
                    }
                    request.method == "POST" && path == "/ccapi/record/stop" -> {
                        recording.set(false)
                        json("{}")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    fun start() {
        server.start()
        // MockWebServer.url() may resolve the host. Keep it on the instrumentation test thread.
        baseUrl = server.url("/").toString()
    }
    fun gate() = CameraSessionTestGate().also(gates::add)
    fun releaseGates() = gates.forEach(CameraSessionTestGate::release)
    fun enqueueContentsEvent() { events.put(listOf("contents")) }
    fun imageResponse(): MockResponse = MockResponse().setHeader("Content-Type", "image/jpeg")
        .setBody(Buffer().write(imageBytes))

    fun statusJson(recordingValue: Boolean = recording.get(), isoValue: String = iso.get()): String =
        JSONObject().put("connected", true).put("recording", recordingValue).put("mode", "movie")
            .put("battery", JSONObject().put("level", 87).put("status", "normal"))
            .put("media", JSONObject().put("available", true).put("remaining_minutes", 42))
            .put("exposure", JSONObject().put("iso", isoValue).put("shutter", "1/125")
                .put("aperture", "4.0").put("white_balance", "auto")).toString()

    fun mediaJson(): String = JSONObject().put("items", JSONArray(itemIds.mapIndexed { index, id ->
        JSONObject().put("id", id).put("name", "${label}_${index + 1}.JPG").put("kind", "image")
            .put("size_bytes", imageBytes.size).put("capture_time", "2026-09-01T00:00:0${2 - index}Z")
    })).toString()

    fun json(body: String): MockResponse = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}

/** Every gate has a bound and is released unconditionally by test teardown. */
internal class CameraSessionTestGate {
    val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)

    fun blockResponse() {
        entered.countDown()
        check(released.await(SESSION_TEST_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            "Timed out waiting to release a synthetic camera response."
        }
    }

    fun release() = released.countDown()
}

package dev.openeos.control.data

import kotlinx.coroutines.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Synthetic protocol journeys; these do not establish Android decoding or physical-camera behavior. */
class CcapiLiveViewJourneyTest {
    @Test fun multipartHeadersAndInvalidFirstPartCannotPublishBeforeSafeAutoFallback() = runBlocking {
        Scenario().use { s ->
            s.multipart = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                .setBody("--canon\r\nContent-Type: text/plain\r\nContent-Length: 3\r\n\r\nbad") }
            s.client.initialize()
            s.client.startLiveView()
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, s.client.currentLiveViewSource())
            assertEquals(listOf("start", "open", "close-stream", "stop", "start", "jpeg"), s.events.toList())
            assertArrayEquals(s.jpeg, s.client.liveViewFrame(1).bytes)
            assertEquals(1, s.events.count { it == "jpeg" }) // Preserve validated first bytes.
        }
    }

    @Test fun nativeDecodeRejectionHappensInsideCandidateBeforeAutoFallback() = runBlocking {
        var validations = 0
        Scenario(validator = { ++validations > 1 }).use { s ->
            s.client.initialize(); s.client.startLiveView()
            assertEquals(2, validations)
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, s.client.currentLiveViewSource())
            assertEquals(listOf("start", "open", "close-stream", "stop", "start", "jpeg"), s.events.toList())
        }
    }

    @Test fun multipartImmediateEofFallsBackOnlyAfterOriginalStop() = runBlocking {
        Scenario().use { s ->
            s.multipart = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon").setBody("") }
            s.client.initialize(); s.client.startLiveView()
            assertEquals(listOf("start", "open", "close-stream", "stop", "start", "jpeg"), s.events.toList())
        }
    }

    @Test fun silentMultipartFirstFrameHasDeadlineBeforeAutoFallback() = runBlocking {
        Scenario().use { s ->
            s.multipart = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                .setBody("--canon\r\n").setHeader("Content-Length", 1_000_000) }
            s.client.initialize()
            withTimeout(20_000) { s.client.startLiveView() }
            assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, s.client.currentLiveViewSource())
            assertEquals(1, s.events.count { it == "stop" })
        }
    }

    @Test fun invalidMultipartWithUnknownCleanupRetainsStopAndBlocksAllNewStarts() = runBlocking {
        Scenario().use { s ->
            s.stopFails = true
            s.multipart = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon").setBody("") }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView() }.exceptionOrNull() is CcapiLiveViewReleaseException)
            assertNull(s.client.currentLiveViewSource()); assertTrue(s.client.liveViewStopRequired)
            assertEquals(listOf("start", "open", "close-stream", "stop"), s.events.toList())
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startLiveView() }.isFailure)
            assertEquals(before, s.events.toList())
            s.stopFails = false; s.client.stopLiveView()
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(1, s.events.count { it == "start" })
            s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
            assertEquals(2, s.events.count { it == "start" })
        }
    }

    @Test fun explicitMultipartNeverSwitchesAfterFirstFrameFailure() = runBlocking {
        Scenario().use { s ->
            s.multipart = { MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon").setBody("") }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART)) }.isFailure)
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(1, s.events.count { it == "start" }); assertFalse("jpeg" in s.events)
        }
    }

    @Test fun deleteJpegStartMustValidateBytesAndClearOwnerOnlyAfterStop() = runBlocking {
        Scenario().use { s ->
            s.jpegResponse = { MockResponse().setHeader("Content-Type", "image/jpeg").setBody("not a JPEG") }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING)) }.isFailure)
            assertEquals(listOf("start", "jpeg", "stop"), s.events.toList())
            assertNull(s.client.currentLiveViewSource()); assertFalse(s.client.liveViewStopRequired)
        }
    }

    @Test fun partialJpegHttpResponseCannotBecomeFirstFrameEvenWithCompleteMarkers() = runBlocking {
        Scenario().use { s ->
            s.jpegResponse = { MockResponse().setResponseCode(206).setHeader("Content-Type", "image/jpeg")
                .setHeader("Content-Range", "bytes 0-4/5").setBody(Buffer().write(s.jpeg)) }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING)) }.isFailure)
            assertNull(s.client.currentLiveViewSource())
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(listOf("start", "jpeg", "stop"), s.events.toList())
        }
    }

    @Test fun localRetirementClosesMultipartWithoutDispatchingOrLosingOriginalStop() = runBlocking {
        Scenario().use { s ->
            s.client.initialize(); s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_MULTIPART))
            assertArrayEquals(s.jpeg, s.client.liveViewFrame(1).bytes)
            val before = s.events.toList()
            s.client.retireLiveViewLocally()
            assertNull(s.client.currentLiveViewSource())
            assertTrue(s.client.liveViewStopRequired)
            assertEquals(before, s.events.toList())
            s.client.stopLiveView()
            assertFalse(s.client.liveViewStopRequired)
            assertEquals(listOf("start", "open", "close-stream", "stop"), s.events.toList())
        }
    }

    @Test fun failedSizeDowngradeStopIsNotRetriedByOuterCleanupOrAuto() = runBlocking {
        Scenario(postOnly = true).use { s ->
            s.stopFails = true
            s.jpegResponse = { MockResponse().setResponseCode(503).setBody("Mode not supported") }
            s.client.initialize()
            assertTrue(runCatching { s.client.startLiveView(LiveViewRequest(size = LiveViewSize.MEDIUM)) }
                .exceptionOrNull() is CcapiLiveViewReleaseException)
            assertEquals(listOf("start", "jpeg", "stop"), s.events.toList())
            assertTrue(s.client.liveViewStopRequired)
            assertNull(s.client.currentLiveViewSource())
            s.stopFails = false
            val before = s.events.toList()
            assertTrue(runCatching { s.client.startLiveView() }.isFailure)
            assertEquals(before, s.events.toList())
            s.client.stopLiveView() // Only this explicit Stop retry may settle the original duty.
            assertEquals(2, s.events.count { it == "stop" })
            assertEquals(1, s.events.count { it == "start" })
        }
    }

    @Test fun maintenanceJpegFirstReadIsStrictSingleEndpointAndRetainsFailedRestoreStop() = runBlocking {
        Scenario().use { s ->
            s.jpegResponse = { MockResponse().setResponseCode(302).setHeader("Location", s.server.url("/forbidden")) }
            s.client.initialize()
            val context = MaintenanceIoContext(coroutineContext) {}
            assertTrue(runCatching { withContext(context) {
                s.client.startLiveView(LiveViewRequest(source = LiveViewSource.CCAPI_JPEG_POLLING))
            } }.isFailure)
            assertEquals(listOf("start", "jpeg"), s.events.toList())
            assertTrue(s.client.liveViewStopRequired); assertTrue(s.client.maintenanceStopOwned)
            assertNull(s.client.currentLiveViewSource())
        }
    }

    private class Scenario(validator: ((ByteArray) -> Boolean)? = null, postOnly: Boolean = false) : AutoCloseable {
        val server = MockWebServer()
        val events = CopyOnWriteArrayList<String>()
        val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 1, 0xff.toByte(), 0xd9.toByte())
        var stopFails = false
        var multipart: () -> MockResponse = {
            MockResponse().setHeader("Content-Type", "multipart/x-mixed-replace; boundary=canon")
                .setBody(Buffer().writeUtf8("--canon\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n")
                    .write(jpeg).writeUtf8("\r\n")).setHeader("Content-Length", 1_000_000)
        }
        var jpegResponse: () -> MockResponse = {
            MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg))
        }
        val client: CcapiClient
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.path == "/ccapi" -> MockResponse().setHeader("Content-Type", "application/json").setBody(if (postOnly) POST_ONLY_DISCOVERY else DISCOVERY)
                    request.path?.endsWith("/multipart") == true && request.method == "GET" -> {
                        events += "open"; multipart()
                    }
                    request.path?.endsWith("/multipart") == true -> { events += "close-stream"; MockResponse().setResponseCode(204) }
                    request.path?.contains("/flip?") == true -> { events += "jpeg"; jpegResponse() }
                    request.path?.endsWith("/shooting/liveview") == true && request.method == "POST" -> {
                        val stop = request.body.readUtf8().contains("\"off\"")
                        events += if (stop) "stop" else "start"
                        MockResponse().setResponseCode(if (stop && stopFails) 503 else 204)
                    }
                    request.path?.endsWith("/shooting/liveview") == true && request.method == "DELETE" -> {
                        events += "stop"; MockResponse().setResponseCode(if (stopFails) 503 else 204)
                    }
                    else -> { events += "unexpected"; MockResponse().setResponseCode(404) }
                }
            }
            server.start()
            client = CcapiClient(server.url("/").toString(), treatAsSimulator = false, jpegFrameValidator = validator)
        }
        override fun close() {
            stopFails = false
            try { runBlocking { client.stopLiveView() } } finally { server.shutdown() }
        }
    }

    companion object {
        private const val POST_ONLY_DISCOVERY = """{"ver110":[{"path":"/shooting/liveview","post":true},{"path":"/shooting/liveview/flip","get":true}]}"""
        private const val DISCOVERY = """{"ver110":[{"path":"/shooting/liveview","post":true,"delete":true},{"path":"/shooting/liveview/multipart","get":true,"delete":true},{"path":"/shooting/liveview/flip","get":true}]}"""
    }
}

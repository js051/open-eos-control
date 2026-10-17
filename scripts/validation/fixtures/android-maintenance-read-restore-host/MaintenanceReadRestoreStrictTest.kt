package dev.openeos.control.data

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual client maintenance access and strict Start paths; no replacement transport logic. */
class MaintenanceReadRestoreStrictTest {
    private fun readback() = JSONObject().put("version", 1).put("engine", "synthetic-engine")
        .put("operation", "sensor-cleaning").put("observationEvidence", "reachable").put("clockObservation", JSONObject.NULL)
    private val request = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING, autoPowerOff = false)
    @Test(timeout = 10000) fun readAndStrictStartRejectInvalidFramingLengthUtf8AndSize() = runBlocking {
        var tested = 0
        for (start in listOf(false, true)) {
            val valid = (if (start) JSONObject().put("active", true).put("liveViewStopUnconfirmed", false) else readback()).toString().toByteArray()
            data class Case(val name: String, val bytes: ByteArray = valid, val declared: Long = bytes.size.toLong(),
                val status: Int = 200, val headers: Map<String, List<String>> = emptyMap())
            val cases = listOf(
                Case("partial status", status = 206), Case("wrong complete status", status = 201),
                Case("Content-Range", headers = mapOf("Content-Range" to listOf("bytes 0-1/2"))),
                Case("duplicate Content-Length", headers = mapOf("Content-Length" to listOf("2", "2"))),
                Case("length plus transfer encoding", headers = mapOf("Content-Length" to listOf("2"), "Transfer-Encoding" to listOf("chunked"))),
                Case("negative Content-Length", headers = mapOf("Content-Length" to listOf("-1"))),
                Case("unsupported Transfer-Encoding", headers = mapOf("Transfer-Encoding" to listOf("gzip"))),
                Case("short body", declared = valid.size + 1L), Case("long body", declared = valid.size - 1L),
                Case("declared oversize", declared = 65_537L), Case("actual oversize", bytes = ByteArray(65_537) { 32 }, declared = -1L),
                Case("invalid UTF-8", bytes = byteArrayOf(0xC3.toByte(), 0x28), declared = 2L),
                Case("trailing JSON", bytes = valid + " {}".toByteArray()),
                Case("duplicate JSON key", bytes = "{\"duplicate\":1,\"duplicate\":2}".toByteArray()),
                Case("empty response", bytes = byteArrayOf()),
            )
            for (case in cases) {
                val client = ExtractedMaintenanceClient(false)
                val response = Response(case.status, ResponseBody(ByteArrayInputStream(case.bytes), case.declared), case.headers)
                val access = client.maintenanceAccess(request, if (start) LiveViewRequest() else null)
                if (start) access.stopLiveView()
                client.respond = { _, _ -> response }
                val failure = runCatching { if (start) access.restore() else access.read(request) }.exceptionOrNull()
                assertNotNull("${case.name}, start=$start", failure)
                assertEquals(if (start) 2 else 1, client.requests.size)
                assertEquals(1, client.calls.last().enqueueCount.get())
                assertEquals(0, client.calls.last().executeCount.get())
                assertEquals(1, response.closes.get())
                assertEquals(TimeUnit.SECONDS.toNanos(15), client.calls.last().timeout().timeoutNanos())
                assertEquals(start, client.liveViewStopRequired)
                if (start) assertTrue(client.maintenanceStopOwned)
                tested++
            }
        }
        println("Strict read/Start rejection matrix: $tested actual access read/restore cases passed")
    }
    @Test(timeout = 7000) fun maintenanceReadbackStillRequiresMatchingActionEngineAndObservation() = runBlocking {
        val cases = listOf(readback().put("engine", "wrong"), readback().put("operation", "sleep"),
            readback().put("version", 2), readback().put("observationEvidence", "unknown"), readback().put("clockObservation", JSONObject()))
        for (body in cases) {
            val client = ExtractedMaintenanceClient(false)
            client.respond = { _, _ -> client.jsonResponse(body) }
            assertNotNull(runCatching { client.maintenanceAccess(request, null).read(request) }.exceptionOrNull())
            assertFalse(client.liveViewStopRequired)
        }
    }
    @Test(timeout = 7000) fun strictStartRequiresExplicitBooleanStartAndStopProof() = runBlocking {
        val cases = listOf(JSONObject().put("active", false).put("liveViewStopUnconfirmed", false),
            JSONObject().put("active", true), JSONObject().put("active", true).put("liveViewStopUnconfirmed", true),
            JSONObject().put("active", "true").put("liveViewStopUnconfirmed", false),
            JSONObject().put("active", true).put("liveViewStopUnconfirmed", "false"))
        for (body in cases) {
            val client = ExtractedMaintenanceClient(false)
            val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView()
            client.respond = { _, _ -> client.jsonResponse(body) }
            assertNotNull(runCatching { access.restore() }.exceptionOrNull())
            assertTrue(client.liveViewStopRequired); assertTrue(client.maintenanceStopOwned)
        }
    }
    @Test(timeout = 7000) fun freshReadsRetainNoCacheHeadersAndExactOwnerPaths() = runBlocking {
        for (action in MaintenanceAction.entries) {
            val client = ExtractedMaintenanceClient(false)
            val response = readback().put("operation", action.wireName).also { body ->
                if (action == MaintenanceAction.CLOCK_SYNC) body.put("observationEvidence", "observed")
                    .put("clockObservation", JSONObject().put("epochSeconds", 1_700_000_001L)
                        .put("utcOffsetMinutes", 0).put("daylightSaving", false))
            }
            client.respond = { _, _ -> client.jsonResponse(response) }
            val frozen = MaintenanceRequest(action, clock = if (action == MaintenanceAction.CLOCK_SYNC) MaintenanceClockTarget(1_700_000_000_000L, 0, false) else null, autoPowerOff = false)
            val observed = client.maintenanceAccess(frozen, null).read(frozen)
            assertTrue(observed.reachable)
            if (action == MaintenanceAction.CLOCK_SYNC) assertEquals(1_700_000_001L, observed.clock?.epochSeconds)
            else assertNull(observed.clock)
            val sent = client.requests.single()
            assertEquals("GET", sent.method)
            assertEquals("/v1/session/synthetic-session/maintenance/${action.wireName}", sent.url.encodedPath)
            assertEquals("no-cache, no-store", sent.headerMap["Cache-Control"])
            assertEquals(1, client.calls.last().enqueueCount.get())
        }
    }
    @Test(timeout = 7000) fun unrelatedMaintenancePathsAndOrdinaryLiveViewKeepTheirGenericTransport() = runBlocking {
        val cases = listOf("GET" to "/v1/session/synthetic-session/maintenance/unknown",
            "GET" to "/v1/session/synthetic-session/maintenance/sensor-cleaning/extra",
            "POST" to "/v1/session/synthetic-session/maintenance/sensor-cleaning",
            "GET" to "/v1/session/synthetic-session/maintenance/control/liveview/start",
            "POST" to "/v1/session/synthetic-session/maintenance/control/liveview/start/extra",
            "GET" to "/v1/session/another-session/maintenance/sensor-cleaning")
        for ((method, path) in cases) {
            val client = ExtractedMaintenanceClient(false)
            client.routeProbe(method, path)
            assertEquals(path, 1, client.calls.last().executeCount.get())
            assertEquals(path, 0, client.calls.last().enqueueCount.get())
            assertEquals(path, 1, client.accepts.get())
        }
        val client = ExtractedMaintenanceClient(false)
        client.startLiveView(LiveViewRequest())
        assertEquals("/v1/session/synthetic-session/liveview/start", client.requests.single().url.encodedPath)
        assertEquals(1, client.calls.last().executeCount.get())
        assertEquals(0, client.calls.last().enqueueCount.get())
        assertTrue(client.liveViewStopRequired); assertFalse(client.maintenanceStopOwned)
    }
    @Test(timeout = 7000) fun cancelledReadWaiterCannotInvokeLateAccept() = runBlocking {
        val client = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val text = readback().toString().toByteArray()
        client.respond = { _, _ -> Response(200, ResponseBody(object : ByteArrayInputStream(text) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
            }
        })) }
        val job = launch(Dispatchers.Default) { client.routeProbe("GET", "/v1/session/synthetic-session/maintenance/sensor-cleaning") }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            job.cancel(); withTimeout(2000) { job.join() }
            assertEquals(1L, release.count); assertEquals(0, client.accepts.get())
        } finally { release.countDown(); withTimeout(2000) { job.join() } }
        withTimeout(2000) { while (client.calls.last().callbacksCompleted.get() == 0) delay(5) }
        assertEquals(0, client.accepts.get())
    }

    @Test(timeout = 7000) fun sameTextReplacementOwnerRejectsReadAccept() = runBlocking {
        val client = ExtractedMaintenanceClient(false)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val text = readback().toString().toByteArray()
        client.respond = { _, _ -> Response(200, ResponseBody(object : ByteArrayInputStream(text) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
            }
        })) }
        val result = async(Dispatchers.Default) {
            runCatching { client.routeProbe("GET", "/v1/session/synthetic-session/maintenance/sensor-cleaning") }
        }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val replacement = client.replaceSession(); val stop = client.liveViewOwnerIdentity
            release.countDown()
            assertTrue(withTimeout(2000) { result.await() }.isFailure)
            assertEquals(0, client.accepts.get())
            assertSame(replacement, client.sessionIdentity); assertSame(stop, client.liveViewOwnerIdentity)
            assertFalse(replacement.closed)
        } finally { release.countDown(); withTimeout(2000) { result.join() } }
    }

    @Test(timeout = 7000) fun duplicateReadCallbackPublishesAtMostOneCurrentResult() = runBlocking {
        val client = ExtractedMaintenanceClient(false); client.callbackCopies = 2
        val responses = java.util.concurrent.CopyOnWriteArrayList<Response>()
        client.respond = { _, _ -> client.jsonResponse(readback()).also { responses += it } }
        client.routeProbe("GET", "/v1/session/synthetic-session/maintenance/sensor-cleaning")
        withTimeout(2000) { while (client.calls.last().callbacksCompleted.get() < 2) delay(5) }
        assertEquals(1, client.accepts.get())
        assertEquals(2, responses.size); assertTrue(responses.all { it.closes.get() == 1 })
        assertEquals(1, client.calls.last().enqueueCount.get())
        assertEquals(0, client.calls.last().executeCount.get())
    }

}

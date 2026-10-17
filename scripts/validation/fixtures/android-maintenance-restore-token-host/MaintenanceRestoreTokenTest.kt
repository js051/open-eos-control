package dev.openeos.control.data

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class MaintenanceRestoreTokenTest {
    private val token = "synthetic_restore_token_000000001"
    private val request = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING, autoPowerOff = false)
    private fun rig(): ExtractedMaintenanceClient = ExtractedMaintenanceClient(true).also { client ->
        client.respond = { sent, _ -> client.jsonResponse(JSONObject().put("active", sent.url.encodedPath.endsWith("/start"))
            .put("liveViewStopUnconfirmed", false).put("restoreToken", token)
            .put("restoreOutcome", if (sent.url.encodedPath.endsWith("/start")) "started" else "stopped")) }
    }
    @Test(timeout = 7000) fun initialStopMustPrepareExactRestorePermit() = runBlocking {
        val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest())
        access.stopLiveView()
        assertEquals(true, JSONObject(requireNotNull(client.requests.single().body)).opt("prepareRestore"))
    }
    @Test(timeout = 7000) fun restoreAndCleanupMustEchoOriginalToken() = runBlocking {
        val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest())
        access.stopLiveView(); access.restore(); client.stopLiveView()
        assertEquals(token, JSONObject(requireNotNull(client.requests[1].body)).opt("restoreToken"))
        assertEquals(token, JSONObject(requireNotNull(client.requests[2].body)).opt("restoreToken"))
    }
    @Test(timeout = 7000) fun missingPermitMustNotDispatchLegacyRestore() = runBlocking {
        val client = ExtractedMaintenanceClient(true)
        val access = client.maintenanceAccess(request, LiveViewRequest())
        access.stopLiveView()
        val failure = runCatching { access.restore() }.exceptionOrNull()
        assertNotNull("Missing restore token must reject before network", failure)
        assertEquals(1, client.requests.size)
    }

    @Test(timeout = 7000) fun malformedOrAbsentPermitFailsClosedWithoutStopDebt() = runBlocking {
        for (bad in listOf(null, "short", "a".repeat(65), "a".repeat(23) + "/", 55)) {
            val client = ExtractedMaintenanceClient(true)
            client.respond = { sent, _ -> client.jsonResponse(JSONObject().put("active", false)
                .put("liveViewStopUnconfirmed", false).also { if (bad != null) it.put("restoreToken", bad) }) }
            val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView()
            assertTrue(runCatching { access.restore() }.exceptionOrNull() is MaintenanceRestoreUnavailableException)
            assertEquals(1, client.requests.size); assertFalse(client.liveViewStopRequired)
        }
    }
    @Test(timeout = 7000) fun idleRestoreAndNonRestoringMaintenanceDoNotPrepare() = runBlocking {
        val client = ExtractedMaintenanceClient(false)
        client.maintenanceAccess(request, null).restore()
        assertTrue(client.requests.isEmpty())
        for (frozen in listOf(MaintenanceRequest(MaintenanceAction.SLEEP), request.copy(autoPowerOff = true))) {
            val active = ExtractedMaintenanceClient(true)
            active.maintenanceAccess(frozen, LiveViewRequest()).stopLiveView()
            assertNull(JSONObject(requireNotNull(active.requests.single().body)).opt("prepareRestore"))
        }
    }
    @Test(timeout = 7000) fun initialStopRequiresFreshDoubleFalseBeforeSavingPermit() = runBlocking {
        for (body in listOf(JSONObject().put("restoreToken", token), JSONObject().put("active", false).put("restoreToken", token),
            JSONObject().put("active", true).put("liveViewStopUnconfirmed", false).put("restoreToken", token))) {
            val client = ExtractedMaintenanceClient(true); client.respond = { _, _ -> client.jsonResponse(body) }
            val access = client.maintenanceAccess(request, LiveViewRequest())
            assertTrue(runCatching { access.stopLiveView() }.isFailure)
            assertTrue(client.liveViewStopRequired)
            assertTrue(runCatching { access.restore() }.isFailure); assertEquals(1, client.requests.size)
        }
    }
    @Test(timeout = 7000) fun badStartReceiptsRetainExactTokenStopDuty() = runBlocking {
        val valid = { JSONObject().put("restoreToken", token).put("restoreOutcome", "started")
            .put("active", true).put("liveViewStopUnconfirmed", false) }
        val invalid = listOf(valid().put("restoreToken", "wrong"), valid().put("restoreOutcome", "stopped"),
            valid().put("active", "true"), valid().put("liveViewStopUnconfirmed", "false"),
            JSONObject().put("active", true).put("liveViewStopUnconfirmed", false))
        for (body in invalid) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView()
            val validRespond = client.respond
            client.respond = { sent, call -> if (sent.url.encodedPath.endsWith("/start")) client.jsonResponse(body) else validRespond(sent, call) }
            assertTrue(runCatching { access.restore() }.isFailure); assertTrue(client.liveViewStopRequired)
            client.stopLiveView(); assertFalse(client.liveViewStopRequired)
            assertEquals(token, JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        }
    }
    @Test(timeout = 7000) fun terminalReceiptOnlyClearsSameTokenDuty() = runBlocking {
        for (outcome in listOf("stopped", "cancelled-before-start")) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore()
            client.respond = { _, _ -> client.jsonResponse(JSONObject().put("restoreToken", token).put("restoreOutcome", outcome)) }
            client.stopLiveView(); assertFalse(client.liveViewStopRequired)
            assertEquals(token, JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        }
    }
    @Test(timeout = 7000) fun partialOrWrongTerminalReceiptsRetainDutyAndRetryToken() = runBlocking {
        val base = { JSONObject().put("restoreToken", token).put("restoreOutcome", "stopped") }
        for (bad in listOf(base().put("restoreToken", "wrong"), base().put("restoreOutcome", "started"),
            base().put("active", false), base().put("liveViewStopUnconfirmed", false),
            base().put("active", false).put("liveViewStopUnconfirmed", true),
            base().put("active", false).put("liveViewStopUnconfirmed", false).put("restoreOutcome", "cancelled-before-start"))) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore()
            client.respond = { _, _ -> client.jsonResponse(bad) }
            assertTrue(runCatching { client.stopLiveView() }.isFailure); assertTrue(client.liveViewStopRequired)
            client.respond = { _, _ -> client.jsonResponse(base()) }
            client.stopLiveView(); assertFalse(client.liveViewStopRequired)
            assertEquals(token, JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        }
    }
    @Test(timeout = 7000) fun newerWarningBeforeStopRequiresGlobalFreshProof() = runBlocking {
        val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore()
        client.newerLiveViewWarning()
        client.respond = { _, _ -> client.jsonResponse(JSONObject().put("restoreToken", token).put("restoreOutcome", "stopped")) }
        assertTrue(runCatching { client.stopLiveView() }.isFailure)
        assertNull(JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        assertTrue(client.liveViewStopRequired)
        client.respond = { sent, _ -> client.validResponse(sent) }; client.stopLiveView(); assertFalse(client.liveViewStopRequired)
    }
    @Test(timeout = 7000) fun newerWarningDuringStopRejectsEvenFreshOrCachedOldReceipt() = runBlocking {
        for (fresh in listOf(false, true)) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore()
            client.respond = { _, _ -> client.newerLiveViewWarning(); client.jsonResponse(JSONObject()
                .put("restoreToken", token).put("restoreOutcome", "stopped").also {
                    if (fresh) it.put("active", false).put("liveViewStopUnconfirmed", false)
                }) }
            assertTrue(runCatching { client.stopLiveView() }.isFailure); assertTrue(client.liveViewStopRequired)
            client.respond = { sent, _ -> client.validResponse(sent) }; client.stopLiveView()
            assertNull(JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        }
    }
    @Test(timeout = 7000) fun secondJourneyHasNewPermitAndOldClosureCannotBorrowIt() = runBlocking {
        val client = rig(); val first = client.maintenanceAccess(request, LiveViewRequest()); first.stopLiveView(); first.restore()
        val secondToken = "synthetic_restore_token_000000002"
        client.respond = { sent, _ -> client.jsonResponse(JSONObject().put("active", sent.url.encodedPath.endsWith("/start"))
            .put("liveViewStopUnconfirmed", false).put("restoreToken", secondToken)
            .put("restoreOutcome", if (sent.url.encodedPath.endsWith("/start")) "started" else "stopped")) }
        val second = client.maintenanceAccess(request, LiveViewRequest()); second.stopLiveView()
        assertEquals(true, JSONObject(requireNotNull(client.requests.last().body)).opt("prepareRestore"))
        assertNull(JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
        val count = client.requests.size
        assertTrue(runCatching { first.restore() }.isFailure); assertEquals(count, client.requests.size)
        second.restore(); assertEquals(secondToken, JSONObject(requireNotNull(client.requests.last().body)).opt("restoreToken"))
    }
    @Test(timeout = 7000) fun sameIdReplacementRejectsLateStopReceipt() = runBlocking {
        val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore()
        client.respond = { _, _ -> client.replaceSession(); client.jsonResponse(JSONObject().put("restoreToken", token).put("restoreOutcome", "stopped")) }
        assertTrue(runCatching { client.stopLiveView() }.isFailure)
        assertTrue(client.liveViewStopRequired); assertFalse(requireNotNull(client.owner).closed)
    }
    @Test(timeout = 7000) fun startCanNeverReplayOnSamePermit() = runBlocking {
        val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView(); access.restore(); client.stopLiveView()
        val count = client.requests.size
        assertTrue(runCatching { access.restore() }.isFailure); assertEquals(count, client.requests.size)
    }

    @Test(timeout = 7000) fun oldBridge404And405NeverTriggerLegacyStartFallback() = runBlocking {
        for (code in listOf(404, 405)) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView()
            client.respond = { _, _ -> Response(code, null) }
            assertTrue(runCatching { access.restore() }.isFailure)
            assertEquals(2, client.requests.size); assertTrue(client.liveViewStopRequired)
            assertTrue(client.requests.all { "/maintenance/" in it.url.encodedPath })
        }
    }
    @Test(timeout = 7000) fun ownedStartStrictFramingStillRejectsPartialMalformedAndOversizedBodies() = runBlocking {
        val valid = JSONObject().put("restoreToken", token).put("restoreOutcome", "started")
            .put("active", true).put("liveViewStopUnconfirmed", false).toString().toByteArray()
        data class Case(val name: String, val bytes: ByteArray = valid, val declared: Long = bytes.size.toLong(),
            val status: Int = 200, val headers: Map<String, List<String>> = emptyMap())
        val cases = listOf(Case("partial status", status = 206), Case("wrong complete status", status = 201),
            Case("range", headers = mapOf("Content-Range" to listOf("bytes 0-1/2"))),
            Case("duplicate length", headers = mapOf("Content-Length" to listOf("2", "2"))),
            Case("length and encoding", headers = mapOf("Content-Length" to listOf("2"), "Transfer-Encoding" to listOf("chunked"))),
            Case("negative length", headers = mapOf("Content-Length" to listOf("-1"))),
            Case("unsupported encoding", headers = mapOf("Transfer-Encoding" to listOf("gzip"))),
            Case("short body", declared = valid.size + 1L), Case("long body", declared = valid.size - 1L),
            Case("declared oversize", declared = 65_537), Case("actual oversize", bytes = ByteArray(65_537) { 32 }, declared = -1),
            Case("invalid utf8", bytes = byteArrayOf(0xC3.toByte(), 0x28), declared = 2),
            Case("trailing json", bytes = valid + " {}".toByteArray()), Case("duplicate key", bytes = "{\"a\":1,\"a\":2}".toByteArray()),
            Case("empty", bytes = byteArrayOf()))
        for (case in cases) {
            val client = rig(); val access = client.maintenanceAccess(request, LiveViewRequest()); access.stopLiveView()
            val response = Response(case.status, ResponseBody(java.io.ByteArrayInputStream(case.bytes), case.declared), case.headers)
            client.respond = { _, _ -> response }
            assertTrue(case.name, runCatching { access.restore() }.isFailure)
            assertTrue(case.name, client.liveViewStopRequired); assertEquals(1, response.closes.get())
            assertEquals(1, client.calls.last().enqueueCount.get()); assertEquals(0, client.calls.last().executeCount.get())
        }
    }
}

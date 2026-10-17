package dev.openeos.control.data

import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Route through the real dispatch closure/helper, not through the reader alone. */
class MaintenanceDispatchStrictTest {
    private fun outcome(operation: String, off: Boolean) = JSONObject().put("version", 1).put("engine", "synthetic-engine")
        .put("operation", operation).put("autoPowerOff", off).put("responseEvidence", "response-received")
    @Test(timeout = 7000) fun dispatchRejectsInvalidFramingLengthUtf8AndSizeBeforeResponseEvidence() = runBlocking {
        val valid = outcome("sensor-cleaning", true).toString().toByteArray()
        data class Case(val name: String, val bytes: ByteArray = valid, val declared: Long = bytes.size.toLong(),
            val status: Int = 200, val headers: Map<String, List<String>> = emptyMap())
        val cases = listOf(
            Case("partial status", status = 206),
            Case("wrong complete status", status = 201),
            Case("Content-Range", headers = mapOf("Content-Range" to listOf("bytes 0-1/2"))),
            Case("duplicate Content-Length", headers = mapOf("Content-Length" to listOf("2", "2"))),
            Case("length plus transfer encoding", headers = mapOf("Content-Length" to listOf("2"), "Transfer-Encoding" to listOf("chunked"))),
            Case("negative Content-Length", headers = mapOf("Content-Length" to listOf("-1"))),
            Case("unsupported Transfer-Encoding", headers = mapOf("Transfer-Encoding" to listOf("gzip"))),
            Case("short body", declared = valid.size + 1L),
            Case("long body", declared = valid.size - 1L),
            Case("declared oversize", declared = 65_537L),
            Case("actual oversize", bytes = ByteArray(65_537) { 32 }, declared = -1L),
            Case("invalid UTF-8", bytes = byteArrayOf(0xC3.toByte(), 0x28), declared = 2L),
            Case("trailing JSON", bytes = valid + " {}".toByteArray()),
            Case("duplicate JSON key", bytes = "{\"duplicate\":1,\"duplicate\":2}".toByteArray()),
            Case("empty response", bytes = byteArrayOf()),
        )
        for (case in cases) {
            val client = ExtractedMaintenanceClient(false)
            val response = Response(case.status, ResponseBody(ByteArrayInputStream(case.bytes), case.declared), case.headers)
            client.respond = { _, _ -> response }
            val request = MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING, autoPowerOff = true)
            val evidence = MaintenanceEvidence(request, eventStopRequired = false, liveViewStopRequired = false)
            val failure = runCatching { client.maintenanceAccess(request, null).dispatch(request, evidence) }.exceptionOrNull()
            assertNotNull(case.name, failure)
            assertEquals(case.name, MaintenanceCommandEvidence.UNKNOWN, evidence.snapshot().command)
            assertEquals(case.name, 1, client.requests.size)
            assertEquals(case.name, 1, client.calls.single().enqueueCount.get())
            assertEquals(case.name, 0, client.calls.single().executeCount.get())
            assertEquals(case.name, 1, response.closes.get())
            assertEquals(case.name, TimeUnit.SECONDS.toNanos(15), client.calls.single().timeout().timeoutNanos())
        }
        println("Strict dispatch rejection matrix: ${cases.size} actual maintenanceAccess.dispatch cases passed")
    }
    @Test(timeout = 7000) fun sleepUsesCapturedOutcomeRouteOnceAndDoesNotReadOrRestore() = runBlocking {
        val client = ExtractedMaintenanceClient(false)
        client.respond = { _, _ -> client.jsonResponse(outcome("sleep", false)) }
        val request = MaintenanceRequest(MaintenanceAction.SLEEP)
        val evidence = MaintenanceEvidence(request, eventStopRequired = false, liveViewStopRequired = false)
        client.maintenanceAccess(request, null).dispatch(request, evidence)
        assertEquals(MaintenanceCommandEvidence.RESPONSE_RECEIVED, evidence.snapshot().command)
        assertEquals(MaintenanceRestoreEvidence.NOT_REQUESTED, evidence.snapshot().restore)
        assertEquals(listOf("POST /v1/session/synthetic-session/maintenance/sleep/outcome"),
            client.requests.map { "${it.method} ${it.url.encodedPath}" })
        assertEquals(0, client.calls.single().executeCount.get())
        assertEquals(1, client.calls.single().enqueueCount.get())
    }
}

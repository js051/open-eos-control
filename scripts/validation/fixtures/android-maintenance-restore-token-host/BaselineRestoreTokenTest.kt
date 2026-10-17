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

}

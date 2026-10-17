package dev.openeos.control.ui

import dev.openeos.control.data.*
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Executes exact Bridge status observation, VM refresh/publication, Retry Stop and repository owner gate. */
class MaintenanceStatusPublicationTest {
    private val token = "synthetic_restore_token_000000001"
    private class Rig(val client: ExtractedMaintenanceClient, val queue: QueueDispatcher,
        val repository: HostRepository, val vm: ExtractedMaintenanceViewModel)
    private suspend fun rig(): Rig {
        val client = ExtractedMaintenanceClient(true); val queue = QueueDispatcher()
        val repository = HostRepository(client); val vm = ExtractedMaintenanceViewModel(repository, queue)
        client.respond = { sent, _ -> client.jsonResponse(JSONObject().put("active", sent.url.encodedPath.endsWith("/start"))
            .put("liveViewStopUnconfirmed", false).put("restoreToken", token)
            .put("restoreOutcome", if (sent.url.encodedPath.endsWith("/start")) "started" else "stopped")) }
        val access = client.maintenanceAccess(MaintenanceRequest(MaintenanceAction.SENSOR_CLEANING), LiveViewRequest())
        access.stopLiveView(); access.restore()
        vm._uiState.value = vm.state.copy(maintenanceSuspended = true, needsLiveViewStopRecovery = true,
            liveViewStopUnconfirmed = true, status = CameraStatus(liveViewStopUnconfirmed = true))
        return Rig(client, queue, repository, vm)
    }
    private fun cached(rig: Rig) = rig.client.jsonResponse(JSONObject().put("restoreToken", token).put("restoreOutcome", "stopped"))
    private fun freshRetry(rig: Rig) {
        rig.client.respond = { sent, _ -> rig.client.jsonResponse(JSONObject().put("active", false).put("liveViewStopUnconfirmed", false)) }
        val before = rig.client.requests.size
        rig.vm.restartLiveView(); assertTrue(rig.queue.until { rig.vm.state.pendingOperations.isEmpty() })
        assertEquals("Explicit fresh Retry must dispatch one Stop", before + 1, rig.client.requests.size)
        val sent = rig.client.requests.last()
        assertTrue(sent.url.encodedPath.endsWith("/maintenance/control/liveview/stop"))
        assertNull(JSONObject(requireNotNull(sent.body)).opt("restoreToken"))
        assertFalse(rig.client.liveViewStopRequired); assertFalse(rig.vm.state.liveViewStopUnconfirmed)
        assertEquals(false, rig.vm.state.status?.liveViewStopUnconfirmed)
        assertTrue(rig.vm.state.maintenanceSuspended); assertFalse(rig.repository.liveViewRunning)
    }
    @Test(timeout = 7000) fun warningPublishedBeforeOwnedRetrySelectsFreshGlobalStop() = runBlocking {
        val rig = rig(); rig.vm.refresh(); assertTrue(rig.queue.until { rig.vm.state.pendingOperations.isEmpty() })
        assertEquals(true, rig.vm.state.status?.liveViewStopUnconfirmed)
        assertTrue(rig.client.maintenanceStopOwned)
        freshRetry(rig)
    }
    @Test(timeout = 7000) fun warningPublishedDuringOwnedRetryRejectsReceiptThenExplicitRetryIsFresh() = runBlocking {
        val rig = rig(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val text = JSONObject().put("restoreToken", token).put("restoreOutcome", "stopped").toString().toByteArray()
        rig.client.respond = { _, _ -> Response(200, ResponseBody(object : ByteArrayInputStream(text) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); return super.read(bytes, offset, length)
            }
        })) }
        try {
            rig.vm.restartLiveView(); assertTrue(rig.queue.until { entered.count == 0L })
            rig.vm.refresh(); assertTrue(rig.queue.until { CameraOperation.STATUS !in rig.vm.state.pendingOperations })
            val warning = rig.vm.state.status
            assertEquals(true, warning?.liveViewStopUnconfirmed)
            release.countDown(); assertTrue(rig.queue.until { rig.vm.state.pendingOperations.isEmpty() })
            assertSame(warning, rig.vm.state.status); assertTrue(rig.client.maintenanceStopOwned)
            freshRetry(rig)
        } finally { release.countDown() }
    }
    @Test(timeout = 7000) fun warningPublishedAfterTerminalReceiptKeepsStrictRecoveryAvailable() = runBlocking {
        val rig = rig(); rig.client.respond = { _, _ -> cached(rig) }
        rig.vm.restartLiveView(); assertTrue(rig.queue.until { rig.vm.state.pendingOperations.isEmpty() })
        assertFalse(rig.client.liveViewStopRequired)
        rig.vm.refresh(); assertTrue(rig.queue.until { rig.vm.state.pendingOperations.isEmpty() })
        assertEquals(true, rig.vm.state.status?.liveViewStopUnconfirmed)
        assertTrue("A new production Bridge status warning must retain strict maintenance ownership", rig.client.maintenanceStopOwned)
        freshRetry(rig)
    }
}

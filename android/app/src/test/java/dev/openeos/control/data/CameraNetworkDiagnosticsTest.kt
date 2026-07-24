package dev.openeos.control.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraNetworkDiagnosticsTest {
    @Test
    fun coexistenceRequiresCameraWifiAndAValidatedCellularDefaultRoute() {
        val ready = CameraNetworkDiagnostics(
            routing = CameraNetworkRouting.WIFI_BOUND,
            cameraNetworkAvailable = true,
            wifiAvailable = true,
            cellularAvailable = true,
            cellularValidated = true,
            systemDefaultTransport = SystemNetworkTransport.CELLULAR,
            systemDefaultValidated = true,
        )

        assertTrue(ready.wifiCellularCoexistence)
        assertFalse(ready.copy(routing = CameraNetworkRouting.SYSTEM_DEFAULT).wifiCellularCoexistence)
        assertFalse(ready.copy(cameraNetworkAvailable = false).wifiCellularCoexistence)
        assertFalse(ready.copy(systemDefaultTransport = SystemNetworkTransport.WIFI).wifiCellularCoexistence)
        assertFalse(ready.copy(systemDefaultValidated = false).wifiCellularCoexistence)
    }
}

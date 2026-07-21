package dev.openeos.control.ui

import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraNetworkRouting
import dev.openeos.control.data.CameraTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun rollingFpsUsesFrameIntervals() {
        assertEquals(10.0, rollingFps(listOf(1_000L, 1_100L, 1_200L, 1_300L)), 0.001)
        assertEquals(0.0, rollingFps(listOf(1_000L)), 0.001)
    }

    @Test
    fun diagnosticReportNeverIncludesCredentials() {
        val state = CameraUiState(
            baseUrl = "https://camera-user:secret@192.168.1.2:443",
            username = "camera-user",
            password = "secret",
            liveViewDiagnostics = LiveViewDiagnostics(sourceUrl = "https://camera-user:secret@192.168.1.2/frame"),
            error = "Authorization: Basic camera-user:secret",
        )

        val report = buildDiagnosticReport(state)

        assertFalse(report.contains("secret"))
        assertFalse(report.contains("camera-user"))
        assertFalse(report.contains("Basic"))
        assertTrue(report.contains("192.168.1.2"))
    }

    @Test
    fun diagnosticReportIncludesCameraNetworkRouteAndStreamHealth() {
        val state = CameraUiState(
            networkDiagnostics = CameraNetworkDiagnostics(
                routing = CameraNetworkRouting.WIFI_BOUND,
                targetHost = "192.168.1.2",
                networkHandle = 42L,
                interfaceName = "wlan0",
                wifiAvailable = true,
                cellularAvailable = true,
            ),
            liveViewDiagnostics = LiveViewDiagnostics(lastFrameAtMillis = 1_000L),
        )

        val report = buildDiagnosticReport(state)

        assertTrue(report.contains("cameraRoute=WIFI_BOUND"))
        assertTrue(report.contains("cameraInterface=wlan0"))
        assertTrue(report.contains("cellularAvailable=true"))
        assertTrue(report.contains("liveViewHealthy=true"))
    }

    @Test
    fun usbDiagnosticReportDoesNotShowTheStaleCcapiUrl() {
        val report = buildDiagnosticReport(
            CameraUiState(
                baseUrl = "http://192.168.1.2:8080",
                transport = CameraTransport.USB_PTP,
            )
        )

        assertTrue(report.contains("transport=USB_PTP"))
        assertTrue(report.contains("baseUrl=not-applicable"))
        assertFalse(report.contains("192.168.1.2"))
    }

    @Test
    fun advancedSettingsAreFilteredByCaptureMode() {
        val settings = listOf(
            CameraSettingControl("moviequality", "Movie quality", "4K", listOf("4K")),
            CameraSettingControl("drive", "Drive", "single", listOf("single")),
            CameraSettingControl("afmethod", "AF", "face", listOf("face")),
        )

        assertEquals(listOf("drive", "afmethod"), settingsForMode(settings, CaptureMode.PHOTO).map { it.key })
        assertEquals(listOf("moviequality", "afmethod"), settingsForMode(settings, CaptureMode.VIDEO).map { it.key })
    }
}

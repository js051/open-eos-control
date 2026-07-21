package dev.openeos.control.ui

import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraCapabilityEvidence
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraNetworkRouting
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.ExposureState
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
            baseUrl = "https://camera-user:secret@192.168.1.2:443?access_token=query-secret",
            username = "camera-user",
            password = "secret",
            liveViewDiagnostics = LiveViewDiagnostics(sourceUrl = "https://camera-user:secret@192.168.1.2/frame"),
            error = "Authorization: Basic camera-user:secret",
        )

        val report = buildDiagnosticReport(state)

        assertFalse(report.contains("secret"))
        assertFalse(report.contains("camera-user"))
        assertFalse(report.contains("query-secret"))
        assertFalse(report.contains("Basic"))
        assertTrue(report.contains("192.168.1.2"))
    }

    @Test
    fun diagnosticReportIncludesCameraNetworkRouteAndStreamHealth() {
        val state = CameraUiState(
            capabilities = CameraCapabilities(
                iso = emptyList(),
                shutter = emptyList(),
                aperture = emptyList(),
                whiteBalance = emptyList(),
                evidence = CameraCapabilityEvidence(
                    source = "GET /ccapi",
                    protocolVersions = listOf("ver100"),
                    advertisedCommands = listOf("POST /ccapi/ver100/shooting/control/shutterbutton"),
                    writableSettings = listOf("iso", "tv"),
                ),
            ),
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
        assertTrue(report.contains("capabilitySource=GET /ccapi"))
        assertTrue(report.contains("advertisedCommandCount=1"))
        assertTrue(report.contains("POST /ccapi/ver100/shooting/control/shutterbutton"))
        assertTrue(report.contains("writableSettings=iso, tv"))
    }

    @Test
    fun usbDiagnosticReportDoesNotShowTheStaleCcapiUrl() {
        val report = buildDiagnosticReport(
            CameraUiState(
                baseUrl = "http://192.168.1.2:8080",
                transport = CameraTransport.USB_PTP,
                status = CameraStatus(
                    connected = true,
                    batteryLevel = 82,
                    batteryStatus = "82%",
                    recording = null,
                    mode = "M",
                    mediaAvailable = true,
                    remainingMinutes = null,
                    exposure = ExposureState("400", "1/50", "2.8", "auto"),
                    rawTransportJson = "{\"kind\":\"ptp-usb\",\"operations\":[\"0x1014\"]}",
                ),
            )
        )

        assertTrue(report.contains("transport=USB_PTP"))
        assertTrue(report.contains("baseUrl=not-applicable"))
        assertTrue(report.contains("transportDetails={\"kind\":\"ptp-usb\""))
        assertFalse(report.contains("192.168.1.2"))
    }

    @Test
    fun bridgeDiagnosticUsesBridgeUrlAndRedactsBearerToken() {
        val report = buildDiagnosticReport(
            CameraUiState(
                baseUrl = "http://192.168.1.2:8080",
                bridgeBaseUrl = "http://10.0.2.2:18181",
                bridgeToken = "bridge-super-secret",
                transport = CameraTransport.DESKTOP_BRIDGE,
                info = CameraInfo(
                    connected = true,
                    model = "Canon EOS R6 Mark III",
                    serial = "test",
                    api = "desktop-bridge/v1/libgphoto2",
                    manufacturer = "Canon.Inc",
                    deviceVersion = "3-1.0.0",
                    engineVersion = "gphoto2 2.5.33",
                ),
                error = "Authorization: Bearer bridge-super-secret",
            )
        )

        assertTrue(report.contains("transport=DESKTOP_BRIDGE"))
        assertTrue(report.contains("baseUrl=http://10.0.2.2:18181"))
        assertFalse(report.contains("192.168.1.2"))
        assertFalse(report.contains("bridge-super-secret"))
        assertFalse(report.contains("Bearer"))
        assertTrue(report.contains("engineVersion=gphoto2 2.5.33"))
    }

    @Test
    fun advancedSettingsAreFilteredByCaptureMode() {
        val settings = listOf(
            CameraSettingControl("moviequality", "Movie quality", "4K", listOf("4K")),
            CameraSettingControl("drivemode", "Drive", "single", listOf("single")),
            CameraSettingControl("movieservoaf", "Movie Servo AF", "on", listOf("on")),
            CameraSettingControl("afmethod", "AF", "face", listOf("face")),
        )

        assertEquals(listOf("drivemode", "afmethod"), settingsForMode(settings, CaptureMode.PHOTO).map { it.key })
        assertEquals(
            listOf("moviequality", "movieservoaf", "afmethod"),
            settingsForMode(settings, CaptureMode.VIDEO).map { it.key },
        )
    }
}

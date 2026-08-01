package dev.openeos.control.ui

import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraCapabilityEvidence
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraNetworkDiagnostics
import dev.openeos.control.data.CameraNetworkRouting
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraTransport
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.NativeLiveViewAudioStatus
import dev.openeos.control.data.SystemNetworkTransport
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
    fun diagnosticReportIncludesTheEffectiveControlOrientationPolicy() {
        val report = buildDiagnosticReport(
            state = CameraUiState(),
            metadata = DiagnosticReportMetadata(
                systemAutoRotationEnabled = false,
                controlRotationDegrees = -90f,
            ),
        )

        assertTrue(report.contains("controlOrientationMode=FOLLOW_SYSTEM"))
        assertTrue(report.contains("systemAutoRotationEnabled=false"))
        assertTrue(report.contains("controlRotationDegrees=-90.0"))
    }

    @Test
    fun diagnosticReportRedactsMachineLocalPaths() {
        val windowsHome = "C:" + "\\Users\\Private User\\capture.jpg"
        val networkHome = "\\\\" + "PRIVATE-SERVER\\private\\capture.jpg"
        val unixHome = "/" + "Users/private/capture.jpg"
        val report = buildDiagnosticReport(
            CameraUiState(
                error =
                    "Failed $windowsHome, $networkHome, C:/dev/frame.jpg, " +
                        "$unixHome, /data/user/0/dev.openeos.control/frame.jpg and file:///tmp/frame.jpg",
            )
        )

        assertFalse(report.contains("C:" + "\\Users"))
        assertFalse(report.contains("Private User"))
        assertFalse(report.contains("PRIVATE-SERVER"))
        assertFalse(report.contains("C:/dev"))
        assertFalse(report.contains("/" + "Users/private"))
        assertFalse(report.contains("/data/user"))
        assertFalse(report.contains("file:///tmp"))
        assertTrue(report.contains("[local-path]"))
    }

    @Test
    fun diagnosticReportIsVersionedRedactsSerialAndSummarizesValidationEvidence() {
        val serial = "PRIVATE-CAMERA-SERIAL"
        val report = buildDiagnosticReport(
            state = CameraUiState(
                info = CameraInfo(
                    connected = true,
                    model = "Canon EOS R6 Mark III",
                    serial = serial,
                    api = "ccapi",
                ),
                status = CameraStatus(
                    connected = true,
                    batteryLevel = 80,
                    batteryStatus = "good",
                    recording = false,
                    mode = "M",
                    mediaAvailable = true,
                    remainingMinutes = null,
                    exposure = ExposureState("400", "1/125", "2.8", "auto"),
                    rawTransportJson = "{\"serial\":\"$serial\"}",
                ),
                capabilities = CameraCapabilities(
                    iso = emptyList(),
                    shutter = emptyList(),
                    aperture = emptyList(),
                    whiteBalance = emptyList(),
                    matrix = dev.openeos.control.data.CapabilityMatrix(
                        supported = setOf(
                            CameraFeature.CAMERA_IDENTITY,
                            CameraFeature.LIVE_VIEW,
                            CameraFeature.STILL_CAPTURE,
                        ),
                    ),
                    evidence = CameraCapabilityEvidence(
                        observedFeatures = setOf(
                            CameraFeature.CAMERA_IDENTITY,
                            CameraFeature.LIVE_VIEW,
                            CameraFeature.USB_DIAGNOSTICS,
                        ),
                    ),
                ),
                lastClockSyncAtMillis = 1_784_480_400_000L,
                error = "Camera $serial rejected a request",
            ),
            metadata = DiagnosticReportMetadata(
                productVersion = "9.8.7-test",
                generatedAt = "2026-07-29T00:00:00Z",
            ),
        )

        assertTrue(report.contains("reportSchema=1"))
        assertTrue(report.contains("generatedAt=2026-07-29T00:00:00Z"))
        assertTrue(report.contains("productVersion=9.8.7-test"))
        assertTrue(report.contains("serial=[redacted]"))
        assertFalse(report.contains(serial))
        assertTrue(report.contains("advertisedFeatureCount=3"))
        assertTrue(report.contains("observedFeatureCount=3"))
        assertTrue(report.contains("validatedAdvertisedFeatureCount=2"))
        assertTrue(report.contains("unverifiedAdvertisedFeatures=STILL_CAPTURE"))
        assertTrue(report.contains("observedWithoutAdvertisement=USB_DIAGNOSTICS"))
        assertTrue(report.contains("lastClockSyncAtMillis=1784480400000"))
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
                    observedFeatures = setOf(CameraFeature.CAMERA_IDENTITY, CameraFeature.LIVE_VIEW),
                ),
            ),
            networkDiagnostics = CameraNetworkDiagnostics(
                routing = CameraNetworkRouting.WIFI_BOUND,
                targetHost = "192.168.1.2",
                networkHandle = 42L,
                interfaceName = "wlan0",
                cameraNetworkAvailable = true,
                wifiAvailable = true,
                cellularAvailable = true,
                cellularValidated = true,
                systemDefaultTransport = SystemNetworkTransport.CELLULAR,
                systemDefaultValidated = true,
                systemDefaultNetworkHandle = 84L,
                systemDefaultInterfaceName = "rmnet_data0",
            ),
            liveViewDiagnostics = LiveViewDiagnostics(lastFrameAtMillis = 1_000L),
            liveViewSource = LiveViewSource.CCAPI_RTP,
            liveViewAudioStatus = NativeLiveViewAudioStatus(
                advertised = true,
                available = true,
                enabled = false,
                codec = "MP4A-LATM",
                rtpPort = 12_010,
                rtpClockRate = 48_000,
                sampleRate = 48_000,
                channels = 2,
                packetsReceived = 12,
                accessUnitsReceived = 10,
                decodedAccessUnits = 8,
                playedSampleFrames = 8_192,
                droppedAccessUnits = 2,
                underruns = 1,
                lastPacketAtMillis = 900,
                lastPcmAtMillis = 850,
                error = null,
            ),
            monitorSettings = LiveViewMonitorSettings(
                histogramVisible = true,
                waveformVisible = true,
                zebraThresholdPercent = 95,
                falseColorEnabled = true,
                focusPeakingEnabled = true,
                frameGuide = LiveViewFrameGuide.RATIO_2_39,
                safeAreaVisible = true,
                desqueeze = LiveViewDesqueeze.X1_33,
            ),
        )

        val report = buildDiagnosticReport(state)

        assertTrue(report.contains("cameraRoute=WIFI_BOUND"))
        assertTrue(report.contains("cameraInterface=wlan0"))
        assertTrue(report.contains("cameraNetworkAvailable=true"))
        assertTrue(report.contains("cellularAvailable=true"))
        assertTrue(report.contains("cellularValidated=true"))
        assertTrue(report.contains("systemDefaultTransport=CELLULAR"))
        assertTrue(report.contains("systemDefaultValidated=true"))
        assertTrue(report.contains("systemDefaultInterface=rmnet_data0"))
        assertTrue(report.contains("wifiCellularCoexistence=true"))
        assertTrue(report.contains("liveViewHealthy=true"))
        assertTrue(report.contains("liveViewSource=CCAPI_RTP"))
        assertTrue(report.contains("rtpAudioAdvertised=true"))
        assertTrue(report.contains("rtpAudioAvailable=true"))
        assertTrue(report.contains("rtpAudioEnabled=false"))
        assertTrue(report.contains("rtpAudioCodec=MP4A-LATM"))
        assertTrue(report.contains("rtpAudioPort=12010"))
        assertTrue(report.contains("rtpAudioClockRate=48000"))
        assertTrue(report.contains("rtpAudioSampleRate=48000"))
        assertTrue(report.contains("rtpAudioChannels=2"))
        assertTrue(report.contains("rtpAudioPackets=12"))
        assertTrue(report.contains("rtpAudioAccessUnits=10"))
        assertTrue(report.contains("rtpAudioDecodedAccessUnits=8"))
        assertTrue(report.contains("rtpAudioPlayedSampleFrames=8192"))
        assertTrue(report.contains("rtpAudioDroppedAccessUnits=2"))
        assertTrue(report.contains("rtpAudioUnderruns=1"))
        assertTrue(report.contains("monitorHistogram=true"))
        assertTrue(report.contains("monitorWaveform=true"))
        assertTrue(report.contains("monitorZebra=95"))
        assertTrue(report.contains("monitorFalseColor=true"))
        assertTrue(report.contains("monitorFocusPeaking=true"))
        assertTrue(report.contains("monitorFrameGuide=RATIO_2_39"))
        assertTrue(report.contains("monitorSafeArea=true"))
        assertTrue(report.contains("monitorDesqueeze=X1_33"))
        assertTrue(report.contains("capabilitySource=GET /ccapi"))
        assertTrue(report.contains("advertisedCommandCount=1"))
        assertTrue(report.contains("POST /ccapi/ver100/shooting/control/shutterbutton"))
        assertTrue(report.contains("writableSettings=iso, tv"))
        assertTrue(report.contains("observedFeatures=CAMERA_IDENTITY, LIVE_VIEW"))
    }

    @Test
    fun physicalValidationRequiresAdvertisedAndObservedEvidence() {
        val state = physicalCameraState().copy(
            operatorConfirmedFeatures = setOf(
                CameraFeature.STILL_CAPTURE,
                CameraFeature.LIVE_VIEW,
                CameraFeature.USB_DIAGNOSTICS,
            ),
        )

        val validation = physicalValidationSummary(state)

        assertEquals(PhysicalValidationSessionStatus.READY, validation.sessionStatus)
        assertEquals(setOf(CameraFeature.STILL_CAPTURE), validation.eligibleFeatures)
        assertEquals(setOf(CameraFeature.STILL_CAPTURE), validation.operatorConfirmedFeatures)
    }

    @Test
    fun simulatorAndOfflinePreviewCannotProducePhysicalValidation() {
        val simulator = physicalCameraState().copy(
            info = physicalCameraState().info?.copy(api = "simulated-ccapi"),
        )

        assertEquals(
            PhysicalValidationSessionStatus.SIMULATOR,
            physicalValidationSummary(simulator).sessionStatus,
        )
        assertTrue(physicalValidationSummary(simulator).eligibleFeatures.isEmpty())
        assertEquals(
            PhysicalValidationSessionStatus.OFFLINE_PREVIEW,
            physicalValidationSummary(physicalCameraState().copy(previewMode = true)).sessionStatus,
        )
        assertTrue(
            runCatching { buildPhysicalValidationRecord(simulator) }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun physicalValidationRecordIsPrivacySafeAndBindsToSanitizedDiagnostic() {
        val privateSerial = "PRIVATE-CAMERA-SERIAL"
        val privatePassword = "camera-password"
        val privateLocalPath = "C:/Us" + "ers/private/capture.jpg"
        val state = physicalCameraState().copy(
            baseUrl = "http://camera-user:$privatePassword@192.168.1.2:8080",
            password = privatePassword,
            info = physicalCameraState().info?.copy(serial = privateSerial),
            operatorConfirmedFeatures = setOf(CameraFeature.STILL_CAPTURE),
            error = "Camera $privateSerial at $privateLocalPath",
        )
        val metadata = DiagnosticReportMetadata(
            productVersion = "0.1.9-test",
            generatedAt = "2026-08-01T00:00:00Z",
        )

        val record = buildPhysicalValidationRecord(state, metadata)

        assertTrue(record.contains("Record schema: 1"))
        assertTrue(record.contains("Camera model: Canon EOS R6 Mark III"))
        assertTrue(record.contains("| STILL_CAPTURE | true | true | true |"))
        assertTrue(record.contains("| LIVE_VIEW | true | false | false |"))
        assertTrue(record.contains(Regex("Diagnostic SHA-256: `[0-9a-f]{64}`")))
        assertFalse(record.contains(privateSerial))
        assertFalse(record.contains(privatePassword))
        assertFalse(record.contains("192.168.1.2"))
        assertFalse(record.contains("C:/Us" + "ers"))
        assertFalse(record.contains("serial", ignoreCase = true))
        assertFalse(record.contains("baseUrl", ignoreCase = true))
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
                    storageTotalBytes = 64_000L,
                    storageFreeBytes = 32_000L,
                    storageFreeImages = 1_234L,
                    storageDeviceCount = 1,
                    rawTransportJson = "{\"kind\":\"ptp-usb\",\"operations\":[\"0x1014\"]}",
                ),
            )
        )

        assertTrue(report.contains("transport=USB_PTP"))
        assertTrue(report.contains("baseUrl=not-applicable"))
        assertTrue(report.contains("transportDetails={\"kind\":\"ptp-usb\""))
        assertTrue(report.contains("storageTotalBytes=64000"))
        assertTrue(report.contains("storageFreeBytes=32000"))
        assertTrue(report.contains("storageFreeImages=1234"))
        assertTrue(report.contains("storageDevices=1"))
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
            CameraSettingControl("shootingmode", "Shooting mode", "Manual", listOf("P", "Manual", "Movie")),
            CameraSettingControl("moviequality", "Movie quality", "4K", listOf("4K", "FHD")),
            CameraSettingControl("drivemode", "Drive", "single", listOf("single", "continuous")),
            CameraSettingControl("stillimagequalitycf", "CF quality", "RAW", listOf("RAW", "JPEG")),
            CameraSettingControl("colorspace", "Color space", "sRGB", listOf("sRGB", "AdobeRGB")),
            CameraSettingControl("highisonr", "High ISO NR", "Off", listOf("Off", "High")),
            CameraSettingControl("aeb", "AEB", "off", listOf("off", "+/- 1")),
            CameraSettingControl("aspectratio", "Aspect ratio", "3:2", listOf("3:2", "16:9")),
            CameraSettingControl("zoomspeed", "Power zoom speed", "8", listOf("1", "8", "15")),
            CameraSettingControl("autopoweroff", "Auto power off", "30 seconds", listOf("30 seconds", "Disable")),
            CameraSettingControl("exposurecompensation", "Exposure compensation", "0", listOf("-1", "0", "1")),
            CameraSettingControl("movieservoaf", "Movie Servo AF", "on", listOf("off", "on")),
            CameraSettingControl("afmethod", "AF", "face", listOf("face", "spot")),
            CameraSettingControl("capturetarget", "Capture target", "Internal RAM", listOf("Internal RAM", "Memory card")),
            CameraSettingControl("capturestorage", "Recording card", "CFe", listOf("CFe", "SD")),
            CameraSettingControl("singleoption", "Single option", "only", listOf("only")),
        )

        assertEquals(
            listOf(
                "shootingmode",
                "drivemode",
                "stillimagequalitycf",
                "colorspace",
                "highisonr",
                "aeb",
                "aspectratio",
                "zoomspeed",
                "autopoweroff",
                "exposurecompensation",
                "afmethod",
                "capturetarget",
                "capturestorage",
            ),
            settingsForMode(settings, CaptureMode.PHOTO).map { it.key },
        )
        assertEquals(
            listOf(
                "shootingmode",
                "moviequality",
                "zoomspeed",
                "autopoweroff",
                "exposurecompensation",
                "movieservoaf",
                "afmethod",
            ),
            settingsForMode(settings, CaptureMode.VIDEO).map { it.key },
        )
    }

    private fun physicalCameraState(): CameraUiState = CameraUiState(
        transport = CameraTransport.CCAPI_NETWORK,
        info = CameraInfo(
            connected = true,
            model = "Canon EOS R6 Mark III",
            serial = "TEST-SERIAL-0001",
            api = "ccapi",
        ),
        status = CameraStatus(
            connected = true,
            batteryLevel = 80,
            batteryStatus = "good",
            recording = false,
            mode = "M",
            mediaAvailable = true,
            remainingMinutes = null,
            exposure = ExposureState("400", "1/125", "2.8", "auto"),
        ),
        capabilities = CameraCapabilities(
            iso = emptyList(),
            shutter = emptyList(),
            aperture = emptyList(),
            whiteBalance = emptyList(),
            matrix = dev.openeos.control.data.CapabilityMatrix(
                supported = setOf(CameraFeature.STILL_CAPTURE, CameraFeature.LIVE_VIEW),
            ),
            evidence = CameraCapabilityEvidence(
                source = "GET /ccapi",
                observedFeatures = setOf(CameraFeature.STILL_CAPTURE, CameraFeature.USB_DIAGNOSTICS),
            ),
        ),
    )
}

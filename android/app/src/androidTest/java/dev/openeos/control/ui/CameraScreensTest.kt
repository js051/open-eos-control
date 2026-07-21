package dev.openeos.control.ui

import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.annotation.StringRes
import dev.openeos.control.R
import dev.openeos.control.data.CameraCapabilityEvidence
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewCapabilities
import dev.openeos.control.data.UsbCameraDevice
import dev.openeos.control.data.UsbCameraInterface
import dev.openeos.control.data.UsbPtpDiagnostics
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

class CameraScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun disconnectedStateShowsDedicatedConnectionScreen() {
        compose.setContent { MaterialTheme { ConnectionScreen(CameraUiState(), noOpActions()) } }
        compose.onNodeWithText(resourceText(R.string.connect_title)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.preview_interface)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.connect)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun readyCanonPtpDeviceOffersRealUsbConnectAction() {
        var selectedDevice: Triple<String, Int, Int>? = null
        val device = UsbCameraDevice(
            deviceName = "usb-r6m3",
            manufacturerName = "Canon",
            productName = "EOS R6 Mark III",
            vendorId = 0x04A9,
            productId = 0x1234,
            deviceClass = 0,
            deviceSubclass = 0,
            deviceProtocol = 0,
            hasPermission = true,
            interfaces = listOf(
                UsbCameraInterface(
                    id = 0,
                    interfaceClass = 6,
                    interfaceSubclass = 1,
                    interfaceProtocol = 1,
                    endpoints = emptyList(),
                )
            ),
        )
        val actions = noOpActions().copy(
            connectUsb = { name, vendorId, productId ->
                selectedDevice = Triple(name, vendorId, productId)
            }
        )
        compose.setContent {
            MaterialTheme {
                ConnectionScreen(
                    CameraUiState(usbDiagnostics = UsbPtpDiagnostics(listOf(device))),
                    actions,
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.connect_usb_camera)).performScrollTo().performClick()

        compose.runOnIdle {
            assertEquals(Triple("usb-r6m3", 0x04A9, 0x1234), selectedDevice)
        }
    }

    @Test
    fun desktopBridgeModeCanScanSelectAndConnectWithoutPersistingTokenUi() {
        var scanRequested = false
        var selectedCamera: String? = null
        var connectRequested = false
        val camera = DesktopBridgeCamera(
            id = "camera-r6m3",
            model = "Canon EOS R6 Mark III",
            port = "usb:001,007",
            engine = "libgphoto2",
        )
        val actions = noOpActions().copy(
            scanDesktopBridge = { scanRequested = true },
            selectBridgeCamera = { selectedCamera = it },
            connectBridge = { connectRequested = true },
        )
        compose.setContent {
            MaterialTheme {
                ConnectionScreen(
                    CameraUiState(
                        connectionTarget = ConnectionTarget.DESKTOP_BRIDGE,
                        bridgeToken = "secret",
                        bridgeCameras = listOf(camera),
                    ),
                    actions,
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.desktop_bridge_token_hint)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.scan_desktop_bridge)).performClick()
        compose.onNodeWithText(camera.model).performClick()
        compose.onNodeWithText(resourceText(R.string.connect_desktop_bridge)).performScrollTo().performClick()

        compose.runOnIdle {
            assertTrue(scanRequested)
            assertEquals(camera.id, selectedCamera)
            assertTrue(connectRequested)
        }
    }

    @Test
    fun offlinePreviewShowsCameraControlsWithoutAConnection() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
            }
        }

        compose.onNodeWithText(resourceText(R.string.offline_preview)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
    }

    @Test
    fun localizedAdvancedSettingStillSendsCameraRawValue() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var request: Pair<String, String>? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setCameraSetting = { key, value -> request = key to value },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    CameraUiState().withOfflinePreview().copy(activeSettingPicker = picker.value),
                    actions,
                )
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-aspectratio")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-aspectratio").performScrollToIndex(3)
        compose.onNodeWithText("16:9").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("aspectratio" to "16:9", request)
        }
        compose.onNodeWithTag("advanced-setting-zoomspeed")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-zoomspeed").performScrollToIndex(11)
        compose.onNodeWithText("12").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("zoomspeed" to "12", request)
        }
        compose.onNodeWithText(resourceText(R.string.setting_drive_mode))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.setting_metering_mode))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.camera_value_super_high_speed_continuous))
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            assertEquals("drivemode" to "Super high speed continuous shooting", request)
        }
    }

    @Test
    fun offlinePreviewExposesCanonManualFocusDriveControls() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var requestedFocusDrive: Pair<FocusDriveDirection, FocusDriveStep>? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            driveFocus = { direction, step -> requestedFocusDrive = direction to step },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    CameraUiState().withOfflinePreview().copy(activeSettingPicker = picker.value),
                    actions,
                )
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithText(resourceText(R.string.manual_focus_drive)).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            resourceText(R.string.focus_drive_step, resourceText(R.string.focus_farther), 3),
        ).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals(FocusDriveDirection.FAR to FocusDriveStep.LARGE, requestedFocusDrive)
        }
    }

    @Test
    fun offlinePreviewIncludesMediaBrowserWithoutEnablingFakeDownloads() {
        val state = CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, noOpActions())
            }
        }

        compose.onNodeWithText(resourceText(R.string.camera_media)).assertIsDisplayed()
        compose.onNodeWithText("R6M3_0001.CR3").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.download_media, "R6M3_0001.CR3"))
            .assertIsNotEnabled()
    }

    @Test
    fun mediaDownloadShowsProgressAndCancelAction() {
        val name = "R6M3_0002.MP4"
        val state = CameraUiState().withOfflinePreview().copy(
            uiMode = UiMode.MEDIA,
            activeMediaDownloadName = name,
            mediaDownloadProgress = CameraMediaTransferProgress(
                bytesTransferred = 32L * 1024L * 1024L,
                totalBytes = 128L * 1024L * 1024L,
            ),
            pendingOperations = setOf(CameraOperation.MEDIA),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, noOpActions())
            }
        }

        compose.onNodeWithText(resourceText(R.string.downloading_media, name)).assertIsDisplayed()
        compose.onNodeWithText("32.0 MB / 128.0 MB (25%)").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.cancel_media_download)).assertIsDisplayed()
    }

    @Test
    fun photoStateExplainsMissingStillCaptureCapability() {
        compose.setContent { MaterialTheme { CameraControlScreen(connectedState(), noOpActions()) } }
        compose.onNodeWithContentDescription(resourceText(R.string.switch_to_video)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.capture_not_supported)).assertIsDisplayed()
    }

    @Test
    fun debugStateShowsDiagnosticSections() {
        compose.setContent { MaterialTheme { DebugScreen(connectedState().copy(uiMode = UiMode.DEBUG), noOpActions()) } }
        compose.onNodeWithText(resourceText(R.string.overview)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.capability_evidence)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("GET /ccapi").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("POST /ccapi/ver100/shooting/control/shutterbutton")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("CCAPI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USB / PTP").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun exposureDialStaysOpenForContinuousAdjustments() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var selectedIso: String? = null
        var selectedShutter: String? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setIso = { selectedIso = it },
            setShutter = { selectedShutter = it },
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(activeSettingPicker = picker.value),
                    actions,
                )
            }
        }

        compose.onNodeWithText("ISO").performClick()
        compose.onNodeWithText("1600").performClick()
        compose.runOnIdle { assertEquals("1600", selectedIso) }
        compose.onNodeWithTag("exposure-picker-SHUTTER").performClick()
        compose.onNodeWithText("1/60").performClick()
        compose.runOnIdle { assertEquals("1/60", selectedShutter) }
        compose.onNodeWithTag("exposure-picker-ISO").assertIsDisplayed()
    }

    @Test
    fun cleanViewHidesHudButKeepsRestoreControl() {
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(connectedState().copy(hudVisible = false), noOpActions())
            }
        }

        compose.onAllNodesWithText("Canon EOS R6 Mark III").assertCountEquals(0)
        compose.onAllNodesWithText("ISO").assertCountEquals(0)
        compose.onNodeWithContentDescription(resourceText(R.string.show_hud)).assertIsDisplayed()
    }

    @Test
    fun gridOverlayIsExposedWhenEnabled() {
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(connectedState().copy(showGrid = true), noOpActions())
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.composition_grid)).assertIsDisplayed()
    }

    @Test
    fun frameRateControlIsVisibleAndChangesThePollingRate() {
        val picker = mutableStateOf<SettingPicker?>(null)
        val fps = mutableIntStateOf(6)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setFps = { fps.intValue = it },
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        activeSettingPicker = picker.value,
                        liveViewFrameRateFps = fps.intValue,
                    ),
                    actions,
                )
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.fps_control_description, 6)).performClick()
        compose.onNodeWithContentDescription(resourceText(R.string.increase_fps)).performClick()
        compose.runOnIdle { assertEquals(7, fps.intValue) }
        compose.onAllNodesWithText(resourceText(R.string.fps_value, 7)).assertCountEquals(2)
    }

    @Test
    fun languageSheetOffersAutomaticEnglishAndTraditionalChinese() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                LanguageSettingsSheet(
                    CameraUiState(activeSettingPicker = SettingPicker.LANGUAGE),
                    noOpActions(),
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.language_system)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.language_english)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.language_traditional_chinese)).assertIsDisplayed()
    }

    private fun connectedState() = CameraUiState(
        info = CameraInfo(true, "Canon EOS R6 Mark III", "test", "ccapi"),
        status = CameraStatus(
            connected = true,
            batteryLevel = 82,
            batteryStatus = "normal",
            recording = false,
            mode = "photo",
            mediaAvailable = true,
            remainingMinutes = 120,
            exposure = ExposureState("800", "1/50", "2.8", "auto"),
        ),
        capabilities = CameraCapabilities(
            iso = listOf("100", "200", "400", "800", "1600", "3200"),
            shutter = listOf("1/30", "1/50", "1/60"),
            aperture = listOf("2.8", "4.0", "5.6"),
            whiteBalance = listOf("auto", "daylight", "cloudy"),
            liveView = LiveViewCapabilities.ccapiNetwork(),
            evidence = CameraCapabilityEvidence(
                source = "GET /ccapi",
                protocolVersions = listOf("ver100"),
                advertisedCommands = listOf("POST /ccapi/ver100/shooting/control/shutterbutton"),
                writableSettings = listOf("iso", "tv", "av", "wb"),
            ),
        ),
    )

    private fun noOpActions() = CameraActions(
        setConnectionTarget = {}, setBaseUrl = {}, setUsername = {}, setPassword = {},
        setBridgeBaseUrl = {}, setBridgeToken = {}, scanDesktopBridge = {}, selectBridgeCamera = {},
        useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {}, enterOfflinePreview = {},
        connect = {}, connectBridge = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
        connectUsb = { _, _, _ -> },
        setUiMode = {}, setCaptureMode = {}, setHudVisible = {}, setGridVisible = {}, openPicker = {}, closePicker = {},
        setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {}, setCameraSetting = { _, _ -> },
        captureStill = {}, focusWithShutter = {}, driveFocus = { _, _ -> }, toggleRecording = {}, tapFocus = { _, _ -> },
        refreshMedia = {}, downloadMedia = { _, _ -> }, cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, setAppLanguage = {}, clearError = {},
    )

    private fun resourceText(@StringRes resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)
}

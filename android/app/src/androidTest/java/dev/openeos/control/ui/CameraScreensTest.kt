package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import dev.openeos.control.R
import dev.openeos.control.data.CameraCapabilityEvidence
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewCapabilities
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.UsbCameraDevice
import dev.openeos.control.data.UsbCameraInterface
import dev.openeos.control.data.UsbPtpDiagnostics
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        compose.onNodeWithText("R6 Mark III").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
        compose.onNodeWithText(
            compose.activity.resources.getQuantityString(R.plurals.storage_shots_remaining, 2_418, 2_418L)
        ).assertIsDisplayed()
    }

    @Test
    fun offlinePreviewTapActionDoesNotCoverEmptyState() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
            }
        }

        val tapAction = compose
            .onNodeWithContentDescription(resourceText(R.string.tap_action_focus))
            .assertIsDisplayed()
        val tapActionBounds = tapAction
            .fetchSemanticsNode()
            .boundsInRoot
        val emptyStateBounds = compose
            .onNodeWithText(resourceText(R.string.offline_preview))
            .fetchSemanticsNode()
            .boundsInRoot
        val settingsBounds = compose
            .onNodeWithContentDescription(resourceText(R.string.more_settings))
            .fetchSemanticsNode()
            .boundsInRoot

        assertFalse(
            "Tap action $tapActionBounds overlaps empty state $emptyStateBounds",
            tapActionBounds.overlaps(emptyStateBounds),
        )
        assertFalse(
            "Tap action $tapActionBounds overlaps settings $settingsBounds",
            tapActionBounds.overlaps(settingsBounds),
        )
    }

    @Test
    fun portraitPhoneSizeKeepsPrimaryCameraControlsVisible() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                }
            }
        }

        assertPrimaryCameraControlsVisible()
    }

    @Test
    fun landscapePhoneSizeKeepsTheSameCameraControlLayout() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 360.dp)),
            ) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                }
            }
        }

        assertPrimaryCameraControlsVisible()
        val exposureCenters = listOf(
            "exposure-control-ISO",
            "exposure-control-SHUTTER",
            "exposure-control-APERTURE",
            "exposure-control-WHITE_BALANCE",
        ).map { tag -> compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.center.y }
        assertTrue(
            "Exposure controls must remain in one stable row instead of switching to a landscape grid",
            exposureCenters.max() - exposureCenters.min() < 1f,
        )
        val previewHintBounds = compose
            .onNodeWithText(resourceText(R.string.offline_preview_hint))
            .fetchSemanticsNode()
            .boundsInRoot
        val exposureBounds = compose
            .onNodeWithTag("exposure-control-ISO")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Landscape preview content must stay above the fixed exposure controls",
            previewHintBounds.bottom <= exposureBounds.top,
        )
    }

    @Test
    fun layoutAwareRotationKeepsLongCameraTextWithinViewport() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                CompositionLocalProvider(
                    LocalCameraControlRotation provides -90f,
                    LocalCameraControlTargetRotation provides -90f,
                ) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                    }
                }
            }
        }

        val previewHintBounds = compose
            .onNodeWithText(resourceText(R.string.offline_preview_hint))
            .fetchSemanticsNode()
            .boundsInRoot
        val previewBounds = compose
            .onNodeWithTag("live-view-frame")
            .fetchSemanticsNode()
            .boundsInRoot
        val offlineContentBounds = compose
            .onNodeWithTag("offline-preview-content", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val exposureBounds = compose
            .onNodeWithTag("exposure-control-ISO")
            .fetchSemanticsNode()
            .boundsInRoot
        val headerBounds = compose
            .onNodeWithTag("camera-overlay-header")
            .fetchSemanticsNode()
            .boundsInRoot
        val cameraNameBounds = compose
            .onNodeWithTag("camera-name", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val storageBounds = compose
            .onNodeWithTag("storage-status", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val isoCellBounds = compose
            .onNodeWithTag("exposure-control-ISO")
            .fetchSemanticsNode()
            .boundsInRoot
        val isoContentBounds = compose
            .onNodeWithTag("exposure-content-ISO", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot

        assertTrue(
            "Long preview guidance should rotate with the camera controls: $previewHintBounds",
            previewHintBounds.height > previewHintBounds.width,
        )
        assertTrue(
            "Rotated offline content $offlineContentBounds must stay inside live view $previewBounds",
            offlineContentBounds.left >= previewBounds.left &&
                offlineContentBounds.top >= previewBounds.top &&
                offlineContentBounds.right <= previewBounds.right &&
                offlineContentBounds.bottom <= previewBounds.bottom,
        )
        assertTrue(
            "Rotated offline content must stay above exposure controls",
            offlineContentBounds.bottom <= exposureBounds.top,
        )
        listOf(cameraNameBounds, storageBounds).forEach { statusBounds ->
            assertTrue(
                "Rotated status $statusBounds must stay inside header $headerBounds",
                statusBounds.left >= headerBounds.left &&
                    statusBounds.top >= headerBounds.top &&
                    statusBounds.right <= headerBounds.right &&
                    statusBounds.bottom <= headerBounds.bottom,
            )
        }
        assertTrue(
            "Rotated ISO content $isoContentBounds must stay inside its bounded cell $isoCellBounds",
            isoContentBounds.left >= isoCellBounds.left &&
                isoContentBounds.top >= isoCellBounds.top &&
                isoContentBounds.right <= isoCellBounds.right &&
                isoContentBounds.bottom <= isoCellBounds.bottom,
        )
    }

    @Test
    fun enlargedTextKeepsPrimaryCameraControlsVisible() {
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                }
            }
        }

        assertPrimaryCameraControlsVisible()
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
        compose.onNodeWithTag("advanced-setting-shootingmode")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-shootingmode").performScrollToIndex(2)
        compose.onNodeWithText(resourceText(R.string.camera_value_aperture_priority_ae))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals("shootingmode" to "AV", request)
        }
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
    fun offlinePreviewExposesCapabilityGatedShutterHalfPress() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var halfPressRequested = false
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            halfPressShutter = { halfPressRequested = true },
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
        compose.onNodeWithContentDescription(resourceText(R.string.half_press_shutter_action))
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(halfPressRequested) }
    }

    @Test
    fun moreSettingsHidesShutterHalfPressWhenTheCameraDoesNotAdvertiseIt() {
        val picker = mutableStateOf<SettingPicker?>(null)
        val preview = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(preview.capabilities)
        val state = preview.copy(
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported - CameraFeature.SHUTTER_HALF_PRESS,
                ),
            ),
        )
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onAllNodesWithContentDescription(resourceText(R.string.half_press_shutter_action))
            .assertCountEquals(0)
    }

    @Test
    fun offlinePreviewCanSelectClickWhiteBalanceAsTheLiveViewTapAction() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var selectedAction: LiveViewTapAction? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setLiveViewTapAction = { selectedAction = it },
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
        compose.onNodeWithText(resourceText(R.string.live_view_tap_action))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.tap_action_white_balance))
            .performScrollTo()
            .performClick()

        compose.runOnIdle {
            assertEquals(LiveViewTapAction.WHITE_BALANCE, selectedAction)
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
    fun mediaThumbnailLoadsWhenAdvertisedAndExposesAnAccessibleDescription() {
        val item = CameraMediaItem("ptp:00000042", "IMG_0042.JPG", "image")
        val preview = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(preview.capabilities)
        val state = mutableStateOf(
            preview.copy(
                previewMode = false,
                mediaItems = listOf(item),
                capabilities = capabilities.copy(
                    matrix = capabilities.matrix.copy(
                        supported = capabilities.matrix.supported + CameraFeature.MEDIA_THUMBNAIL,
                    ),
                ),
            ),
        )
        var requestedItem: CameraMediaItem? = null
        val actions = noOpActions().copy(loadMediaThumbnail = { requestedItem = it })
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state.value, actions)
            }
        }

        compose.runOnIdle {
            assertEquals(item, requestedItem)
            state.value = state.value.copy(
                mediaThumbnails = mapOf(
                    item.id to Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888),
                ),
            )
        }
        compose.onNodeWithContentDescription(resourceText(R.string.media_thumbnail, item.name))
            .assertIsDisplayed()
    }

    @Test
    fun mediaDeleteRequiresConfirmationBeforeDispatchingCameraAction() {
        var deletedName: String? = null
        val state = CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA)
        val actions = noOpActions().copy(deleteMedia = { deletedName = it.name })
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.delete_media, "R6M3_0001.CR3"))
            .performClick()
        compose.runOnIdle { assertEquals(null, deletedName) }
        compose.onNodeWithText(
            resourceText(R.string.delete_media_confirmation, "R6M3_0001.CR3"),
        ).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals("R6M3_0001.CR3", deletedName) }
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
    fun liveViewSettingsExposeOnlyAdvertisedStreamSources() {
        var selectedSource: LiveViewSource? = null
        val capabilities = connectedState().capabilities!!.let { current ->
            current.copy(
                liveView = current.liveView.copy(
                    sources = listOf(LiveViewSource.CCAPI_RTP, LiveViewSource.CCAPI_JPEG_POLLING),
                    defaultSource = LiveViewSource.CCAPI_RTP,
                )
            )
        }
        val actions = noOpActions().copy(setLiveViewSource = { selectedSource = it })
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        capabilities = capabilities,
                        activeSettingPicker = SettingPicker.LIVE_VIEW,
                        liveViewSource = LiveViewSource.CCAPI_RTP,
                    ),
                    actions,
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.live_view_source_rtp)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.live_view_source_jpeg)).performClick()
        compose.runOnIdle { assertEquals(LiveViewSource.CCAPI_JPEG_POLLING, selectedSource) }
        compose.onAllNodesWithText(resourceText(R.string.size)).assertCountEquals(0)
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

    private fun assertPrimaryCameraControlsVisible() {
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.fps_control_description, 6))
            .assertIsDisplayed()
        compose.onNodeWithTag("exposure-control-ISO").assertIsDisplayed()
        compose.onNodeWithTag("exposure-control-SHUTTER").assertIsDisplayed()
        compose.onNodeWithTag("exposure-control-APERTURE").assertIsDisplayed()
        compose.onNodeWithTag("exposure-control-WHITE_BALANCE").assertIsDisplayed()
    }

    private fun noOpActions() = CameraActions(
        setConnectionTarget = {}, setBaseUrl = {}, setUsername = {}, setPassword = {},
        setBridgeBaseUrl = {}, setBridgeToken = {}, scanDesktopBridge = {}, selectBridgeCamera = {},
        useHttpPreset = {}, useHttpsPreset = {}, useSimulatorPreset = {}, enterOfflinePreview = {},
        connect = {}, connectBridge = {}, disconnect = {}, refresh = {}, refreshUsb = {}, requestUsbPermission = {},
        connectUsb = { _, _, _ -> },
        setUiMode = {}, setCaptureMode = {}, setHudVisible = {}, setGridVisible = {}, setLiveViewTapAction = {},
        openPicker = {}, closePicker = {},
        setIso = {}, setShutter = {}, setAperture = {}, setWhiteBalance = {}, setCameraSetting = { _, _ -> },
        captureStill = {}, autofocus = {}, driveFocus = { _, _ -> }, toggleRecording = {}, tapFocus = { _, _ -> },
        halfPressShutter = {},
        clickWhiteBalance = { _, _ -> },
        refreshMedia = {}, loadMediaThumbnail = {}, downloadMedia = { _, _ -> }, deleteMedia = {},
        cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
    )

    private fun resourceText(@StringRes resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)
}

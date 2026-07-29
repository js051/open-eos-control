package dev.openeos.control.ui

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
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
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewCapabilities
import dev.openeos.control.data.LiveViewMagnification
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
        compose.onNodeWithText("R6 III").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
        compose.onNodeWithContentDescription(
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
        val magnificationBounds = compose
            .onNodeWithTag("live-view-magnification")
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
        assertFalse(
            "Magnification $magnificationBounds overlaps empty state $emptyStateBounds",
            magnificationBounds.overlaps(emptyStateBounds),
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
    fun quarterTurnRemeasuresLongCopyAsWideLandscapeContent() {
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
        val previewTitleBounds = compose
            .onNodeWithText(resourceText(R.string.offline_preview))
            .fetchSemanticsNode()
            .boundsInRoot
        val previewIconBounds = compose
            .onNodeWithTag("offline-preview-icon", useUnmergedTree = true)
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
        assertTrue(
            "Preview title should rotate with the camera controls: $previewTitleBounds",
            previewTitleBounds.height > previewTitleBounds.width,
        )
        assertTrue(
            "Wide landscape guidance should remain readable after rotation: $previewHintBounds",
            previewHintBounds.height > previewHintBounds.width,
        )
        assertTrue(
            "Sideways guidance should use a wide icon-and-copy layout before rotation",
            kotlin.math.abs(previewIconBounds.center.y - previewTitleBounds.center.y) > 24f,
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
        val stableSlots = listOf(
            "camera-model-status" to "camera-name",
            "exposure-control-ISO" to "exposure-content-ISO",
            "exposure-control-SHUTTER" to "exposure-content-SHUTTER",
            "exposure-control-APERTURE" to "exposure-content-APERTURE",
            "exposure-control-WHITE_BALANCE" to "exposure-content-WHITE_BALANCE",
            "battery-status" to "battery-status-content",
            "storage-status" to "storage-status-content",
            "fps-control" to "fps-content",
        )
        stableSlots.forEach { (slotTag, contentTag) ->
            val slotBounds = compose.onNodeWithTag(slotTag).fetchSemanticsNode().boundsInRoot
            val contentBounds = compose
                .onNodeWithTag(contentTag, useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                "Rotated content $contentTag $contentBounds must stay inside $slotTag $slotBounds",
                contentBounds.left >= slotBounds.left &&
                    contentBounds.top >= slotBounds.top &&
                    contentBounds.right <= slotBounds.right &&
                    contentBounds.bottom <= slotBounds.bottom,
            )
        }
    }

    @Test
    fun quarterTurnKeepsLargeLocalizedCopyInsideTheLiveViewViewport() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
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
        }

        val viewport = compose
            .onNodeWithTag("offline-preview-viewport", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val content = compose
            .onNodeWithTag("offline-preview-content", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val hint = compose
            .onNodeWithText(resourceText(R.string.offline_preview_hint))
            .fetchSemanticsNode()
            .boundsInRoot
        listOf(content, hint).forEach { bounds ->
            assertTrue(
                "Rotated large text $bounds must stay inside the orientation-aware viewport $viewport",
                bounds.left >= viewport.left &&
                    bounds.top >= viewport.top &&
                    bounds.right <= viewport.right &&
                    bounds.bottom <= viewport.bottom,
            )
        }
    }

    @Test
    fun quarterTurnRemeasuresLiveViewSettingsInsideAFullLengthSidePanel() {
        val picker = mutableStateOf<SettingPicker?>(SettingPicker.LIVE_VIEW)
        val actions = noOpActions().copy(closePicker = { picker.value = null })
        compose.setContent {
            CompositionLocalProvider(
                LocalCameraControlRotation provides -90f,
                LocalCameraControlTargetRotation provides -90f,
            ) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(
                        connectedState().copy(activeSettingPicker = picker.value),
                        actions,
                    )
                }
            }
        }

        val viewportBounds = compose
            .onNodeWithTag("settings-content-viewport", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val panelBounds = compose
            .onNodeWithTag("camera-settings-panel")
            .fetchSemanticsNode()
            .boundsInRoot
        val titleBounds = compose
            .onNodeWithText(resourceText(R.string.live_view_settings))
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Quarter-turn settings title should be upright when the device is held sideways: $titleBounds",
            titleBounds.height > titleBounds.width,
        )
        assertTrue(
            "Sideways settings should retain a measurable constrained viewport: $viewportBounds",
            viewportBounds.width > 0f && viewportBounds.height > 0f,
        )
        assertTrue(
            "Quarter-turn settings should use a tall side panel in the fixed camera layout: $panelBounds",
            panelBounds.height > panelBounds.width,
        )
        compose.onNodeWithText(resourceText(R.string.auto_refresh)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.composition_grid)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.dismiss)).performClick()
        compose.runOnIdle { assertEquals(null, picker.value) }
        compose.onAllNodesWithText(resourceText(R.string.live_view_settings)).assertCountEquals(0)
    }

    @Test
    fun upsideDownSettingsMoveToThePhysicalBottomEdge() {
        compose.setContent {
            CompositionLocalProvider(
                LocalCameraControlRotation provides 180f,
                LocalCameraControlTargetRotation provides 180f,
            ) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(
                        connectedState().copy(activeSettingPicker = SettingPicker.LIVE_VIEW),
                        noOpActions(),
                    )
                }
            }
        }

        val root = compose.onNodeWithTag("camera-settings-root").fetchSemanticsNode().boundsInRoot
        val panel = compose.onNodeWithTag("camera-settings-panel").fetchSemanticsNode().boundsInRoot
        val spaceAbove = panel.top - root.top
        val spaceBelow = root.bottom - panel.bottom
        assertTrue(
            "Upside-down settings must stay closer to the safe top edge than the bottom: $panel, $root",
            spaceAbove < spaceBelow,
        )
        compose.onNodeWithTag("settings-content-rotation").fetchSemanticsNode()
        compose.onNodeWithText(resourceText(R.string.live_view_settings)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.auto_refresh)).assertIsDisplayed()
    }

    @Test
    fun openSettingsRemainVisibleWhenSystemRotationLockChanges() {
        val controlRotation = mutableFloatStateOf(-90f)
        compose.setContent {
            CompositionLocalProvider(
                LocalCameraControlRotation provides controlRotation.floatValue,
                LocalCameraControlTargetRotation provides controlRotation.floatValue,
            ) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    CameraControlScreen(
                        connectedState().copy(activeSettingPicker = SettingPicker.LIVE_VIEW),
                        noOpActions(),
                    )
                }
            }
        }

        val sidewaysTitle = compose
            .onNodeWithText(resourceText(R.string.live_view_settings))
            .fetchSemanticsNode()
            .boundsInRoot
        val sidewaysPanel = compose
            .onNodeWithTag("camera-settings-panel")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(sidewaysTitle.height > sidewaysTitle.width)

        compose.runOnIdle { controlRotation.floatValue = 0f }
        compose.onNodeWithText(resourceText(R.string.live_view_settings)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.auto_refresh)).assertIsDisplayed()
        val naturalTitle = compose
            .onNodeWithText(resourceText(R.string.live_view_settings))
            .fetchSemanticsNode()
            .boundsInRoot
        val naturalPanel = compose
            .onNodeWithTag("camera-settings-panel")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(naturalTitle.width > naturalTitle.height)
        assertTrue(sidewaysPanel.height > sidewaysPanel.width)
        assertTrue(naturalPanel.width > sidewaysPanel.width)
        assertTrue(naturalPanel.height < sidewaysPanel.height)
        assertTrue(sidewaysPanel != naturalPanel)

        compose.runOnIdle { controlRotation.floatValue = -90f }
        compose.onNodeWithText(resourceText(R.string.live_view_settings)).assertIsDisplayed()
        val restoredSidewaysTitle = compose
            .onNodeWithText(resourceText(R.string.live_view_settings))
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(restoredSidewaysTitle.height > restoredSidewaysTitle.width)
        assertEquals(
            sidewaysPanel,
            compose.onNodeWithTag("camera-settings-panel").fetchSemanticsNode().boundsInRoot,
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
        compose.onNodeWithTag("advanced-setting-capturetarget")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.camera_value_phone))
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals("capturetarget" to "Phone", request)
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
        val whiteBalanceLabel = compose.onNodeWithText(
            resourceText(R.string.tap_action_white_balance),
            useUnmergedTree = true,
        )
        whiteBalanceLabel
            .performScrollTo()
            .assertIsDisplayed()
        val buttonBounds = compose
            .onNodeWithTag("tap-action-white-balance")
            .fetchSemanticsNode()
            .boundsInRoot
        val labelBounds = whiteBalanceLabel.fetchSemanticsNode().boundsInRoot
        assertTrue(
            "The longest tap-action label must remain inside its button: $labelBounds, $buttonBounds",
            labelBounds.left >= buttonBounds.left &&
                labelBounds.top >= buttonBounds.top &&
                labelBounds.right <= buttonBounds.right &&
                labelBounds.bottom <= buttonBounds.bottom,
        )
        whiteBalanceLabel
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
    fun mediaPreviewOpensFromThumbnailOnlyWhenAdvertised() {
        val item = CameraMediaItem("ccapi:image", "IMG_0042.JPG", "image", previewAvailable = true)
        val preview = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(preview.capabilities)
        val state = mutableStateOf(
            preview.copy(
                previewMode = false,
                mediaItems = listOf(item),
                capabilities = capabilities.copy(
                    matrix = capabilities.matrix.copy(
                        supported = capabilities.matrix.supported + CameraFeature.MEDIA_PREVIEW,
                    ),
                ),
            ),
        )
        var openedItem: CameraMediaItem? = null
        val actions = noOpActions().copy(
            openMediaPreview = {
                openedItem = it
                state.value = state.value.copy(mediaPreviewItem = it, mediaPreviewLoading = true)
            },
            closeMediaPreview = {
                state.value = state.value.copy(mediaPreviewItem = null, mediaPreviewLoading = false)
            },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state.value, actions) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.preview_media, item.name)).performClick()
        compose.runOnIdle { assertEquals(item, openedItem) }
        compose.onNodeWithContentDescription(resourceText(R.string.close_media_preview)).assertIsDisplayed()
            .performClick()
        compose.onNodeWithContentDescription(resourceText(R.string.close_media_preview)).assertDoesNotExist()
    }

    @Test
    fun mediaPreviewButtonIsHiddenWhenTheItemHasNoDecodablePreview() {
        val item = CameraMediaItem("ptp:raw", "IMG_0042.CR3", "raw", previewAvailable = false)
        val preview = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(preview.capabilities)
        val state = preview.copy(
            previewMode = false,
            mediaItems = listOf(item),
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.MEDIA_PREVIEW,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, noOpActions()) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.preview_media, item.name)).assertDoesNotExist()
        compose.onNodeWithText(item.name).assertIsDisplayed()
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
    fun photoStateShowsModeSelectorAndExplainsMissingStillCaptureCapability() {
        var selectedMode: CaptureMode? = null
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState(),
                    noOpActions().copy(setCaptureMode = { selectedMode = it }),
                )
            }
        }
        compose.onNodeWithTag("capture-mode-PHOTO").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(CaptureMode.VIDEO, selectedMode) }
        compose.onNodeWithText(resourceText(R.string.capture_not_supported)).assertIsDisplayed()
    }

    @Test
    fun recordingDisablesCaptureModeSelector() {
        val base = connectedState()
        val state = base.copy(
            captureMode = CaptureMode.VIDEO,
            status = base.status?.copy(recording = true),
        )
        compose.setContent { MaterialTheme { CameraControlScreen(state, noOpActions()) } }

        compose.onNodeWithTag("capture-mode-PHOTO").assertIsNotEnabled()
        compose.onNodeWithTag("capture-mode-VIDEO").assertIsSelected().assertIsNotEnabled()
    }

    @Test
    fun bulbModeUsesTheCentralShutterForStartAndStop() {
        var toggleCount = 0
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities).copy(
            advancedSettings = listOf(
                CameraSettingControl("shootingmode", "Shooting mode", "Bulb", listOf("Manual", "Bulb")),
            ),
            matrix = CapabilityMatrix(supported = setOf(CameraFeature.BULB_EXPOSURE)),
        )
        val state = mutableStateOf(base.copy(capabilities = capabilities))
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    state.value,
                    noOpActions().copy(toggleBulbExposure = { toggleCount += 1 }),
                )
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.start_bulb_exposure)).performClick()
        compose.runOnIdle {
            assertEquals(1, toggleCount)
            state.value = state.value.copy(
                status = state.value.status?.copy(bulbExposureActive = true),
                bulbStartedAtMillis = android.os.SystemClock.elapsedRealtime(),
            )
        }
        compose.onNodeWithContentDescription(resourceText(R.string.stop_bulb_exposure)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.bulb_exposure_time, "00:00")).assertIsDisplayed()
        compose.onNodeWithTag("exposure-control-ISO").assertIsNotEnabled()
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
        compose.onNodeWithText(resourceText(R.string.validation_coverage)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.unverified_advertised_features))
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
    fun exposureDialLocksDuringWriteAndReturnsToConfirmedValueAfterFailure() {
        val state = mutableStateOf(connectedState().copy(activeSettingPicker = SettingPicker.ISO))
        var selectedIso: String? = null
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    state.value,
                    noOpActions().copy(setIso = { selectedIso = it }),
                )
            }
        }

        compose.onNodeWithTag("exposure-option-3").assertIsSelected()
        compose.onNodeWithTag("exposure-option-4").performClick().assertIsSelected()
        compose.runOnIdle {
            assertEquals("1600", selectedIso)
            state.value = state.value.copy(pendingOperations = setOf(CameraOperation.SETTING))
        }
        compose.onNodeWithTag("exposure-option-4").assertIsSelected().assertIsNotEnabled()

        compose.runOnIdle { state.value = state.value.copy(pendingOperations = emptySet()) }
        compose.waitForIdle()
        compose.onNodeWithTag("exposure-option-3").assertIsSelected()
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
    fun decodedFrameRendersPixelAndGeometricMonitoringOverlays() {
        val width = 160
        val height = 90
        val pixels = IntArray(width * height) { index ->
            val x = index % width
            val level = x * 255 / (width - 1)
            0xff000000.toInt() or (level shl 16) or (level shl 8) or level
        }
        val frame = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        liveViewBitmap = frame,
                        monitorSettings = LiveViewMonitorSettings(
                            histogramVisible = true,
                            zebraThresholdPercent = 90,
                            falseColorEnabled = true,
                            focusPeakingEnabled = true,
                            frameGuide = LiveViewFrameGuide.RATIO_2_39,
                            safeAreaVisible = true,
                            desqueeze = LiveViewDesqueeze.X1_33,
                        ),
                    ),
                    noOpActions(),
                )
            }
        }

        val histogramDescription = resourceText(R.string.histogram)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose
                .onAllNodesWithContentDescription(histogramDescription)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithContentDescription(histogramDescription).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.monitor_guides)).assertIsDisplayed()
    }

    @Test
    fun monitoringAssistsExposeGuidesAndUpdateUserSelections() {
        val picker = mutableStateOf<SettingPicker?>(SettingPicker.LIVE_VIEW)
        var settings = LiveViewMonitorSettings()
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            setHistogramVisible = { settings = settings.copy(histogramVisible = it) },
            setZebraThreshold = { settings = settings.copy(zebraThresholdPercent = it) },
            setFalseColorEnabled = { settings = settings.copy(falseColorEnabled = it) },
            setFrameGuide = { settings = settings.copy(frameGuide = it) },
            setSafeAreaVisible = { settings = settings.copy(safeAreaVisible = it) },
            setDesqueeze = { settings = settings.copy(desqueeze = it) },
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(activeSettingPicker = picker.value),
                    actions,
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.monitoring_assists)).performClick()
        compose.onNodeWithText(resourceText(R.string.histogram)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-zebra-options").performScrollTo().performScrollToIndex(6)
        compose.onNodeWithText(resourceText(R.string.zebra_threshold, 95)).performClick()
        compose.onNodeWithText(resourceText(R.string.false_color)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-frame-guide-options").performScrollTo().performScrollToIndex(2)
        compose.onNodeWithText(resourceText(R.string.ratio_2_39)).performScrollTo().performClick()
        compose.onNodeWithText(resourceText(R.string.safe_area)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-desqueeze-options").performScrollTo().performScrollToIndex(4)
        compose.onNodeWithText(resourceText(R.string.desqueeze_value, 2f)).performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(settings.histogramVisible)
            assertEquals(95, settings.zebraThresholdPercent)
            assertTrue(settings.falseColorEnabled)
            assertEquals(LiveViewFrameGuide.RATIO_2_39, settings.frameGuide)
            assertTrue(settings.safeAreaVisible)
            assertEquals(LiveViewDesqueeze.X2, settings.desqueeze)
        }
    }

    @Test
    fun nativeRtpDisablesPixelAnalysisButKeepsGeometricAssists() {
        val picker = mutableStateOf<SettingPicker?>(SettingPicker.LIVE_VIEW)
        val actions = noOpActions().copy(openPicker = { picker.value = it })
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        activeSettingPicker = picker.value,
                        liveViewSource = LiveViewSource.CCAPI_RTP,
                    ),
                    actions,
                )
            }
        }

        compose.onNodeWithText(resourceText(R.string.monitoring_assists)).performClick()
        compose.onNodeWithText(resourceText(R.string.monitoring_assists_rtp_unavailable)).assertExists()
        compose.onNodeWithText(resourceText(R.string.histogram)).assertIsNotEnabled()
        compose.onNodeWithText(resourceText(R.string.frame_guide)).assertExists()
        compose.onNodeWithText(resourceText(R.string.anamorphic_desqueeze)).assertExists()
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
    fun liveViewMagnificationControlAppearsOnlyWhenAdvertisedAndSendsFiveTimes() {
        var selected: LiveViewMagnification? = null
        val base = connectedState()
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    base.copy(
                        capabilities = base.capabilities?.copy(
                            matrix = CapabilityMatrix(
                                supported = base.capabilities.matrix.supported +
                                    CameraFeature.LIVE_VIEW_MAGNIFICATION,
                            ),
                        ),
                        liveViewMagnification = LiveViewMagnification.X1,
                    ),
                    noOpActions().copy(setLiveViewMagnification = { selected = it }),
                )
            }
        }

        compose.onNodeWithTag("live-view-magnification").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LiveViewMagnification.X5, selected) }
    }

    @Test
    fun liveViewMagnificationControlIsHiddenWhenNotAdvertised() {
        val base = connectedState()
        compose.setContent {
            MaterialTheme { CameraControlScreen(base, noOpActions()) }
        }
        compose.onAllNodesWithTag("live-view-magnification").assertCountEquals(0)
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
        compose.onNodeWithTag("capture-mode-selector").assertIsDisplayed()
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
        captureStill = {}, autofocus = {}, driveFocus = { _, _ -> }, setLiveViewMagnification = {},
        toggleRecording = {}, tapFocus = { _, _ -> },
        halfPressShutter = {},
        clickWhiteBalance = { _, _ -> },
        refreshMedia = {}, loadMediaThumbnail = {}, openMediaPreview = {}, closeMediaPreview = {},
        downloadMedia = { _, _ -> }, deleteMedia = {},
        cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
    )

    private fun resourceText(@StringRes resource: Int, vararg arguments: Any): String =
        compose.activity.getString(resource, *arguments)
}

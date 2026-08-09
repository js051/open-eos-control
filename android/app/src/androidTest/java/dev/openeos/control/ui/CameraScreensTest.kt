package dev.openeos.control.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.material3.MaterialTheme
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.WindowInsets
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.annotation.StringRes
import androidx.core.graphics.Insets
import androidx.core.view.WindowInsetsCompat
import dev.openeos.control.R
import dev.openeos.control.data.CameraCapabilityEvidence
import dev.openeos.control.data.CameraCapabilities
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraFileNaming
import dev.openeos.control.data.CameraFileNamingField
import dev.openeos.control.data.CameraInfo
import dev.openeos.control.data.CameraIntegerRange
import dev.openeos.control.data.CameraMediaItem
import dev.openeos.control.data.CameraMediaTransferProgress
import dev.openeos.control.data.CameraStatus
import dev.openeos.control.data.CameraTemperatureStatus
import dev.openeos.control.data.CameraSettingControl
import dev.openeos.control.data.CameraSettingInputKind
import dev.openeos.control.data.CapabilityMatrix
import dev.openeos.control.data.DesktopBridgeCamera
import dev.openeos.control.data.ExposureState
import dev.openeos.control.data.FocusDriveDirection
import dev.openeos.control.data.FocusDriveStep
import dev.openeos.control.data.LiveViewCapabilities
import dev.openeos.control.data.LiveViewMagnification
import dev.openeos.control.data.LiveViewSource
import dev.openeos.control.data.NativeLiveViewAudioStatus
import dev.openeos.control.data.UsbCameraDevice
import dev.openeos.control.data.UsbCameraInterface
import dev.openeos.control.data.UsbPtpDiagnostics
import androidx.test.services.storage.TestStorage
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
        compose.onNodeWithContentDescription("Canon EOS R6 Mark III").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.capture_photo)).assertIsDisplayed()
        compose.onNodeWithText("800").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            compose.activity.resources.getQuantityString(R.plurals.storage_shots_remaining, 2_418, 2_418L)
        ).assertIsDisplayed()
    }

    @Test
    fun videoPreviewShowsExactRemainingRecordingDuration() {
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    CameraUiState().withOfflinePreview().copy(captureMode = CaptureMode.VIDEO),
                    noOpActions(),
                )
            }
        }

        compose.onNodeWithContentDescription(
            resourceText(R.string.recording_time_remaining, "1:58:00"),
        ).assertIsDisplayed()
        compose.onNodeWithText("1:58:00").assertIsDisplayed()
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
        compose.onNodeWithTag("offline-preview-stacked", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("offline-preview-inline", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test
    fun landscapePhoneSizeKeepsTheSameCameraControlLayout() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.WindowInsets(
                    WindowInsetsCompat.Builder().build(),
                ),
            ) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(800.dp, 360.dp)),
                ) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                    }
                }
            }
        }

        saveVisualSnapshot(
            stem = "camera-control-landscape-en",
            nodeTag = "camera-control-root",
        )
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
    fun safeDrawingInsetsKeepTheFixedCameraControlsInsideTheUsableWindow() {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(
                WindowInsetsCompat.Type.systemBars(),
                Insets.of(0, 24, 0, 48),
            )
            .setInsets(
                WindowInsetsCompat.Type.displayCutout(),
                Insets.of(16, 0, 0, 0),
            )
            .build()
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.WindowInsets(insets)) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
                ) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                    }
                }
            }
        }

        assertPrimaryCameraControlsVisible()
        val root = compose.onNodeWithTag("camera-control-root").fetchSemanticsNode().boundsInRoot
        listOf(
            "camera-overlay-header",
            "exposure-control-ISO",
            "capture-mode-selector",
        ).forEach { tag ->
            val control = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Safe-area control $tag $control must remain inside the camera root $root",
                control.left >= root.left && control.top >= root.top &&
                    control.right <= root.right && control.bottom <= root.bottom,
            )
        }
    }

    @Test
    fun quarterTurnUsesAReadableCameraStateWithoutDroppingCopy() {
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

        compose.onNodeWithText(resourceText(R.string.offline_preview_hint)).assertIsDisplayed()
        compose.onNodeWithTag("offline-preview-inline", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("offline-preview-stacked", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("offline-preview-title-overflow", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("offline-preview-hint-overflow", useUnmergedTree = true).assertCountEquals(0)
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
        val previewViewportBounds = compose
            .onNodeWithTag("offline-preview-viewport", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val offlineContentBounds = compose
            .onNodeWithTag("offline-preview-content", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val previewHintBounds = compose
            .onNodeWithText(resourceText(R.string.offline_preview_hint))
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
            "Sideways camera state should keep its icon and short title separated",
            kotlin.math.abs(previewIconBounds.center.y - previewTitleBounds.center.y) > 24f,
        )
        assertTrue(
            "Rotated offline content $offlineContentBounds must stay inside live view $previewBounds",
            offlineContentBounds.left >= previewBounds.left &&
                offlineContentBounds.top >= previewBounds.top &&
                offlineContentBounds.right <= previewBounds.right &&
                offlineContentBounds.bottom <= previewBounds.bottom,
        )
        val density = compose.activity.resources.displayMetrics.density
        assertTrue(
            "Sideways guidance should use the available Live View long axis instead of a narrow fixed width: $previewViewportBounds",
            previewViewportBounds.height >= 360f * density,
        )
        assertTrue(
            "English sideways guidance should remain a compact two-line reading block: $previewHintBounds",
            previewHintBounds.width <= 54f * density,
        )
        assertTrue(
            "The complete offline hint $previewHintBounds must stay inside its readable group $offlineContentBounds",
            previewHintBounds.left >= offlineContentBounds.left &&
                previewHintBounds.top >= offlineContentBounds.top &&
                previewHintBounds.right <= offlineContentBounds.right &&
                previewHintBounds.bottom <= offlineContentBounds.bottom,
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
    fun quarterTurnActionMenuRemeasuresFullLabelsWithoutMovingTheCameraLayout() {
        var selectedPicker: SettingPicker? = null
        val menuVisible = mutableStateOf(true)
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
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                if (menuVisible.value) {
                                    CameraActionMenuPanel(
                                        state = connectedState(),
                                        actions = noOpActions().copy(openPicker = { selectedPicker = it }),
                                        onDismissRequest = { menuVisible.value = false },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(
                resourceText(R.string.language),
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        listOf(
            R.string.language,
            R.string.debug,
            R.string.disconnect,
        ).forEach { labelResource ->
            val labelBounds = compose
                .onNodeWithText(resourceText(labelResource), useUnmergedTree = true)
                .fetchSemanticsNode()
                .boundsInRoot
            assertTrue(
                "A sideways action label must be rotated for the user's physical viewpoint: $labelBounds",
                labelBounds.height > labelBounds.width,
            )
        }
        listOf(
            "camera-action-language",
            "camera-action-debug",
            "camera-action-disconnect",
        ).forEach { actionTag ->
            compose.onAllNodesWithTag(
                "$actionTag-label-overflow",
                useUnmergedTree = true,
            ).assertCountEquals(0)
        }

        compose.onNodeWithTag("camera-action-language").performClick()
        compose.runOnIdle { assertEquals(SettingPicker.LANGUAGE, selectedPicker) }
        compose.runOnIdle { assertFalse(menuVisible.value) }
        compose.onAllNodesWithText(
            resourceText(R.string.language),
            useUnmergedTree = true,
        ).assertCountEquals(0)
    }

    @Test
    fun quarterTurnKeepsLocalizedCopyInsideTheLiveViewViewport() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
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
        val modelSlot = compose
            .onNodeWithTag("camera-model-status")
            .fetchSemanticsNode()
            .boundsInRoot
        val modelText = compose
            .onNodeWithTag("camera-name", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Rotated compact state $content must stay inside the orientation-aware viewport $viewport",
            content.left >= viewport.left &&
                content.top >= viewport.top &&
                content.right <= viewport.right &&
                content.bottom <= viewport.bottom,
        )
        assertTrue(
            "Localized offline copy $hint must stay inside the readable state $content",
            hint.left >= content.left && hint.top >= content.top &&
                hint.right <= content.right && hint.bottom <= content.bottom,
        )
        compose.onNodeWithText(resourceText(R.string.offline_preview_hint)).assertIsDisplayed()
        compose.onAllNodesWithTag("offline-preview-title-overflow", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("offline-preview-hint-overflow", useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("R6 III").assertIsDisplayed()
        compose.onNodeWithText("82%").assertIsDisplayed()
        compose.onNodeWithText("2,418").assertIsDisplayed()
        compose.onNodeWithContentDescription("Canon EOS R6 Mark III").assertIsDisplayed()
        assertTrue(
            "The complete rotated model name $modelText must stay inside its stable HUD slot $modelSlot",
            modelText.left >= modelSlot.left &&
                modelText.top >= modelSlot.top &&
                modelText.right <= modelSlot.right &&
                modelText.bottom <= modelSlot.bottom,
        )
    }

    @Test
    fun quarterTurnKeepsExactCameraStatusAvailableInAnOrientationAwarePanel() {
        val fullCameraName = "Canon EOS R6 Mark III"
        val fullStorage = compose.activity.resources.getQuantityString(
            R.plurals.storage_shots_remaining,
            2_418,
            2_418L,
        )
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
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

        compose.onNodeWithContentDescription(fullCameraName).performClick()
        compose.onNodeWithText(resourceText(R.string.camera_status)).assertIsDisplayed()
        compose.onNodeWithText(fullCameraName).assertIsDisplayed()
        compose.onNodeWithText(fullStorage).assertIsDisplayed()

        val dialog = compose
            .onNodeWithTag("camera-status-dialog-rotation", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "A side-facing status panel must remain wide from the user's viewpoint: $dialog",
            dialog.height > dialog.width,
        )
        listOf("camera-status-model-detail", "camera-status-battery-detail", "camera-status-storage-detail")
            .forEach { tag ->
                val detail = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                assertTrue(
                    "Full status detail $tag $detail must stay inside the rotated dialog $dialog",
                    detail.left >= dialog.left && detail.top >= dialog.top &&
                        detail.right <= dialog.right && detail.bottom <= dialog.bottom,
                )
            }
        compose.onNodeWithContentDescription(resourceText(R.string.dismiss)).performClick()
        compose.onAllNodesWithTag("camera-status-dialog").assertCountEquals(0)
    }

    @Test
    fun quarterTurnKeepsLongMessageDialogsWideFromTheUsersViewpoint() {
        val title = "Camera request failed"
        val message = "The camera rejected this command. Check the advertised capability and try again."
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                CompositionLocalProvider(
                    LocalCameraControlRotation provides -90f,
                    LocalCameraControlTargetRotation provides -90f,
                ) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        CameraRotatingMessageDialog(title, message, onDismissRequest = {})
                    }
                }
            }
        }

        compose.onNodeWithText(title).assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        val panel = compose
            .onNodeWithTag("camera-message-dialog-rotation", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val copy = compose
            .onNodeWithTag("camera-message-dialog-text", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "A side-facing message panel must remain wide from the user's viewpoint: $panel",
            panel.height > panel.width,
        )
        assertTrue(
            "Long message copy $copy must stay inside its readable panel $panel",
            copy.left >= panel.left && copy.top >= panel.top &&
                copy.right <= panel.right && copy.bottom <= panel.bottom,
        )
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
        val rotatedViewportBounds = compose
            .onNodeWithTag("settings-content-rotation", useUnmergedTree = true)
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
        assertTrue(
            "Quarter-turn settings must use the full fixed panel instead of discarding its long axis: $rotatedViewportBounds, $panelBounds",
            kotlin.math.abs(rotatedViewportBounds.width - panelBounds.width) < 2f &&
                kotlin.math.abs(rotatedViewportBounds.height - panelBounds.height) < 2f,
        )
        compose.onNodeWithText(resourceText(R.string.auto_refresh)).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.composition_grid)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.dismiss)).performClick()
        compose.runOnIdle { assertEquals(null, picker.value) }
        compose.onAllNodesWithText(resourceText(R.string.live_view_settings)).assertCountEquals(0)
    }

    @Test
    fun upsideDownSettingsKeepTheSameBottomAnchoredLayout() {
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
            "Upside-down settings must keep the panel anchored to the layout bottom: $panel, $root",
            spaceBelow < spaceAbove,
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
        assertEquals(sidewaysPanel, naturalPanel)

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
    fun visualSnapshotShowsEnglishPortraitCameraControls() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(
                    DeviceConfigurationOverride.Locales(LocaleList("en")),
                ) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                    }
                }
            }
        }

        saveVisualSnapshot("camera-control-portrait-en")
        compose.onNodeWithText("Offline UI preview").assertIsDisplayed()
    }

    @Test
    fun visualSnapshotShowsReadableTraditionalChineseSideControlsAtLargeText() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.Locales(LocaleList("zh-TW")),
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
            }
        }

        saveVisualSnapshot(
            stem = "camera-control-side-zh-TW-130pct",
            userViewRotationDegrees = -90f,
        )
        val selector = compose
            .onNodeWithTag("capture-mode-selector", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val photoMode = compose
            .onNodeWithTag("capture-mode-PHOTO", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "The fixed mode hit target must keep a physical-edge inset after a quarter turn: " +
                "selector=$selector photo=$photoMode",
            photoMode.top > selector.top && photoMode.bottom < selector.bottom,
        )
        compose.onNodeWithTag("capture-mode-indicator-PHOTO", useUnmergedTree = true)
            .assertIsDisplayed()
        compose.onNodeWithText("離線 UI 預覽").assertIsDisplayed()
    }

    @Test
    fun visualSnapshotShowsReadableTraditionalChineseSideSettingsAtLargeText() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.Locales(LocaleList("zh-TW")),
                    ) {
                        CompositionLocalProvider(
                            LocalCameraControlRotation provides -90f,
                            LocalCameraControlTargetRotation provides -90f,
                        ) {
                            MaterialTheme(colorScheme = OpenEosColorScheme) {
                                CameraControlScreen(
                                    connectedState().copy(activeSettingPicker = SettingPicker.LIVE_VIEW),
                                    noOpActions(),
                                )
                            }
                        }
                    }
                }
            }
        }

        saveVisualSnapshot(
            stem = "live-view-settings-side-zh-TW-130pct",
            userViewRotationDegrees = -90f,
        )
        val settingsRoot = compose
            .onNodeWithTag("camera-settings-root", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val settingsPanel = compose
            .onNodeWithTag("camera-settings-panel", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val settingsTitle = compose
            .onNodeWithText("Live View 設定", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Side settings title must remain inside its visible panel: " +
                "root=$settingsRoot panel=$settingsPanel title=$settingsTitle",
            settingsTitle.left >= settingsPanel.left &&
                settingsTitle.top >= settingsPanel.top &&
                settingsTitle.right <= settingsPanel.right &&
                settingsTitle.bottom <= settingsPanel.bottom &&
                settingsPanel.left >= settingsRoot.left &&
                settingsPanel.top >= settingsRoot.top &&
                settingsPanel.right <= settingsRoot.right &&
                settingsPanel.bottom <= settingsRoot.bottom,
        )
        compose.onNodeWithText("自動更新").assertIsDisplayed()
    }

    @Test
    fun visualSnapshotShowsTraditionalChineseUpsideDownControlsWithoutMovingLayout() {
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.3f)) {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.Locales(LocaleList("zh-TW")),
                    ) {
                        CompositionLocalProvider(
                            LocalCameraControlRotation provides 180f,
                            LocalCameraControlTargetRotation provides 180f,
                        ) {
                            MaterialTheme(colorScheme = OpenEosColorScheme) {
                                CameraControlScreen(CameraUiState().withOfflinePreview(), noOpActions())
                            }
                        }
                    }
                }
            }
        }

        saveVisualSnapshot(
            stem = "camera-control-upside-down-zh-TW-130pct",
            userViewRotationDegrees = 180f,
        )
        compose.onNodeWithText("離線 UI 預覽").assertIsDisplayed()
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
    fun quarterTurnKeepsLongExposureValuesInsideFixedAtomicSlots() {
        val status = requireNotNull(connectedState().status).copy(
            exposure = ExposureState(
                iso = "102400",
                shutter = "1/16000",
                aperture = "F32",
                whiteBalance = "White fluorescent light",
            ),
        )
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
                            CameraControlScreen(connectedState().copy(status = status), noOpActions())
                        }
                    }
                }
            }
        }

        SettingPicker.entries.filter { it in setOf(
            SettingPicker.ISO,
            SettingPicker.SHUTTER,
            SettingPicker.APERTURE,
            SettingPicker.WHITE_BALANCE,
        ) }.forEach { picker ->
            val slot = compose.onNodeWithTag("exposure-control-${picker.name}")
                .fetchSemanticsNode().boundsInRoot
            val value = compose.onNodeWithTag("exposure-value-${picker.name}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            assertTrue(
                "Rotated exposure value $picker must stay inside its fixed slot: $value, $slot",
                value.left >= slot.left && value.top >= slot.top &&
                    value.right <= slot.right && value.bottom <= slot.bottom,
            )
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
        compose.onNodeWithTag("advanced-setting-zoom")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-zoom").assertIsDisplayed()
        compose.onNodeWithText("50%").assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-zoomspeed")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-zoomspeed").performScrollToIndex(11)
        compose.onNodeWithText("12").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("zoomspeed" to "12", request)
        }
        compose.onNodeWithTag("advanced-setting-alomode")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-value-alomode-High")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle {
            assertEquals("alomode" to "High", request)
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
        compose.onNodeWithTag("advanced-setting-capturestorage")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("SD").assertIsDisplayed().performClick()
        compose.runOnIdle {
            assertEquals("capturestorage" to "SD", request)
        }
    }

    @Test
    fun textMetadataSettingPreservesDirtyDraftAndAppliesRawAsciiValue() {
        val base = connectedState()
        val owner = CameraSettingControl(
            key = "ownername",
            label = "Owner name",
            value = "TEST OWNER",
            values = emptyList(),
            inputKind = CameraSettingInputKind.TEXT,
            maxLength = 255,
        )
        val screenState = mutableStateOf(
            base.copy(
                activeSettingPicker = SettingPicker.MORE,
                capabilities = base.capabilities?.copy(advancedSettings = listOf(owner)),
            ),
        )
        var request: Pair<String, String>? = null
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    screenState.value,
                    noOpActions().copy(setCameraSetting = { key, value -> request = key to value }),
                )
            }
        }

        compose.onNodeWithTag("advanced-setting-ownername").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-text-ownername").performTextReplacement("OPEN EOS")
        compose.runOnIdle {
            screenState.value = screenState.value.copy(
                capabilities = screenState.value.capabilities?.copy(
                    advancedSettings = listOf(owner.copy(value = "CAMERA REFRESH")),
                ),
            )
        }
        compose.onNodeWithTag("advanced-setting-text-apply-ownername")
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle { assertEquals("ownername" to "OPEN EOS", request) }
    }

    @Test
    fun soundRecordingControlsAppearOnlyInVideoSettings() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview())
        val picker = mutableStateOf<SettingPicker?>(null)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.value.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onAllNodesWithTag("advanced-setting-soundrecording").assertCountEquals(0)
        compose.onAllNodesWithTag("advanced-setting-soundrecordinglevel").assertCountEquals(0)
        compose.onAllNodesWithTag("advanced-setting-windfilter").assertCountEquals(0)
        compose.onAllNodesWithTag("advanced-setting-attenuator").assertCountEquals(0)
        compose.runOnIdle {
            picker.value = null
            state.value = state.value.copy(captureMode = CaptureMode.VIDEO)
        }
        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-soundrecording")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-windfilter")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-attenuator")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-soundrecordinglevel")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-soundrecordinglevel").assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.setting_sound_recording_level)).assertIsDisplayed()
        compose.onNodeWithText("32").assertIsDisplayed()
    }

    @Test
    fun deviceFunctionSettingsRemainAvailableInPhotoAndVideoAndSendRawValues() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview())
        val picker = mutableStateOf<SettingPicker?>(null)
        var request: Pair<String, String>? = null
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setCameraSetting = { key, value -> request = key to value },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.value.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-beep").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.setting_beep)).assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-value-beep-disabletouch")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals("beep" to "disabletouch", request) }

        compose.onNodeWithTag("advanced-setting-displayoff").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.setting_display_off)).assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-displayoff").performScrollToIndex(4)
        compose.onNodeWithTag("advanced-setting-value-displayoff-120")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals("displayoff" to "120", request) }

        compose.runOnIdle {
            picker.value = null
            state.value = state.value.copy(captureMode = CaptureMode.VIDEO)
        }
        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-beep").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-displayoff").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun focusBracketingControlsAppearOnlyInPhotoSettingsAndUseRanges() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview())
        val picker = mutableStateOf<SettingPicker?>(null)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.value.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-focusbracketing")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-focusbracketingnumberofshots")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-focusbracketingnumberofshots")
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-focusbracketingfocusincrement")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-values-focusbracketingfocusincrement")
            .assertIsDisplayed()
        compose.onNodeWithTag("advanced-setting-focusbracketingexposuresmoothing")
            .performScrollTo()
            .assertIsDisplayed()

        compose.runOnIdle {
            picker.value = null
            state.value = state.value.copy(captureMode = CaptureMode.VIDEO)
        }
        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        for (key in listOf(
            "focusbracketing",
            "focusbracketingnumberofshots",
            "focusbracketingfocusincrement",
            "focusbracketingexposuresmoothing",
        )) {
            compose.onAllNodesWithTag("advanced-setting-$key").assertCountEquals(0)
        }
    }

    @Test
    fun movieSettingsAppearOnlyInVideoModeWithReadableQualityValues() {
        val state = mutableStateOf(
            CameraUiState().withOfflinePreview().copy(captureMode = CaptureMode.VIDEO),
        )
        val picker = mutableStateOf<SettingPicker?>(null)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.value.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("advanced-setting-moviequality")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("3840x2160 / 59.94p / IPB").assertIsDisplayed()
        for ((key, label) in listOf(
            "highframerate" to resourceText(R.string.setting_high_frame_rate),
            "moviecropping" to resourceText(R.string.setting_movie_cropping),
            "movieformat" to resourceText(R.string.setting_movie_format),
        )) {
            compose.onNodeWithTag("advanced-setting-$key")
                .performScrollTo()
                .assertIsDisplayed()
            compose.onNodeWithText(label).assertIsDisplayed()
        }

        compose.runOnIdle {
            picker.value = null
            state.value = state.value.copy(captureMode = CaptureMode.PHOTO)
        }
        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        for (key in listOf("moviequality", "highframerate", "moviecropping", "movieformat")) {
            compose.onAllNodesWithTag("advanced-setting-$key").assertCountEquals(0)
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
    fun moreSettingsShowsClockSyncOnlyWhenCapabilityIsAdvertised() {
        var syncRequested = false
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        val supportedState = base.copy(
            activeSettingPicker = SettingPicker.MORE,
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.CAMERA_CLOCK_SYNC,
                ),
            ),
        )
        val screenState = mutableStateOf(supportedState)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    screenState.value,
                    noOpActions().copy(syncCameraClock = { syncRequested = true }),
                )
            }
        }

        compose.onNodeWithTag("sync-camera-clock")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(syncRequested) }

        compose.runOnIdle {
            screenState.value = base.copy(activeSettingPicker = SettingPicker.MORE)
        }
        compose.onNodeWithTag("sync-camera-clock").assertDoesNotExist()
    }

    @Test
    fun moreSettingsConfirmsAdvertisedCameraSleepBeforeDispatch() {
        var sleepRequested = false
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        val state = base.copy(
            activeSettingPicker = SettingPicker.MORE,
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.CAMERA_SLEEP,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    state,
                    noOpActions().copy(sleepCamera = { sleepRequested = true }),
                )
            }
        }

        compose.onNodeWithTag("camera-sleep")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertFalse(sleepRequested) }
        compose.onNodeWithTag("camera-sleep-confirm")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertTrue(sleepRequested) }
    }

    @Test
    fun offlinePreviewCameraSleepIsVisibleButDisabled() {
        val state = CameraUiState().withOfflinePreview().copy(activeSettingPicker = SettingPicker.MORE)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state, noOpActions())
            }
        }

        compose.onNodeWithTag("camera-sleep")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun moreSettingsConfirmsAdvertisedSensorCleaningAndPowerOffChoice() {
        var requestedAutoPowerOff: Boolean? = null
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        val state = base.copy(
            activeSettingPicker = SettingPicker.MORE,
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.SENSOR_CLEANING,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    state,
                    noOpActions().copy(cleanSensor = { requestedAutoPowerOff = it }),
                )
            }
        }

        compose.onNodeWithTag("sensor-cleaning")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(null, requestedAutoPowerOff) }
        compose.onNodeWithText(resourceText(R.string.sensor_cleaning_power_off)).performClick()
        compose.onNodeWithTag("sensor-cleaning-confirm")
            .assertIsDisplayed()
            .performClick()
        compose.runOnIdle { assertEquals(true, requestedAutoPowerOff) }
    }

    @Test
    fun offlinePreviewSensorCleaningIsVisibleButDisabled() {
        val state = CameraUiState().withOfflinePreview().copy(activeSettingPicker = SettingPicker.MORE)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state, noOpActions())
            }
        }

        compose.onNodeWithTag("sensor-cleaning")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun moreSettingsCreatesAdvertisedDirectoryWithSanitizedAsciiName() {
        var requestedName: String? = null
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        val state = base.copy(
            activeSettingPicker = SettingPicker.MORE,
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.DIRECTORY_CONTROL,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    state,
                    noOpActions().copy(createDirectory = { requestedName = it }),
                )
            }
        }

        compose.onNodeWithTag("create-directory")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("directory-name").performTextInput("abc12")
        compose.onNodeWithTag("create-directory-confirm").performClick()

        compose.runOnIdle { assertEquals("ABC12", requestedName) }
    }

    @Test
    fun offlinePreviewDirectoryCreationIsVisibleButDisabled() {
        val state = CameraUiState().withOfflinePreview().copy(activeSettingPicker = SettingPicker.MORE)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state, noOpActions())
            }
        }

        compose.onNodeWithTag("create-directory")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
    }

    @Test
    fun photoFileNamingEditorNormalizesAndDispatchesAdvertisedPrefix() {
        var requested: Pair<CameraFileNamingField, String>? = null
        val state = connectedStateWithFileNaming().copy(activeSettingPicker = SettingPicker.MORE)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    state,
                    noOpActions().copy(setFileNaming = { field, value -> requested = field to value }),
                )
            }
        }

        compose.onNodeWithTag("file-naming")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.onNodeWithTag("still-user-setting-1")
            .assertIsDisplayed()
            .performTextReplacement("r6m_")
        compose.onNodeWithTag("still-user-setting-1-apply")
            .assertIsEnabled()
            .performClick()

        compose.runOnIdle {
            assertEquals(CameraFileNamingField.STILL_USER_SETTING_1 to "R6M_", requested)
        }
    }

    @Test
    fun videoFileNamingEditorFiltersFieldsAndRejectsOutOfRangeReel() {
        var requested: Pair<CameraFileNamingField, String>? = null
        val state = connectedStateWithFileNaming().copy(
            activeSettingPicker = SettingPicker.MORE,
            captureMode = CaptureMode.VIDEO,
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(
                    state,
                    noOpActions().copy(setFileNaming = { field, value -> requested = field to value }),
                )
            }
        }

        compose.onNodeWithTag("file-naming").performScrollTo().performClick()
        compose.onNodeWithTag("movie-index").assertIsDisplayed()
        compose.onNodeWithTag("still-user-setting-1").assertDoesNotExist()
        compose.onNodeWithTag("movie-reel-number").performTextReplacement("0")
        compose.onNodeWithTag("movie-reel-number-apply").assertIsNotEnabled()
        compose.onNodeWithTag("movie-reel-number").performTextReplacement("42")
        compose.onNodeWithTag("movie-reel-number-apply").assertIsEnabled().performClick()

        compose.runOnIdle {
            assertEquals(CameraFileNamingField.MOVIE_REEL_NUMBER to "42", requested)
        }
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
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText(resourceText(R.string.live_view_tap_action))
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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
    fun advertisedClickWhiteBalanceEnablesTheConnectedCameraTapAction() {
        val picker = mutableStateOf<SettingPicker?>(null)
        var selectedAction: LiveViewTapAction? = null
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        val state = base.copy(
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.CLICK_WHITE_BALANCE,
                ),
            ),
        )
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
            setLiveViewTapAction = { selectedAction = it },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(state.copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onNodeWithTag("tap-action-white-balance")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()

        compose.runOnIdle {
            assertEquals(LiveViewTapAction.WHITE_BALANCE, selectedAction)
        }
    }

    @Test
    fun connectedCameraDoesNotShowClickWhiteBalanceWithoutCapability() {
        val picker = mutableStateOf<SettingPicker?>(null)
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            closePicker = { picker.value = null },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                CameraControlScreen(connectedState().copy(activeSettingPicker = picker.value), actions)
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.more_settings)).performClick()
        compose.onAllNodesWithTag("tap-action-white-balance").assertCountEquals(0)
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

        compose.onNodeWithContentDescription(resourceText(R.string.media_actions, "R6M3_0001.CR3"))
            .performClick()
        compose.onNodeWithText(resourceText(R.string.delete_media, "R6M3_0001.CR3"))
            .performClick()
        compose.runOnIdle { assertEquals(null, deletedName) }
        compose.onNodeWithText(
            resourceText(R.string.delete_media_confirmation, "R6M3_0001.CR3"),
        ).assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.delete)).performClick()
        compose.runOnIdle { assertEquals("R6M3_0001.CR3", deletedName) }
    }

    @Test
    fun mediaMetadataSheetLoadsAndDispatchesOnlyAdvertisedActions() {
        val item = CameraMediaItem(
            id = "ccapi:image",
            name = "IMG_0042.JPG",
            kind = "image",
            protected = false,
            archived = false,
            rating = 2,
            rotationDegrees = 0,
        )
        val preview = CameraUiState().withOfflinePreview()
        val state = preview.copy(previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item))
        var loaded: CameraMediaItem? = null
        var protection: Boolean? = null
        var archived: Boolean? = null
        var rating: Int? = null
        var rotation: Int? = null
        val actions = noOpActions().copy(
            loadMediaInfo = { loaded = it },
            setMediaProtection = { _, value -> protection = value },
            setMediaArchived = { _, value -> archived = value },
            setMediaRating = { _, value -> rating = value },
            setMediaRotation = { _, value -> rotation = value },
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, actions) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.media_actions, item.name)).performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(item, loaded) }

        compose.onNodeWithContentDescription(resourceText(R.string.protect_media, item.name)).performClick()
        compose.onNodeWithContentDescription(resourceText(R.string.archive_media, item.name)).performClick()
        compose.onNodeWithContentDescription(resourceText(R.string.set_media_rating, item.name, 4)).performClick()
        compose.onNodeWithText(resourceText(R.string.rotation_degrees_short, 180)).performClick()
        compose.runOnIdle {
            assertEquals(true, protection)
            assertEquals(true, archived)
            assertEquals(4, rating)
            assertEquals(180, rotation)
        }
    }

    @Test
    fun mediaMetadataSheetHidesProtectionActionsWhenItemStatusIsUnknown() {
        val item = CameraMediaItem(
            id = "usb-host:IMG_0042.JPG",
            name = "IMG_0042.JPG",
            kind = "image",
            protected = null,
        )
        val preview = CameraUiState().withOfflinePreview()
        val state = preview.copy(previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item))
        var loaded: CameraMediaItem? = null
        val actions = noOpActions().copy(loadMediaInfo = { loaded = it })
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, actions) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.media_actions, item.name)).performClick()
        compose.waitForIdle()

        compose.runOnIdle { assertEquals(item, loaded) }
        compose.onNodeWithContentDescription(resourceText(R.string.protect_media, item.name)).assertDoesNotExist()
        compose.onNodeWithContentDescription(resourceText(R.string.unprotect_media, item.name)).assertDoesNotExist()
    }

    @Test
    fun mediaMetadataSheetHidesRatingForExplicitlyUnsupportedItem() {
        val item = CameraMediaItem(
            id = "usb-host:IMG_0042.JPG",
            name = "IMG_0042.JPG",
            kind = "image",
            ratingWritable = false,
        )
        val preview = CameraUiState().withOfflinePreview()
        val state = preview.copy(previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item))
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, noOpActions()) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.media_actions, item.name)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(resourceText(R.string.set_media_rating, item.name, 1))
            .assertDoesNotExist()
        compose.onNodeWithContentDescription(resourceText(R.string.clear_media_rating, item.name))
            .assertDoesNotExist()
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
    fun mediaMetadataSheetHidesArchiveActionsWhenItemStatusIsUnknown() {
        val item = CameraMediaItem(
            id = "ccapi:IMG_0042.JPG",
            name = "IMG_0042.JPG",
            kind = "image",
            archived = null,
        )
        val preview = CameraUiState().withOfflinePreview()
        val state = preview.copy(previewMode = false, uiMode = UiMode.MEDIA, mediaItems = listOf(item))
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) { MediaScreen(state, noOpActions()) }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.media_actions, item.name)).performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription(resourceText(R.string.archive_media, item.name)).assertDoesNotExist()
        compose.onNodeWithContentDescription(resourceText(R.string.unarchive_media, item.name)).assertDoesNotExist()
    }

    @Test
    fun mediaUploadActionIsHiddenWithoutAdvertisedCapability() {
        val state = CameraUiState().withOfflinePreview().copy(uiMode = UiMode.MEDIA)
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, noOpActions())
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.upload_media)).assertDoesNotExist()
    }

    @Test
    fun mediaUploadShowsProgressAndCancelActionWhenAdvertised() {
        val name = "EDITED_0001.JPG"
        val preview = CameraUiState().withOfflinePreview()
        val capabilities = requireNotNull(preview.capabilities)
        val state = preview.copy(
            previewMode = false,
            uiMode = UiMode.MEDIA,
            activeMediaUploadName = name,
            mediaUploadProgress = CameraMediaTransferProgress(
                bytesTransferred = 8L * 1024L * 1024L,
                totalBytes = 32L * 1024L * 1024L,
            ),
            pendingOperations = setOf(CameraOperation.MEDIA),
            capabilities = capabilities.copy(
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.MEDIA_UPLOAD,
                ),
            ),
        )
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state, noOpActions())
            }
        }

        compose.onNodeWithContentDescription(resourceText(R.string.upload_media)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(resourceText(R.string.uploading_media, name)).assertIsDisplayed()
        compose.onNodeWithText("8.0 MB / 32.0 MB (25%)").assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.cancel_media_upload)).assertIsDisplayed()
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
    fun rtpAudioMonitorIsCapabilityGatedDefaultMutedAndDispatchesExplicitEnable() {
        var requested: Boolean? = null
        val state = connectedState().copy(
            activeSettingPicker = SettingPicker.LIVE_VIEW,
            liveViewSource = LiveViewSource.CCAPI_RTP,
            liveViewAudioStatus = NativeLiveViewAudioStatus(
                advertised = true,
                available = true,
                enabled = false,
                codec = "MP4A-LATM",
                rtpPort = 12_010,
                rtpClockRate = 48_000,
                channels = 2,
            ),
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    state,
                    noOpActions().copy(setRtpAudioEnabled = { requested = it }),
                )
            }
        }

        compose.onNodeWithTag("rtp-audio-toggle")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(true, requested) }
    }

    @Test
    fun rtpAudioMonitorIsHiddenWhenSdpDoesNotAdvertiseAudio() {
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        activeSettingPicker = SettingPicker.LIVE_VIEW,
                        liveViewSource = LiveViewSource.CCAPI_RTP,
                    ),
                    noOpActions(),
                )
            }
        }

        compose.onNodeWithTag("rtp-audio-toggle").assertDoesNotExist()
    }

    @Test
    fun advertisedButUnavailableRtpAudioShowsARealDisabledState() {
        val failure = "Unsupported Canon RTP audio format"
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    connectedState().copy(
                        activeSettingPicker = SettingPicker.LIVE_VIEW,
                        liveViewSource = LiveViewSource.CCAPI_RTP,
                        liveViewAudioStatus = NativeLiveViewAudioStatus(
                            advertised = true,
                            available = false,
                            codec = "AAC-HBR",
                            error = failure,
                        ),
                    ),
                    noOpActions(),
                )
            }
        }

        compose.onNodeWithTag("rtp-audio-toggle")
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsNotEnabled()
        compose.onNodeWithText(failure).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun quarterTurnCapabilityWarningKeepsTheFullReadableMessage() {
        val message = resourceText(R.string.capture_not_supported)
        val base = connectedState()
        val state = base.copy(
            capabilities = base.capabilities?.copy(
                matrix = CapabilityMatrix(
                    supported = base.capabilities.matrix.supported +
                        CameraFeature.LIVE_VIEW_MAGNIFICATION,
                ),
                liveView = base.capabilities.liveView.copy(
                    magnifications = listOf(
                        LiveViewMagnification.X1,
                        LiveViewMagnification.X5,
                        LiveViewMagnification.X10,
                    ),
                    currentMagnification = LiveViewMagnification.X1,
                ),
            ),
        )
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(1.5f)) {
                    CompositionLocalProvider(
                        LocalCameraControlRotation provides -90f,
                        LocalCameraControlTargetRotation provides -90f,
                    ) {
                        MaterialTheme { CameraControlScreen(state, noOpActions()) }
                    }
                }
            }
        }

        compose.onNodeWithTag("capability-warning-surface", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(message).assertIsDisplayed()
        compose.onNodeWithContentDescription(message).assertIsDisplayed()
        compose.onNodeWithTag("capability-warning-message", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodesWithTag("capability-warning-message-overflow", useUnmergedTree = true)
            .assertCountEquals(0)
        val warningBounds = compose
            .onNodeWithTag("capability-warning-surface", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val warningSlotBounds = compose
            .onNodeWithTag("capability-warning-rotation", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val liveViewBounds = compose
            .onNodeWithTag("live-view-frame")
            .fetchSemanticsNode()
            .boundsInRoot
        val exposureBounds = compose
            .onNodeWithTag("exposure-control-ISO")
            .fetchSemanticsNode()
            .boundsInRoot
        val magnificationBounds = compose
            .onNodeWithTag("live-view-magnification")
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "Full rotated warning $warningBounds must stay inside the viewfinder $liveViewBounds",
            warningBounds.left >= liveViewBounds.left &&
                warningBounds.top >= liveViewBounds.top &&
                warningBounds.right <= liveViewBounds.right &&
                warningBounds.bottom <= liveViewBounds.bottom,
        )
        assertTrue(
            "A side-facing warning must retain a wide reading area from the user's viewpoint: $warningSlotBounds",
            warningSlotBounds.height > warningSlotBounds.width,
        )
        assertTrue(
            "Capability warning must not move or overlap the fixed exposure rail",
            warningBounds.bottom <= exposureBounds.top,
        )
        assertTrue(
            "Capability warning $warningBounds must not cover magnification $magnificationBounds",
            warningBounds.right <= magnificationBounds.left ||
                warningBounds.left >= magnificationBounds.right ||
                warningBounds.bottom <= magnificationBounds.top ||
                warningBounds.top >= magnificationBounds.bottom,
        )
    }

    @Test
    fun quarterTurnKeepsLongErrorsInsideACompactReadableNotice() {
        val message = "Camera request failed after checking every advertised CCAPI endpoint."
        compose.setContent {
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(DpSize(360.dp, 800.dp)),
            ) {
                CompositionLocalProvider(
                    LocalCameraControlRotation provides -90f,
                    LocalCameraControlTargetRotation provides -90f,
                ) {
                    MaterialTheme {
                        Box(Modifier.fillMaxSize()) {
                            Box(Modifier.align(Alignment.BottomCenter)) {
                                ErrorBanner(message, onDismiss = {})
                            }
                        }
                    }
                }
            }
        }

        compose.onNodeWithText(message).assertIsDisplayed()
        val notice = compose
            .onNodeWithTag("camera-error-rotation", useUnmergedTree = true)
            .fetchSemanticsNode()
            .boundsInRoot
        val copy = compose.onNodeWithText(message).fetchSemanticsNode().boundsInRoot
        assertTrue("Side-facing error notice should remain wide to the user: $notice", notice.height > notice.width)
        assertTrue(
            "Long error copy $copy must stay inside its stable notice $notice",
            copy.left >= notice.left && copy.top >= notice.top &&
                copy.right <= notice.right && copy.bottom <= notice.bottom,
        )
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
    fun temperatureRestrictionDisablesStartsButKeepsRecordingStopAvailable() {
        val base = connectedState()
        var recordingToggleCount = 0
        val state = mutableStateOf(
            base.copy(
                status = base.status?.copy(temperature = CameraTemperatureStatus.DISABLE_RELEASE),
            ),
        )
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    state.value,
                    noOpActions().copy(toggleRecording = { recordingToggleCount += 1 }),
                )
            }
        }

        compose.onNodeWithTag("temperature-status-banner").assertIsDisplayed()
        compose.onNodeWithTag("capture-button", useUnmergedTree = true).assertIsNotEnabled()

        compose.runOnIdle {
            state.value = state.value.copy(
                captureMode = CaptureMode.VIDEO,
                status = state.value.status?.copy(
                    recording = true,
                    temperature = CameraTemperatureStatus.RESTRICTION_MOVIE_RECORDING,
                ),
            )
        }
        compose.onNodeWithTag("capture-button", useUnmergedTree = true)
            .assertIsEnabled()
            .performClick()
        compose.runOnIdle { assertEquals(1, recordingToggleCount) }
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
    fun physicalValidationShowsOnlyObservedAdvertisedFeaturesAndCallsAction() {
        var confirmed: Pair<CameraFeature, Boolean>? = null
        val base = connectedState()
        val state = base.copy(
            uiMode = UiMode.DEBUG,
            capabilities = base.capabilities?.copy(
                matrix = CapabilityMatrix(
                    supported = setOf(CameraFeature.STILL_CAPTURE, CameraFeature.LIVE_VIEW),
                ),
                evidence = base.capabilities.evidence.copy(
                    observedFeatures = setOf(CameraFeature.STILL_CAPTURE),
                ),
            ),
        )
        compose.setContent {
            MaterialTheme {
                DebugScreen(
                    state,
                    noOpActions().copy(setOperatorConfirmation = { feature, value ->
                        confirmed = feature to value
                    }),
                )
            }
        }

        compose.onNodeWithTag("physical-confirmation-STILL_CAPTURE")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(48.dp)
        compose.onAllNodesWithTag("physical-confirmation-LIVE_VIEW").assertCountEquals(0)
        compose.onNodeWithContentDescription(
            resourceText(R.string.physical_validation_confirmation_description, "STILL_CAPTURE"),
        ).performClick()
        compose.runOnIdle {
            assertEquals(CameraFeature.STILL_CAPTURE to true, confirmed)
        }
        compose.onNodeWithTag("copy-physical-validation-record").assertIsEnabled()
    }

    @Test
    fun simulatorCannotCreatePhysicalValidationRecord() {
        val base = connectedState()
        val state = base.copy(
            uiMode = UiMode.DEBUG,
            info = base.info?.copy(api = "simulated-ccapi"),
            capabilities = base.capabilities?.copy(
                matrix = CapabilityMatrix(supported = setOf(CameraFeature.STILL_CAPTURE)),
                evidence = base.capabilities.evidence.copy(
                    source = "simulator contract",
                    observedFeatures = setOf(CameraFeature.STILL_CAPTURE),
                ),
            ),
        )
        compose.setContent { MaterialTheme { DebugScreen(state, noOpActions()) } }

        compose.onNodeWithText(resourceText(R.string.physical_validation_simulator_unavailable))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithTag("physical-confirmation-STILL_CAPTURE").assertCountEquals(0)
        compose.onNodeWithTag("copy-physical-validation-record").assertIsNotEnabled()
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
                            waveformVisible = true,
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
        compose.onNodeWithContentDescription(resourceText(R.string.luma_waveform)).assertIsDisplayed()
        compose.onNodeWithContentDescription(resourceText(R.string.monitor_guides)).assertIsDisplayed()
    }

    @Test
    fun monitoringAssistsExposeGuidesAndUpdateUserSelections() {
        val picker = mutableStateOf<SettingPicker?>(SettingPicker.LIVE_VIEW)
        var settings = LiveViewMonitorSettings()
        val actions = noOpActions().copy(
            openPicker = { picker.value = it },
            setHistogramVisible = {
                settings = settings.copy(
                    histogramVisible = it,
                    waveformVisible = if (it) false else settings.waveformVisible,
                )
            },
            setWaveformVisible = {
                settings = settings.copy(
                    waveformVisible = it,
                    histogramVisible = if (it) false else settings.histogramVisible,
                )
            },
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
        compose.onNodeWithText(resourceText(R.string.luma_waveform)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-lut-options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.load_cube_lut)).assertIsEnabled()
        compose.onNodeWithTag("monitor-zebra-options").performScrollTo().performScrollToIndex(6)
        compose.onNodeWithText(resourceText(R.string.zebra_threshold, 95)).performClick()
        compose.onNodeWithText(resourceText(R.string.false_color)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-frame-guide-options").performScrollTo().performScrollToIndex(2)
        compose.onNodeWithText(resourceText(R.string.ratio_2_39)).performScrollTo().performClick()
        compose.onNodeWithText(resourceText(R.string.safe_area)).performScrollTo().performClick()
        compose.onNodeWithTag("monitor-desqueeze-options").performScrollTo().performScrollToIndex(4)
        compose.onNodeWithText(resourceText(R.string.desqueeze_value, 2f)).performScrollTo().performClick()
        compose.runOnIdle {
            assertFalse(settings.histogramVisible)
            assertTrue(settings.waveformVisible)
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
        compose.onNodeWithText(resourceText(R.string.luma_waveform)).assertIsNotEnabled()
        compose.onNodeWithTag("monitor-lut-options").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resourceText(R.string.load_cube_lut)).assertIsNotEnabled()
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
    fun liveViewMagnificationControlCyclesOnlyAdvertisedValuesIncludingTenTimes() {
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
                            liveView = base.capabilities.liveView.copy(
                                magnifications = listOf(
                                    LiveViewMagnification.X1,
                                    LiveViewMagnification.X5,
                                    LiveViewMagnification.X10,
                                ),
                                currentMagnification = LiveViewMagnification.X5,
                            ),
                        ),
                        liveViewMagnification = LiveViewMagnification.X5,
                    ),
                    noOpActions().copy(setLiveViewMagnification = { selected = it }),
                )
            }
        }

        compose.onNodeWithTag("live-view-magnification").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(LiveViewMagnification.X10, selected) }
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
    fun liveViewMagnificationControlIsHiddenInVideoMode() {
        val base = connectedState()
        compose.setContent {
            MaterialTheme {
                CameraControlScreen(
                    base.copy(
                        captureMode = CaptureMode.VIDEO,
                        capabilities = base.capabilities?.copy(
                            matrix = CapabilityMatrix(
                                supported = base.capabilities.matrix.supported +
                                    CameraFeature.LIVE_VIEW_MAGNIFICATION,
                            ),
                            liveView = base.capabilities.liveView.copy(
                                magnifications = listOf(
                                    LiveViewMagnification.X1,
                                    LiveViewMagnification.X5,
                                ),
                                currentMagnification = LiveViewMagnification.X1,
                            ),
                        ),
                    ),
                    noOpActions(),
                )
            }
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

    private fun connectedStateWithFileNaming(): CameraUiState {
        val base = connectedState()
        val capabilities = requireNotNull(base.capabilities)
        return base.copy(
            capabilities = capabilities.copy(
                fileNaming = CameraFileNaming(
                    stillFilenameMode = "preset_code",
                    stillFilenameModeOptions = listOf("preset_code", "usersetting1", "usersetting2"),
                    stillUserSetting1 = "IMG_",
                    stillUserSetting2 = "EOS",
                    movieIndex = "A_",
                    movieReelNumber = 1,
                    movieReelRange = CameraIntegerRange(1, 9999, 1),
                    movieClipNumber = 1,
                    movieClipRange = CameraIntegerRange(1, 999, 1),
                    movieUserDefined = "EOS01",
                ),
                matrix = capabilities.matrix.copy(
                    supported = capabilities.matrix.supported + CameraFeature.FILE_NAMING_CONTROL,
                ),
            ),
        )
    }

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

    private fun saveVisualSnapshot(
        stem: String,
        userViewRotationDegrees: Float = 0f,
        nodeTag: String? = null,
    ) {
        compose.waitForIdle()
        val snapshotNode = if (nodeTag == null) {
            compose.onRoot(useUnmergedTree = true)
        } else {
            compose.onNodeWithTag(nodeTag, useUnmergedTree = true)
        }
        val deviceFrame = snapshotNode.captureToImage().asAndroidBitmap()
        writeVisualSnapshot("$stem-device-frame.png", deviceFrame)
        if (userViewRotationDegrees == 0f) return

        val userView = Bitmap.createBitmap(
            deviceFrame,
            0,
            0,
            deviceFrame.width,
            deviceFrame.height,
            Matrix().apply { setRotate(-userViewRotationDegrees) },
            true,
        )
        writeVisualSnapshot("$stem-user-view.png", userView)
        if (kotlin.math.abs(userViewRotationDegrees) == 90f) {
            assertTrue("A side-facing user view must be wider than it is tall", userView.width > userView.height)
        }
    }

    private fun writeVisualSnapshot(name: String, bitmap: Bitmap) {
        TestStorage().openOutputFile(name).buffered().use { output ->
            assertTrue("Failed to encode visual snapshot $name", bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
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

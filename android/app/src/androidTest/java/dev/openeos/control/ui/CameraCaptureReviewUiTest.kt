package dev.openeos.control.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraCaptureReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun waitingReviewHasReachableReadOnlyActionsAtLargeTextAndSmallSizes() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, captureReviewStatus = CaptureReviewStatus.NOT_READY,
            captureStatusReadbackFailed = true,
        ))
        val size = mutableStateOf(DpSize(320.dp, 480.dp))
        val locale = mutableStateOf(LocaleList("en"))
        var retries = 0
        var opened = 0
        var shutterCommands = 0
        val actions = noOpActions().copy(
            retryCaptureReview = {
                retries += 1
                state.value = state.value.copy(captureReviewStatus = CaptureReviewStatus.SEARCHING, captureReviewLoading = true)
            },
            openCaptureReview = { opened += 1 },
            captureStill = { shutterCommands += 1 },
        )
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(locale.value)) {
                        MaterialTheme(colorScheme = OpenEosColorScheme) { CaptureReviewButton(state.value, actions) }
                    }
                }
            }
        }
        for ((language, viewport) in listOf("en" to DpSize(320.dp, 480.dp), "zh-TW" to DpSize(480.dp, 320.dp))) {
            compose.runOnIdle {
                size.value = viewport
                locale.value = LocaleList(language)
                state.value = state.value.copy(captureReviewStatus = CaptureReviewStatus.NOT_READY, captureReviewLoading = false)
            }
            compose.onNodeWithTag("capture-review-button").assertIsEnabled().performClick()
            compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
            compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("capture-review-open-existing").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("capture-review-status-dialog").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(2, retries)
            assertEquals(2, opened)
            assertEquals(0, shutterCommands)
        }
    }

    @Test fun noPreviousItemStillExposesNotReadyRetryAndOfflinePreviewRemainsDirect() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, captureReviewItem = null, captureReviewStatus = CaptureReviewStatus.NOT_READY,
        ))
        var opened = 0
        val actions = noOpActions().copy(openCaptureReview = { opened += 1 })
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { CaptureReviewButton(state.value, actions) } }
        compose.onNodeWithTag("capture-review-button").assertIsEnabled().performClick()
        compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("capture-review-open-existing").assertDoesNotExist()
        compose.onNodeWithTag("capture-review-dismiss").performScrollTo().performClick()
        compose.runOnIdle { state.value = CameraUiState().withOfflinePreview() }
        compose.onNodeWithTag("capture-review-button").performClick()
        compose.onNodeWithTag("capture-review-status-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, opened) }
    }
    @Test fun retryButtonIsDisabledDuringMediaAndAvailableAfterItFinishes() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, captureReviewStatus = CaptureReviewStatus.NOT_READY,
            pendingOperations = setOf(CameraOperation.MEDIA),
        ))
        var retries = 0
        val actions = noOpActions().copy(retryCaptureReview = { retries += 1 })
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { CaptureReviewButton(state.value, actions) } }
        compose.onNodeWithTag("capture-review-button").performClick()
        compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, retries)
            state.value = state.value.copy(pendingOperations = emptySet())
        }
        compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
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
        downloadMedia = {}, deleteMedia = {},
        cancelMediaDownload = {},
        refreshLiveView = {}, restartLiveView = {},
        setAutoRefresh = {}, setFps = {}, setLiveViewSize = {}, setLiveViewSource = {}, setAppLanguage = {}, clearError = {},
    )

}

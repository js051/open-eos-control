package dev.openeos.control.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import dev.openeos.control.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CameraCaptureReviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun missingAndFailedReviewsHaveDistinctReachableReadOnlyActionsInBothLanguages() {
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
            // AndroidView's factory retains its first context. Recreate the real view when
            // changing locale/viewport, so the separate Dialog window receives that context too.
            key(locale.value, size.value) {
                DeviceConfigurationOverride(DeviceConfigurationOverride.ForcedSize(size.value)) {
                    DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(locale.value)) {
                        DialogFontScaleOverride(2f) {
                            MaterialTheme(colorScheme = OpenEosColorScheme) { CaptureReviewButton(state.value, actions) }
                        }
                    }
                }
            }
        }
        for ((language, viewport) in listOf("en" to DpSize(320.dp, 480.dp), "zh-TW" to DpSize(480.dp, 320.dp))) {
            for (status in listOf(CaptureReviewStatus.NOT_READY, CaptureReviewStatus.READ_FAILED)) {
                val missing = if (language == "en") "New media has not been found yet." else "尚未找到新出現的素材。"
                val failed = if (language == "en") "Recent media could not be read. New media availability is unknown."
                    else "無法讀取最近素材，目前無法確認是否有新素材。"
                val message = if (status == CaptureReviewStatus.READ_FAILED) failed else missing
                compose.runOnIdle {
                    size.value = viewport
                    locale.value = LocaleList(language)
                    state.value = state.value.copy(captureReviewStatus = status, captureReviewLoading = false)
                }
                try {
                    compose.onNodeWithTag("capture-review-button").assertContentDescriptionEquals(message).assertIsEnabled().performClick()
                    val dialog = compose.onNodeWithTag("capture-review-status-dialog").assertIsDisplayed().fetchSemanticsNode()
                    compose.runOnIdle {
                        // Parent composition assertions cannot prove a separate Dialog's locale
                        // or density. Check the actual Android root before checking its text.
                        val resources = (dialog.root as ViewRootForTest).view.resources
                        assertEquals(language, resources.configuration.locales[0].toLanguageTag())
                        assertEquals(2f, resources.configuration.fontScale, 0.01f)
                        assertEquals(2f, dialog.layoutInfo.density.fontScale, 0.01f)
                        assertEquals(message, resources.getString(if (status == CaptureReviewStatus.READ_FAILED)
                            R.string.capture_review_read_failed else R.string.capture_review_not_ready))
                    }
                    compose.onNodeWithText(message).performScrollTo().assertIsDisplayed()
                    compose.onNodeWithText(if (status == CaptureReviewStatus.READ_FAILED) missing else failed).assertDoesNotExist()
                    val retry = compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsDisplayed().assertIsEnabled()
                    val retryNode = retry.fetchSemanticsNode()
                    compose.runOnIdle { assertFullyVisibleDialogAction(retryNode) }
                    retry.performClick()
                    compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsNotEnabled()
                    val open = compose.onNodeWithTag("capture-review-open-existing").performScrollTo().assertIsDisplayed()
                    val openNode = open.fetchSemanticsNode()
                    compose.runOnIdle { assertFullyVisibleDialogAction(openNode) }
                    open.performClick()
                    compose.onNodeWithTag("capture-review-status-dialog").assertDoesNotExist()
                } catch (failure: Throwable) {
                    recordConfigurationFailure(language, status)
                    throw failure
                }
            }
        }
        compose.runOnIdle {
            assertEquals(4, retries)
            assertEquals(4, opened)
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
        for (status in listOf(CaptureReviewStatus.NOT_READY, CaptureReviewStatus.READ_FAILED)) {
            compose.runOnIdle { state.value = state.value.copy(captureReviewStatus = status) }
            compose.onNodeWithTag("capture-review-button").assertIsEnabled().performClick()
            compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("capture-review-open-existing").assertDoesNotExist()
            compose.onNodeWithTag("capture-review-dismiss").performScrollTo().performClick()
        }
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

    @Test fun failedReviewRetryIsBlockedDuringCaptureAndMediaAndRecoveryClosesNotice() {
        val state = mutableStateOf(CameraUiState().withOfflinePreview().copy(
            previewMode = false, captureReviewStatus = CaptureReviewStatus.READ_FAILED,
        ))
        var retries = 0
        var opened = 0
        val actions = noOpActions().copy(retryCaptureReview = { retries += 1 }, openCaptureReview = { opened += 1 })
        compose.setContent { MaterialTheme(colorScheme = OpenEosColorScheme) { CaptureReviewButton(state.value, actions) } }
        compose.onNodeWithTag("capture-review-button").performClick()
        for (operation in listOf(CameraOperation.MEDIA, CameraOperation.CAPTURE)) {
            compose.runOnIdle { state.value = state.value.copy(pendingOperations = setOf(operation)) }
            compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsNotEnabled()
        }
        compose.runOnIdle { state.value = state.value.copy(pendingOperations = emptySet()) }
        compose.onNodeWithTag("capture-review-retry").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, retries)
            state.value = state.value.copy(captureReviewStatus = CaptureReviewStatus.IDLE)
        }
        compose.onNodeWithTag("capture-review-status-dialog").assertDoesNotExist()
        compose.onNodeWithTag("capture-review-button").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    private fun recordConfigurationFailure(language: String, status: CaptureReviewStatus) {
        // Preserve the actual roots before rule teardown, without masking the original failure.
        runCatching {
            compose.onAllNodes(isRoot(), useUnmergedTree = true).printToLog("CAPTURE_REVIEW_FAILURE")
        }.onFailure { println("CAPTURE_REVIEW_SEMANTICS_UNAVAILABLE ${it.javaClass.simpleName}") }
        if (InstrumentationRegistry.getArguments().getString("requireSimulator") != "true" ||
            Build.HARDWARE !in setOf("ranchu", "goldfish")) return
        runCatching {
            val image = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                TestStorage().openOutputFile("capture-review-$language-${status.name.lowercase()}-failure.png").use {
                    check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
            } finally { image.recycle() }
        }.onFailure { println("CAPTURE_REVIEW_SCREENSHOT_UNAVAILABLE ${it.javaClass.simpleName}") }
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

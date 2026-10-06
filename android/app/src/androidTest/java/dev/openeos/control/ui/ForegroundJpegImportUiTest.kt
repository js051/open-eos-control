package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.text.TextLayoutResult
import kotlin.math.ceil
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.test.filters.SdkSuppress
import dev.openeos.control.data.CameraFeature
import dev.openeos.control.data.CameraTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** UI callbacks and reachability only. These tests do not prove HTTP discovery or MediaStore output. */
@SdkSuppress(minSdkVersion = 29)
class ForegroundJpegImportUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun ready() = CameraUiState().withOfflinePreview().copy(
        previewMode = false, transport = CameraTransport.CCAPI_NETWORK,
        uiMode = UiMode.MEDIA, mediaItems = emptyList(),
    )

    @Test fun galleryEntryAndCancelNeverStartUntilExplicitEnable() {
        val state = mutableStateOf(ready())
        var starts = 0
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                MediaScreen(state.value, connectionRecoveryTestActions().copy(enableForegroundJpegImport = {
                    starts++
                    state.value = state.value.copy(foregroundJpegImport = ForegroundJpegImportStatus(
                        phase = ForegroundImportPhase.BASELINING))
                }))
            }
        }
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-destination").performScrollTo()
            .assertTextContains(cameraGalleryPath(state.value.info?.model), substring = true)
        compose.onNodeWithTag("foreground-import-disclosure").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-limits").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-stop").assertIsDisplayed().assertIsEnabled()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun eligibilityIsRecheckedWhileDisclosureIsOpen() {
        val base = ready()
        val state = mutableStateOf(base)
        val supported = mutableStateOf(false)
        var starts = 0
        val capabilities = requireNotNull(base.capabilities)
        compose.setContent {
            MaterialTheme {
                ForegroundJpegImportDialog(state.value, connectionRecoveryTestActions().copy(
                    enableForegroundJpegImport = { starts++ }), onDismiss = {},
                    platformSupported = supported.value)
            }
        }
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { supported.value = true }
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled()
        val gatedStates = listOf(
            base.copy(info = null),
            base.copy(foregroundImportOwnerActive = true),
            base.copy(previewMode = true),
            base.copy(transport = CameraTransport.USB_PTP),
            base.copy(transport = CameraTransport.DESKTOP_BRIDGE),
            base.copy(capabilities = capabilities.copy(matrix = capabilities.matrix.copy(
                supported = capabilities.matrix.supported - CameraFeature.MEDIA_BROWSER))),
            base.copy(capabilities = capabilities.copy(matrix = capabilities.matrix.copy(
                supported = capabilities.matrix.supported - CameraFeature.MEDIA_DOWNLOAD))),
            base.copy(pendingOperations = setOf(CameraOperation.CAPTURE)),
            base.copy(pendingOperations = setOf(CameraOperation.MEDIA)),
            base.copy(mediaLibraryLoading = true),
            base.copy(captureReviewLoading = true),
            base.copy(activeMediaDownloadName = "MANUAL.JPG"),
            base.copy(activeMediaUploadName = "UPLOAD.JPG"),
            base.copy(autofocusHoldState = AutofocusHoldState.HOLDING),
            base.copy(shutterReleaseUnconfirmed = true),
            base.copy(status = base.status?.copy(recording = true)),
        )
        gatedStates.forEach { gated ->
            compose.runOnIdle { state.value = gated }
            compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled().performClick()
            compose.onNodeWithTag("foreground-import-unavailable").performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(0, starts); state.value = base }
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test fun cleanupAcknowledgementUnblocksButNeverEnablesDeletesOrConfirmsCleanup() {
        val state = mutableStateOf(ready().copy(foregroundImportCleanupUnconfirmed = true))
        var acknowledgements = 0
        var starts = 0
        var stops = 0
        var deletes = 0
        val actions = connectionRecoveryTestActions().copy(
            enableForegroundJpegImport = { starts++ },
            stopForegroundJpegImport = { stops++ },
            deleteMedia = { deletes++ },
            deleteMediaBatch = { deletes++ },
            acknowledgeForegroundImportCleanupWarning = {
                acknowledgements++
                state.value = state.value.copy(foregroundImportCleanupUnconfirmed = false)
            },
        )
        compose.setContent { MaterialTheme { ForegroundJpegImportDialog(state.value, actions, onDismiss = {}) } }
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements); assertEquals(0, starts) }
        compose.onNodeWithTag("foreground-import-cleanup-acknowledge").performScrollTo()
            .assertIsDisplayed().assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithTag("foreground-import-cleanup-warning").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, acknowledgements)
            assertEquals(0, starts)
            assertEquals(0, stops)
            assertEquals(0, deletes)
            assertEquals(ForegroundImportPhase.OFF, state.value.foregroundJpegImport.phase)
        }
    }

    @Test fun cleanupAcknowledgementStaysDisabledUntilOwnerRetiresEvenAfterSessionClears() {
        val state = mutableStateOf(ready().copy(foregroundImportCleanupUnconfirmed = true,
            foregroundImportOwnerActive = true,
            foregroundJpegImport = ForegroundJpegImportStatus(phase = ForegroundImportPhase.STOPPING)))
        var acknowledgements = 0
        compose.setContent { MaterialTheme { ForegroundJpegImportDialog(state.value,
            connectionRecoveryTestActions().copy(acknowledgeForegroundImportCleanupWarning = { acknowledgements++ }),
            onDismiss = {}) } }
        compose.onNodeWithTag("foreground-import-cleanup-acknowledge").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { state.value = CameraUiState(foregroundImportCleanupUnconfirmed = true,
            foregroundImportOwnerActive = true) }
        compose.onNodeWithTag("foreground-import-cleanup-acknowledge").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, acknowledgements)
            state.value = state.value.copy(foregroundImportOwnerActive = false) }
        compose.onNodeWithTag("foreground-import-cleanup-acknowledge").performScrollTo().assertIsEnabled().performTouchInput { click() }
        compose.runOnIdle { assertEquals(1, acknowledgements) }
    }

    @Test fun cleanupWarningSurvivesOffDisconnectReplacementAndReopenedUiState() {
        // The VM owns persistence. This test renders the boolean loaded into otherwise fresh state.
        val state = mutableStateOf(ready().copy(uiMode = UiMode.CONTROL, foregroundImportCleanupUnconfirmed = true))
        var starts = 0
        var acknowledgements = 0
        val actions = connectionRecoveryTestActions().copy(enableForegroundJpegImport = { starts++ },
            acknowledgeForegroundImportCleanupWarning = { acknowledgements++ })
        compose.setContent {
            MaterialTheme(colorScheme = OpenEosColorScheme) {
                ForegroundImportCleanupWarningHost(state.value, actions) {
                    if (state.value.connected) CameraControlScreen(state.value, actions)
                    else ConnectionScreen(state.value, actions)
                }
            }
        }
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-dismiss").performScrollTo().performClick()
        compose.runOnIdle { state.value = CameraUiState(foregroundImportCleanupUnconfirmed = true) }
        compose.onNodeWithTag("foreground-import-global-warning").assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            state.value = ready().copy(uiMode = UiMode.CONTROL,
                mediaSessionGeneration = state.value.mediaSessionGeneration + 1,
                foregroundImportCleanupUnconfirmed = true)
        }
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-global-warning").assertDoesNotExist()
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(0, starts)
            assertEquals(0, acknowledgements)
            assertTrue(state.value.foregroundImportCleanupUnconfirmed)
            assertEquals(ForegroundImportPhase.OFF, state.value.foregroundJpegImport.phase)
        }
    }

    @Test fun cleanupWarningAlsoHasAnEntryFromDebugWithoutImplicitAcknowledgement() {
        val state = ready().copy(uiMode = UiMode.DEBUG, foregroundImportCleanupUnconfirmed = true)
        var acknowledgements = 0
        compose.setContent { MaterialTheme { ForegroundImportCleanupWarningHost(state,
            connectionRecoveryTestActions().copy(acknowledgeForegroundImportCleanupWarning = { acknowledgements++ })) {
                Box(Modifier.size(320.dp, 480.dp))
            } } }
        compose.onNodeWithTag("foreground-import-global-warning").assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-cleanup-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(0, acknowledgements) }
    }

    @Test fun debugRetainsNormalActiveStopAndTerminalFailureWithoutCleanupWarning() {
        val state = mutableStateOf(ready().copy(uiMode = UiMode.DEBUG,
            foregroundJpegImport = ForegroundJpegImportStatus(phase = ForegroundImportPhase.WATCHING)))
        var stops = 0
        var starts = 0
        compose.setContent { MaterialTheme { ForegroundImportCleanupWarningHost(state.value,
            connectionRecoveryTestActions().copy(enableForegroundJpegImport = { starts++ },
                stopForegroundJpegImport = {
                    stops++
                    state.value = state.value.copy(foregroundJpegImport = state.value.foregroundJpegImport.copy(
                        phase = ForegroundImportPhase.STOPPING, stopReason = ForegroundImportStopReason.USER))
                })) { Box(Modifier.size(320.dp, 480.dp)) } } }
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-stop").assertIsEnabled().performTouchInput { click() }
        compose.onNodeWithTag("foreground-import-stop").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, stops)
            assertEquals(0, starts)
            state.value = state.value.copy(foregroundJpegImport = ForegroundJpegImportStatus(
                phase = ForegroundImportPhase.STOPPED, stopReason = ForegroundImportStopReason.TRANSFER_FAILED))
        }
        compose.onNodeWithTag("foreground-import-entry").assertIsDisplayed().performClick()
        compose.onNodeWithTag("foreground-import-stop-reason").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("foreground-import-cleanup-warning").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun replacementConnectionDismissesDisclosureWithoutEnabling() {
        val state = mutableStateOf(ready())
        var starts = 0
        compose.setContent {
            MaterialTheme { ForegroundJpegImportEntry(state.value, connectionRecoveryTestActions().copy(
                enableForegroundJpegImport = { starts++ })) }
        }
        compose.onNodeWithTag("foreground-import-entry").performClick()
        compose.onNodeWithTag("foreground-import-dialog").assertIsDisplayed()
        compose.runOnIdle {
            // Identical camera descriptions still represent a replacement connection.
            state.value = state.value.copy(info = state.value.info?.copy(),
                mediaSessionGeneration = state.value.mediaSessionGeneration + 1)
        }
        compose.onNodeWithTag("foreground-import-dialog").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun activePhasesExposeCountsAndStopWaitsForCleanupWithoutRestart() {
        val state = mutableStateOf(ready())
        var stops = 0
        var starts = 0
        compose.setContent {
            MaterialTheme { ForegroundJpegImportDialog(state.value, connectionRecoveryTestActions().copy(
                enableForegroundJpegImport = { starts++ },
                stopForegroundJpegImport = {
                    stops++
                    state.value = state.value.copy(foregroundJpegImport = state.value.foregroundJpegImport.copy(
                        phase = ForegroundImportPhase.STOPPING, stopReason = ForegroundImportStopReason.USER))
                }), onDismiss = {}) }
        }
        listOf(ForegroundImportPhase.BASELINING, ForegroundImportPhase.WATCHING,
            ForegroundImportPhase.WAITING, ForegroundImportPhase.SAVING).forEach { phase ->
            compose.runOnIdle { state.value = state.value.copy(foregroundJpegImport = ForegroundJpegImportStatus(
                phase = phase, baselineCount = 10, knownCount = 15, pendingCount = 2,
                completedCount = 3, activeName = if (phase == ForegroundImportPhase.SAVING) "SYNTHETIC.JPG" else null)) }
            compose.onNodeWithTag("foreground-import-phase").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("foreground-import-counts").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("foreground-import-start").assertDoesNotExist()
            compose.onNodeWithTag("foreground-import-dialog-stop").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("foreground-import-dialog-stop").performScrollTo().assertIsNotEnabled().performClick()
            compose.onNodeWithTag("foreground-import-start").assertDoesNotExist()
            compose.onNodeWithTag("foreground-import-phase").performScrollTo().assertIsDisplayed()
        }
        compose.runOnIdle { assertEquals(4, stops); assertEquals(0, starts) }
    }

    @Test fun stoppedFailuresRemainVisibleAndNeverRetryThemselves() {
        val state = mutableStateOf(ready())
        var starts = 0
        compose.setContent {
            MaterialTheme { ForegroundJpegImportDialog(state.value, connectionRecoveryTestActions().copy(
                enableForegroundJpegImport = { starts++ }), onDismiss = {}) }
        }
        ForegroundImportStopReason.entries.forEach { reason ->
            compose.runOnIdle { state.value = state.value.copy(foregroundJpegImport = ForegroundJpegImportStatus(
                phase = ForegroundImportPhase.STOPPED, stopReason = reason,
                baselineCount = 10, knownCount = 15, completedCount = 3, discardedCount = 2)) }
            compose.onNodeWithTag("foreground-import-stop-reason").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("foreground-import-start").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("foreground-import-dialog-stop").assertDoesNotExist()
        }
        compose.runOnIdle { assertEquals(0, starts) }
    }

    @Test fun controlStopIsReachableWithHudShownOrHiddenAndDoesNotCoverShutter() {
        val state = mutableStateOf(ready().copy(uiMode = UiMode.CONTROL))
        var stops = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme(colorScheme = OpenEosColorScheme) {
                    Box(Modifier.size(320.dp, 640.dp)) {
                        CameraControlScreen(state.value, connectionRecoveryTestActions().copy(
                            stopForegroundJpegImport = { stops++ }))
                    }
                }
            }
        }
        compose.onNodeWithTag("foreground-import-entry").assertDoesNotExist()
        for (hud in listOf(true, false)) {
            compose.runOnIdle { state.value = state.value.copy(hudVisible = hud,
                foregroundJpegImport = ForegroundJpegImportStatus(phase = ForegroundImportPhase.WATCHING)) }
            val stop = compose.onNodeWithTag("foreground-import-stop").assertIsDisplayed().assertIsEnabled()
            if (hud) {
                val shutterBounds = compose.onNodeWithTag("capture-button").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val stopBounds = stop.fetchSemanticsNode().boundsInRoot
                assertTrue("The import Stop strip must not overlap the shutter", shutterBounds.bottom <= stopBounds.top)
            }
            stop.performTouchInput { click() }
        }
        compose.runOnIdle { assertEquals(2, stops) }
    }

    @Test fun englishLargeTextLandscapeCleanupAcknowledgementIsFullyReachable() =
        verifyLargeTextAction("en", "foreground-import-cleanup-acknowledge")
    @Test fun traditionalChineseLargeTextLandscapeCleanupAcknowledgementIsFullyReachable() =
        verifyLargeTextAction("zh-TW", "foreground-import-cleanup-acknowledge")
    @Test fun englishLargeTextLandscapeEnableIsFullyReachable() = verifyLargeTextAction("en", "foreground-import-start")
    @Test fun traditionalChineseLargeTextLandscapeEnableIsFullyReachable() = verifyLargeTextAction("zh-TW", "foreground-import-start")
    @Test fun englishLargeTextLandscapeCancelIsFullyReachable() = verifyLargeTextAction("en", "foreground-import-dismiss")
    @Test fun traditionalChineseLargeTextLandscapeCancelIsFullyReachable() = verifyLargeTextAction("zh-TW", "foreground-import-dismiss")
    @Test fun englishLargeTextLandscapeStopIsFullyReachable() = verifyLargeTextAction("en", "foreground-import-dialog-stop")
    @Test fun traditionalChineseLargeTextLandscapeStopIsFullyReachable() = verifyLargeTextAction("zh-TW", "foreground-import-dialog-stop")

    private fun assertCompleteActionText(tag: String) {
        val nodes = compose.onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("The action must expose its text layout: $tag", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
            assertTrue("The action must return its text layout: $tag", layouts.isNotEmpty())
            layouts.forEach { layout ->
                // String semantics may report unused paragraph width. Use occupied line extents
                // just as the existing connection layout canaries do, plus height and ellipsis.
                val lineWidths = (0 until layout.lineCount).map {
                    ceil((layout.getLineRight(it) - layout.getLineLeft(it)).toDouble()).toInt()
                }
                val noWrapClipped = !layout.layoutInput.softWrap &&
                    ceil(layout.multiParagraph.intrinsics.maxIntrinsicWidth.toDouble()).toInt() > layout.size.width
                assertTrue("Horizontal action text clipping: $tag", !noWrapClipped && lineWidths.all { it <= layout.size.width })
                assertTrue("Vertical action text clipping: $tag", !layout.didOverflowHeight)
                assertTrue("Ellipsized action text: $tag", (0 until layout.lineCount).none(layout::isLineEllipsized))
            }
        }
    }

    private fun verifyLargeTextAction(locale: String, tag: String) {
        val orientation = compose.activity.requestedOrientation
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000L) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    ready = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        it.window.decorView.isLaidOut && it.window.decorView.width > it.window.decorView.height
                }
                ready
            }
            var starts = 0
            var stops = 0
            var dismisses = 0
            var acknowledgements = 0
            val state = ready().copy(
                foregroundImportCleanupUnconfirmed = tag == "foreground-import-cleanup-acknowledge",
                foregroundJpegImport = ForegroundJpegImportStatus(
                    phase = if (tag == "foreground-import-dialog-stop") ForegroundImportPhase.SAVING else ForegroundImportPhase.OFF,
                    activeName = if (tag == "foreground-import-dialog-stop") "SYNTHETIC.JPG" else null,
                ),
            )
            compose.setContent {
                DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                    DialogFontScaleOverride(2f) {
                        MaterialTheme(colorScheme = OpenEosColorScheme) {
                            ForegroundJpegImportDialog(state, connectionRecoveryTestActions().copy(
                                enableForegroundJpegImport = { starts++ }, stopForegroundJpegImport = { stops++ },
                                acknowledgeForegroundImportCleanupWarning = { acknowledgements++ }),
                                onDismiss = { dismisses++ }, modifier = Modifier.size(320.dp, 320.dp))
                        }
                    }
                }
            }
            compose.runOnIdle { assertLandscapeActivityWindow(compose.activity) }
            compose.onNodeWithTag("foreground-import-disclosure").performScrollTo().assertIsDisplayed()
                .assertTextContains(if (locale == "zh-TW") "新檔案識別碼" else "new file identity", substring = true)
            val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            val node = action.fetchSemanticsNode()
            compose.runOnIdle { assertFullyVisibleDialogAction(node) }
            assertCompleteActionText(tag)
            action.performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(if (tag == "foreground-import-start") 1 else 0, starts)
                assertEquals(if (tag == "foreground-import-dialog-stop") 1 else 0, stops)
                assertEquals(if (tag in setOf("foreground-import-start", "foreground-import-dismiss")) 1 else 0, dismisses)
                assertEquals(if (tag == "foreground-import-cleanup-acknowledge") 1 else 0, acknowledgements)
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        }
    }
}

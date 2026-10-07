package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.Locales
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.openeos.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.ceil

/** Presentation acceptance only: immutable synthetic states, no camera, files, or MediaStore IO. */
@RunWith(AndroidJUnit4::class)
class SavedJpegDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun englishNarrowPortraitAtTwoTimesFontKeepsEveryActionReachable() =
        verifyLargeTextActions("en", landscape = false)

    @Test fun traditionalChineseNarrowPortraitAtTwoTimesFontKeepsEveryActionReachable() =
        verifyLargeTextActions("zh-TW", landscape = false)

    @Test fun englishShortLandscapeAtTwoTimesFontKeepsEveryActionReachable() =
        verifyLargeTextActions("en", landscape = true)

    @Test fun traditionalChineseShortLandscapeAtTwoTimesFontKeepsEveryActionReachable() =
        verifyLargeTextActions("zh-TW", landscape = true)

    @Test
    fun backDuringPreparationDismissesOnlyTheList() {
        val fixture = Fixture().apply {
            handoff.value = activeHandoff(CameraImportHandoffPhase.PREPARING)
            state.value = state.value.copy(progress = progress())
        }
        val originalState = fixture.state.value
        val originalHandoff = fixture.handoff.value
        showDialog(fixture)

        compose.pressFocusedDialogBack(compose.activity, DIALOG)

        compose.onNodeWithTag(DIALOG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf("dismiss"), fixture.calls)
            assertEquals(originalState, fixture.state.value)
            assertEquals(originalHandoff, fixture.handoff.value)
        }
    }

    @Test
    fun backOnClearConfirmationDoesNotClearCancelOrDismissTheList() {
        val fixture = Fixture().apply {
            handoff.value = activeHandoff(CameraImportHandoffPhase.AWAITING_RESULT)
        }
        val originalState = fixture.state.value
        val originalHandoff = fixture.handoff.value
        showDialog(fixture)
        tapAction("saved-jpegs-clear")
        compose.onNodeWithTag(CLEAR_CONFIRM).assertIsDisplayed()

        compose.pressFocusedDialogBack(compose.activity, CLEAR_CONFIRM)

        compose.onNodeWithTag(CLEAR_CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(DIALOG).assertIsDisplayed()
        scrollToTag("saved-jpegs-entry-synthetic-0").assertIsOn()
        compose.runOnIdle {
            assertTrue(fixture.calls.isEmpty())
            assertEquals(originalState, fixture.state.value)
            assertEquals(originalHandoff, fixture.handoff.value)
        }
        tapAction("saved-jpegs-close", scroll = false)
        compose.runOnIdle { assertEquals(listOf("dismiss"), fixture.calls) }
    }

    @Test
    fun onlyUnavailableSelectionCannotSendAndRecheckWaitsForOtherWork() {
        val row = row(0)
        val fixture = Fixture().apply {
            state.value = SavedJpegUiState(
                rows = listOf(row), selectedIds = setOf(row.id), unavailableIds = setOf(row.id),
            )
            automaticImportActive.value = true
        }
        showDialog(fixture)
        compose.onNodeWithTag("saved-jpegs-selection-count")
            .assertTextEquals(text("en", R.string.saved_jpegs_selected_count, 0, 1))
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        scrollToTag("saved-jpegs-entry-synthetic-0").assertIsNotEnabled()
        scrollToTag("saved-jpegs-recheck-synthetic-0").assertIsNotEnabled()

        compose.runOnIdle {
            fixture.automaticImportActive.value = false
            fixture.handoff.value = activeHandoff(CameraImportHandoffPhase.PREPARING, CameraImportHandoffOrigin.CAMERA)
        }
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        scrollToTag("saved-jpegs-recheck-synthetic-0").assertIsNotEnabled()

        compose.runOnIdle { fixture.handoff.value = CameraImportHandoffState() }
        tapAction("saved-jpegs-recheck-synthetic-0")
        compose.runOnIdle {
            assertEquals(listOf("recheck:synthetic-0"), fixture.calls)
            // A recheck request is not proof of a readable original, and cannot enable Send.
            assertEquals(setOf(row.id), fixture.state.value.unavailableIds)
        }
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
    }

    private fun verifyLargeTextActions(locale: String, landscape: Boolean) = withWindowOrientation(landscape) {
        val fixture = Fixture().apply { automaticImportActive.value = true }
        val panel = if (landscape) DpSize(480.dp, 320.dp) else DpSize(320.dp, 560.dp)
        showDialog(fixture, locale, Modifier.size(panel.width, panel.height))

        // This checks the actual Dialog layout, not ForcedSize on the Activity's composition.
        // The production tag sits inside the Surface's 12dp outer padding on each side.
        compose.onNodeWithTag(DIALOG)
            .assertWidthIsEqualTo(panel.width - 24.dp)
            .assertHeightIsEqualTo(panel.height - 24.dp)
        val fixedClose = actionBounds("saved-jpegs-close", scroll = false)
        compose.onNodeWithTag("saved-jpegs-selection-count")
            .assertTextEquals(text(locale, R.string.saved_jpegs_selected_count, 1, 100))
        assertCompleteText("saved-jpegs-selection-count")

        // Stop and Send are separate decisions. Tapping Stop does not assume cleanup is done.
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        scrollToTag("saved-jpegs-stop-import")
            .assertTextEquals(text(locale, R.string.saved_jpegs_stop_import))
        tapAction("saved-jpegs-stop-import")
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("stop"), fixture.calls) }
        compose.runOnIdle { fixture.automaticImportActive.value = false }
        scrollToTag("saved-jpegs-send").assertIsEnabled()
        compose.runOnIdle { assertEquals(listOf("stop"), fixture.calls) }
        tapAction("saved-jpegs-send")

        compose.runOnIdle {
            fixture.handoff.value = activeHandoff(CameraImportHandoffPhase.PREPARING)
            fixture.state.value = fixture.state.value.copy(progress = progress())
        }
        scrollToTag("saved-jpegs-progress-count")
            .assertTextEquals(text(locale, R.string.saved_jpegs_progress, 1, 2))
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        tapAction("saved-jpegs-cancel")
        compose.runOnIdle { assertEquals(listOf("stop", "send", "cancel"), fixture.calls) }

        // Cancellation is a separate outcome, with no fabricated verified import counts.
        compose.runOnIdle {
            fixture.handoff.value = CameraImportHandoffState()
            fixture.state.value = fixture.state.value.copy(
                progress = null,
                outcome = CameraImportHandoffOutcome(issue = CameraImportHandoffIssue.CANCELLED),
            )
        }
        scrollToTag("saved-jpegs-issue")
            .assertTextEquals(text(locale, R.string.saved_jpegs_issue_cancelled))
        compose.onNodeWithTag("saved-jpegs-result").assertDoesNotExist()
        tapAction("saved-jpegs-send")

        compose.runOnIdle {
            fixture.handoff.value = activeHandoff(CameraImportHandoffPhase.AWAITING_RESULT)
            fixture.state.value = fixture.state.value.copy(outcome = null)
        }
        scrollToTag("saved-jpegs-phase")
            .assertTextEquals(text(locale, R.string.saved_jpegs_awaiting_result))
        compose.onNodeWithTag("saved-jpegs-cancel").assertDoesNotExist()
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()

        val savedOutcome = CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(3, 2, 1, 4))
        compose.runOnIdle {
            fixture.state.value = fixture.state.value.copy(outcome = savedOutcome, cleanupUnconfirmed = true)
            fixture.handoff.value = activeHandoff(CameraImportHandoffPhase.CLEANING).let { handoff ->
                handoff.copy(active = requireNotNull(handoff.active).copy(
                    outcome = savedOutcome, cleanupUnconfirmed = true,
                ))
            }
        }
        assertSavedResult(locale, savedOutcome)
        scrollToTag("saved-jpegs-cleanup-warning")
            .assertTextEquals(text(locale, R.string.saved_jpegs_cleanup_warning))
        scrollToTag("saved-jpegs-send").assertIsNotEnabled()
        tapAction("saved-jpegs-retry-cleanup")
        assertSavedResult(locale, savedOutcome)
        scrollToTag("saved-jpegs-cleanup-warning").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf("stop", "send", "cancel", "send", "retry"), fixture.calls)
            assertEquals(savedOutcome, fixture.state.value.outcome)
        }

        // A later camera result must not replace the independently retained saved-JPEG result.
        val cameraOutcome = CameraImportHandoffOutcome(summary = CameraImportReceiptSummary(8, 0, 0, 0))
        compose.runOnIdle {
            fixture.state.value = fixture.state.value.copy(cleanupUnconfirmed = false)
            fixture.handoff.value = CameraImportHandoffState(lastResult = CameraImportHandoffResult(
                token = 2L, origin = CameraImportHandoffOrigin.CAMERA,
                outcome = cameraOutcome, cleanupUnconfirmed = false,
            ))
        }
        scrollToTag("saved-jpegs-camera-result")
            .assertTextEquals(text(locale, R.string.saved_jpegs_result, 8, 0, 0, 0))
        assertSavedResult(locale, savedOutcome)
        compose.onNodeWithTag("saved-jpegs-cleanup-warning").assertDoesNotExist()

        // The last of 100 rows, its recheck, and the first selection are all reachable.
        scrollToTag("saved-jpegs-entry-synthetic-99").assertIsNotEnabled()
        tapAction("saved-jpegs-recheck-synthetic-99")
        assertSameBounds(fixedClose, actionBounds("saved-jpegs-close", scroll = false))
        tapAction("saved-jpegs-entry-synthetic-0")
        compose.runOnIdle {
            assertEquals("toggle:synthetic-0:false", fixture.calls.last())
        }

        // Clear is records-only and requires its own confirmation even during an active handoff.
        compose.runOnIdle {
            fixture.handoff.value = activeHandoff(CameraImportHandoffPhase.AWAITING_RESULT)
        }
        tapAction("saved-jpegs-clear")
        compose.onNodeWithTag(CLEAR_CONFIRM).assertIsDisplayed()
        compose.onNodeWithText(text(locale, R.string.saved_jpegs_clear_confirmation)).assertExists()
        val cancelConfirmation = compose.onNodeWithText(text(locale, R.string.cancel))
        assertVisibleAction(cancelConfirmation)
        cancelConfirmation.performTouchInput { click() }
        compose.onNodeWithTag(CLEAR_CONFIRM).assertDoesNotExist()
        compose.runOnIdle { assertFalse(fixture.calls.contains("clear")) }

        tapAction("saved-jpegs-clear")
        compose.runOnIdle { assertFalse(fixture.calls.contains("clear")) }
        tapAction("saved-jpegs-clear-confirm-button", scroll = false)
        compose.onNodeWithTag(CLEAR_CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(DIALOG).assertIsDisplayed()
        scrollToTag("saved-jpegs-entry-synthetic-0").assertIsOn()
        compose.runOnIdle {
            assertEquals(1, fixture.calls.count { it == "clear" })
            assertEquals(1, fixture.calls.count { it == "cancel" })
            assertEquals(CameraImportHandoffPhase.AWAITING_RESULT, fixture.handoff.value.active?.phase)
            assertEquals(savedOutcome, fixture.state.value.outcome)
            // This UI dispatches only its clear callback; storage and in-flight work are external.
            fixture.state.value = fixture.state.value.copy(rows = emptyList(), selectedIds = emptySet())
        }
        val emptyFixedClose = actionBounds("saved-jpegs-close", scroll = false)
        scrollToTag("saved-jpegs-empty")
            .assertTextEquals(text(locale, R.string.saved_jpegs_empty))
        assertSavedResult(locale, savedOutcome)
        assertSameBounds(emptyFixedClose, actionBounds("saved-jpegs-close", scroll = false))
        tapAction("saved-jpegs-close", scroll = false)
        compose.onNodeWithTag(DIALOG).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(listOf(
                "stop", "send", "cancel", "send", "retry", "recheck:synthetic-99",
                "toggle:synthetic-0:false", "clear", "dismiss",
            ), fixture.calls)
            assertEquals(CameraImportHandoffPhase.AWAITING_RESULT, fixture.handoff.value.active?.phase)
        }
    }

    private fun showDialog(fixture: Fixture, locale: String = "en", modifier: Modifier = Modifier) {
        compose.awaitForegroundActivityWindow(compose.activity)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.Locales(LocaleList(locale))) {
                // Unlike a composition-only override, this also reaches the separate Dialog window.
                DialogFontScaleOverride(2f) {
                    MaterialTheme(colorScheme = OpenEosColorScheme) {
                        if (fixture.visible.value) SavedJpegDialog(
                            state = fixture.state.value,
                            handoff = fixture.handoff.value,
                            automaticImportActive = fixture.automaticImportActive.value,
                            onToggle = { id, selected -> fixture.calls += "toggle:${id.value}:$selected" },
                            onSend = { fixture.calls += "send" },
                            onCancel = { fixture.calls += "cancel" },
                            onClear = { fixture.calls += "clear" },
                            onRetryCleanup = { fixture.calls += "retry" },
                            onRecheck = { fixture.calls += "recheck:${it.value}" },
                            onStopAutomaticImport = { fixture.calls += "stop" },
                            onDismiss = { fixture.calls += "dismiss"; fixture.visible.value = false },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    private fun scrollToTag(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(LIST).performScrollToNode(hasTestTag(tag))
        // Status and unavailable rows contain multiple controls. Their item can be taller than
        // the viewport, so reach the actual descendant before asserting its full touch bounds.
        return compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
    }

    private fun tapAction(tag: String, scroll: Boolean = true) {
        actionBounds(tag, scroll)
        compose.onNodeWithTag(tag).performTouchInput { click() }
    }

    private fun actionBounds(tag: String, scroll: Boolean = true): Rect {
        val action = if (scroll) scrollToTag(tag) else compose.onNodeWithTag(tag)
        val bounds = assertVisibleAction(action)
        assertCompleteText(tag)
        if (scroll) {
            val close = assertVisibleAction(compose.onNodeWithTag("saved-jpegs-close"))
            assertFalse("Scrollable action $tag must not overlap fixed Close", bounds.overlaps(close))
        }
        return bounds
    }

    private fun assertVisibleAction(action: SemanticsNodeInteraction): Rect {
        val node = action.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        return compose.runOnIdle { assertFullyVisibleDialogAction(node) }
    }

    private fun assertCompleteText(tag: String) {
        val nodes = compose.onAllNodes(
            (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))) and
                SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        assertTrue("Expected label layout for $tag", nodes.isNotEmpty())
        nodes.forEach { node ->
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle { node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts) }
            assertTrue("Expected nonempty text layout for $tag", layouts.isNotEmpty())
            layouts.forEach { layout ->
                val lineWidths = (0 until layout.lineCount).map {
                    ceil((layout.getLineRight(it) - layout.getLineLeft(it)).toDouble()).toInt()
                }
                assertTrue("Horizontal label clipping for $tag", lineWidths.all { it <= layout.size.width })
                assertFalse("Vertical label clipping for $tag", layout.didOverflowHeight)
                assertTrue("Ellipsized label for $tag", (0 until layout.lineCount).none(layout::isLineEllipsized))
            }
        }
    }

    private fun assertSavedResult(locale: String, outcome: CameraImportHandoffOutcome) {
        val summary = requireNotNull(outcome.summary)
        scrollToTag("saved-jpegs-result").assertTextEquals(text(locale, R.string.saved_jpegs_result,
            summary.imported, summary.duplicates, summary.failed, summary.cancelled))
        assertCompleteText("saved-jpegs-result")
    }

    private fun assertSameBounds(expected: Rect, actual: Rect) {
        assertEquals("Close must stay fixed horizontally", expected.left, actual.left, 1f)
        assertEquals("Close must stay fixed vertically", expected.top, actual.top, 1f)
        assertEquals("Close must keep its full width", expected.right, actual.right, 1f)
        assertEquals("Close must keep its full height", expected.bottom, actual.bottom, 1f)
    }

    private fun withWindowOrientation(landscape: Boolean, block: () -> Unit) {
        val original = compose.activity.requestedOrientation
        val requested = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val expected = if (landscape) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
        try {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = requested }
            compose.waitUntil(10_000L) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    val decor = it.window.decorView
                    ready = it.resources.configuration.orientation == expected && decor.isLaidOut &&
                        decor.width > 0 && decor.height > 0 &&
                        if (landscape) decor.width > decor.height else decor.height > decor.width
                }
                ready
            }
            compose.runOnIdle {
                assertEquals(expected, compose.activity.resources.configuration.orientation)
                if (landscape) assertLandscapeActivityWindow(compose.activity)
            }
            block()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = original }
        }
    }

    private fun text(locale: String, resource: Int, vararg arguments: Any): String {
        val configuration = Configuration(compose.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(locale))
        }
        return compose.activity.createConfigurationContext(configuration).getString(resource, *arguments)
    }

    private class Fixture {
        val state = mutableStateOf(SavedJpegUiState(
            rows = List(100) { row(it) },
            selectedIds = setOf(DeliveredJpegId("synthetic-0")),
            unavailableIds = setOf(DeliveredJpegId("synthetic-99")),
        ))
        val handoff = mutableStateOf(CameraImportHandoffState<CameraImportHandoffSession>())
        val automaticImportActive = mutableStateOf(false)
        val visible = mutableStateOf(true)
        val calls = mutableListOf<String>()
    }

    private companion object {
        const val DIALOG = "saved-jpegs-dialog"
        const val LIST = "saved-jpegs-list"
        const val CLEAR_CONFIRM = "saved-jpegs-clear-confirm"

        fun row(index: Int) = DeliveredJpegRow(
            id = DeliveredJpegId("synthetic-$index"),
            filename = "IMG_${index.toString().padStart(3, '0')}.JPG",
            cameraModel = "Test EOS", captureTime = null, byteLength = 1_024L,
        )

        fun progress() = SavedJpegProgress(
            completedItems = 1, totalItems = 2, filename = "IMG_001.JPG",
            bytesTransferred = 512L, totalBytes = 1_024L,
        )

        fun activeHandoff(
            phase: CameraImportHandoffPhase,
            origin: CameraImportHandoffOrigin = CameraImportHandoffOrigin.SAVED_JPEG,
        ) = CameraImportHandoffState<CameraImportHandoffSession>(active = CameraImportHandoffLease(
            token = 1L, origin = origin, reservation = CameraImportStagingReservation("synthetic-dialog-only"),
            cameraGeneration = null, phase = phase,
        ))
    }
}

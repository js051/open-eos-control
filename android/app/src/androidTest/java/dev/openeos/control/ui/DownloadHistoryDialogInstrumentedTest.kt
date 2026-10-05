package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.openeos.control.R
import dev.openeos.control.data.DownloadHistoryDestination
import dev.openeos.control.data.DownloadHistoryEntry
import dev.openeos.control.data.DownloadHistoryOutcome
import dev.openeos.control.data.DownloadHistoryState
import dev.openeos.control.data.DownloadHistoryWarning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DownloadHistoryDialogInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun realLandscapeLargeTextCloseIsFullyVisibleAndReceivesOneTap() =
        verifyLargeTextAction("download-history-close")

    @Test
    fun narrow320DpLargeTextCloseIsFullyVisibleAndReceivesOneTap() =
        verifyLargeTextAction("download-history-close", narrow = true)

    @Test
    fun realLandscapeLargeTextClearAndConfirmationReceiveOneTapEach() =
        verifyLargeTextAction("download-history-clear")

    @Test
    fun narrow320DpLargeTextClearAndConfirmationReceiveOneTapEach() =
        verifyLargeTextAction("download-history-clear", narrow = true)

    @Test
    fun oneHundredLongFilenamesCanScrollToTheFinalReceipt() {
        val entries = List(100) { index ->
            entry(index).copy(filename = "SYNTHETIC_${index.toString().padStart(3, '0')}_${"W".repeat(96)}.CR3")
        }
        showHistory(history = mutableStateOf(readyState(entries)), fontScale = 2f)

        compose.onNodeWithTag("download-history-list")
            .performScrollToNode(hasTestTag("download-history-entry-receipt-99"))
        compose.onNodeWithTag("download-history-entry-receipt-99").assertIsDisplayed()
        compose.onNodeWithText(entries.last().filename).assertIsDisplayed()

        // The fixed actions remain reachable after scrolling through the entire retained history.
        assertFullyVisibleAction(compose.onNodeWithTag("download-history-clear"))
        val close = compose.onNodeWithTag("download-history-close")
        assertFullyVisibleAction(close)
        close.performTouchInput { click() }
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
    }

    @Test
    fun backClosesHistoryWithoutClearingRecords() {
        var clears = 0
        var dismissals = 0
        showHistory(onClear = { clears++ }, onDismiss = { dismissals++ })
        compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()

        pressBack()

        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, clears)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun backOnClearConfirmationCancelsOnlyConfirmation() {
        var clears = 0
        var dismissals = 0
        showHistory(onClear = { clears++ }, onDismiss = { dismissals++ })
        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithTag("download-history-clear-confirm").assertIsDisplayed()

        pressBack()

        compose.onNodeWithTag("download-history-clear-confirm").assertDoesNotExist()
        compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(0, clears)
            assertEquals(0, dismissals)
        }

        pressBack()
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, clears)
            assertEquals(1, dismissals)
        }
    }

    @Test
    fun clearRequiresConfirmationAndOnlyDispatchesTheClearCallback() {
        var clears = 0
        var dismissals = 0
        showHistory(onClear = { clears++ }, onDismiss = { dismissals++ })

        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithText(text(R.string.download_history_clear_confirmation)).assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, clears) }
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()

        compose.onNodeWithTag("download-history-clear-confirm").assertDoesNotExist()
        compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()
        // Rendering follows the supplied store state: a callback alone must not erase records.
        scrollToReceipt("receipt-0").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, clears)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun failedClearShowsGenericWarningAndRetainsRecords() {
        val original = readyState(List(100) { entry(it) })
        val history = mutableStateOf(original)
        var clears = 0
        var dismissals = 0
        showHistory(
            history = history,
            onClear = {
                clears++
                history.value = history.value.copy(warning = DownloadHistoryWarning.CLEAR_FAILED)
            },
            onDismiss = { dismissals++ },
        )

        scrollToReceipt("receipt-99").assertIsDisplayed()
        compose.onNodeWithTag("download-history-clear").performClick()
        compose.onNodeWithTag("download-history-clear-confirm-button").performClick()

        compose.onNodeWithTag("download-history-clear-confirm").assertDoesNotExist()
        // Failure must become visible by itself, even when Clear began from the oldest record.
        compose.onNodeWithText(text(R.string.download_history_warning_clear)).assertIsDisplayed()
        scrollToReceipt("receipt-0").assertIsDisplayed()
        scrollToReceipt("receipt-1").assertIsDisplayed()
        compose.onNodeWithTag("download-history-clear").assertIsEnabled()
        compose.runOnIdle {
            assertEquals(original.entries, history.value.entries)
            assertEquals(1, clears)
            assertEquals(0, dismissals)
        }
    }

    @Test
    fun loadingStateBecomesAnExplicitEmptyHistory() {
        val history = mutableStateOf(DownloadHistoryState())
        showHistory(history = history)
        compose.onNodeWithTag("download-history-list")
            .performScrollToNode(androidx.compose.ui.test.hasText(text(R.string.download_history_loading)))
        compose.onNodeWithText(text(R.string.download_history_loading)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.download_history_empty)).assertDoesNotExist()

        compose.runOnIdle { history.value = readyState(emptyList()) }

        compose.onNodeWithText(text(R.string.download_history_loading)).assertDoesNotExist()
        compose.onNodeWithTag("download-history-list")
            .performScrollToNode(androidx.compose.ui.test.hasText(text(R.string.download_history_empty)))
        compose.onNodeWithText(text(R.string.download_history_empty)).assertIsDisplayed()
        compose.onNodeWithTag("download-history-close").assertIsEnabled().performClick()
        compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
    }

    private fun verifyLargeTextAction(tag: String, narrow: Boolean = false) = withLandscapeWindow {
        var clears = 0
        var dismissals = 0
        showHistory(
            fontScale = 2f,
            modifier = if (narrow) Modifier.size(320.dp, 320.dp) else Modifier,
            onClear = { clears++ },
            onDismiss = { dismissals++ },
        )
        if (narrow) {
            compose.onNodeWithTag("download-history-dialog")
                .assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
        }

        val clearBounds = assertFullyVisibleAction(compose.onNodeWithTag("download-history-clear"), landscape = true)
        val closeBounds = assertFullyVisibleAction(compose.onNodeWithTag("download-history-close"), landscape = true)
        assertFalse("The clear and close touch targets must not overlap", clearBounds.overlaps(closeBounds))
        compose.onNodeWithTag(tag).performTouchInput { click() }

        if (tag == "download-history-clear") {
            compose.onNodeWithTag("download-history-clear-confirm").assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(0, clears)
                assertEquals(0, dismissals)
            }
            val confirm = compose.onNodeWithTag("download-history-clear-confirm-button")
            assertFullyVisibleAction(confirm)
            confirm.performTouchInput { click() }
            compose.onNodeWithTag("download-history-clear-confirm").assertDoesNotExist()
            compose.onNodeWithTag("download-history-dialog").assertIsDisplayed()
        } else {
            compose.onNodeWithTag("download-history-dialog").assertDoesNotExist()
        }
        compose.runOnIdle {
            assertEquals(if (tag == "download-history-clear") 1 else 0, clears)
            assertEquals(if (tag == "download-history-close") 1 else 0, dismissals)
        }
    }

    private fun showHistory(
        history: State<DownloadHistoryState> = mutableStateOf(readyState()),
        fontScale: Float = 1f,
        modifier: Modifier = Modifier,
        onClear: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        val visible = mutableStateOf(true)
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
                MaterialTheme {
                    if (visible.value) {
                        DownloadHistoryDialog(
                            state = history.value,
                            onClear = onClear,
                            onDismiss = {
                                visible.value = false
                                onDismiss()
                            },
                            modifier = modifier,
                        )
                    }
                }
            }
        }
    }

    private fun scrollToReceipt(receiptId: String): SemanticsNodeInteraction {
        val tag = "download-history-entry-$receiptId"
        compose.onNodeWithTag("download-history-list").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun pressBack() {
        // Send Back to the active Android window, including the nested confirmation dialog.
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitForIdle()
    }

    private fun withLandscapeWindow(test: () -> Unit) {
        val originalOrientation = compose.activity.requestedOrientation
        try {
            // A composition-only ForcedSize does not resize the separate Android Dialog window.
            compose.activityRule.scenario.onActivity {
                it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            }
            compose.waitUntil(timeoutMillis = 10_000L) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    val decor = it.window.decorView
                    ready = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        decor.isLaidOut && decor.width > decor.height && decor.height > 0
                }
                ready
            }
            test()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = originalOrientation }
        }
    }

    private fun assertFullyVisibleAction(control: SemanticsNodeInteraction, landscape: Boolean = false): Rect {
        val node = control.assertIsDisplayed().assertIsEnabled().fetchSemanticsNode()
        return compose.runOnIdle {
            // Check the full measured bounds, not just already-clipped boundsInRoot.
            val fullWindow = Rect(node.positionInWindow, node.size.toSize())
            assertContains(node.boundsInWindow, fullWindow)
            val view = (node.root as ViewRootForTest).view
            val localVisible = android.graphics.Rect()
            assertTrue("The Dialog's Android root must be visible", view.getLocalVisibleRect(localVisible))
            val location = IntArray(2).also(view::getLocationOnScreen)
            val visibleScreen = Rect(
                (localVisible.left + location[0]).toFloat(),
                (localVisible.top + location[1]).toFloat(),
                (localVisible.right + location[0]).toFloat(),
                (localVisible.bottom + location[1]).toFloat(),
            )
            val fullScreen = Rect(node.positionOnScreen, node.size.toSize())
            assertContains(visibleScreen, fullScreen)
            val minimumTouchTarget = 48f * view.resources.displayMetrics.density - 1f
            assertTrue("The action must be at least 48dp tall", fullScreen.height >= minimumTouchTarget)
            assertTrue("The action must be at least 48dp wide", fullScreen.width >= minimumTouchTarget)
            assertEquals("The Dialog must retain the requested 2x font scale", 2f, node.layoutInfo.density.fontScale, 0.01f)
            if (landscape) assertTrue("The Dialog must use a real landscape window", visibleScreen.width > visibleScreen.height)
            fullScreen
        }
    }

    private fun assertContains(outer: Rect, inner: Rect) {
        assertTrue(
            "Full measured bounds must be visible: $inner inside $outer",
            inner.width > 0 && inner.height > 0 &&
                inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
                inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1,
        )
    }

    private fun text(resource: Int): String = compose.activity.getString(resource)

    private fun readyState(entries: List<DownloadHistoryEntry> = listOf(entry(0))) =
        DownloadHistoryState(entries = entries, loading = false, writable = true)

    private fun entry(index: Int) = DownloadHistoryEntry(
        receiptId = "receipt-$index",
        filename = "SYNTHETIC_${index.toString().padStart(3, '0')}.JPG",
        destination = DownloadHistoryDestination.GALLERY,
        startedAtMillis = 1_759_680_000_000L + index * 1_000L,
        finishedAtMillis = 1_759_680_000_500L + index * 1_000L,
        outcome = DownloadHistoryOutcome.COMPLETED,
    )
}

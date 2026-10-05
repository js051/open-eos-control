package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class MediaDateRangeDialogUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun realLandscapeLargeTextApplyIsFullyVisibleAndReceivesOneTap() = verifyAction("media-date-apply")
    @Test fun realLandscapeLargeTextCancelIsFullyVisibleAndReceivesOneTap() = verifyAction("media-date-cancel")
    @Test fun realLandscapeLargeTextClearIsFullyVisibleAndReceivesOneTap() = verifyAction("media-date-clear")

    @Test fun narrow320DpLargeTextApplyRemainsReachable() = verifyAction("media-date-apply", narrow = true)
    @Test fun narrow320DpLargeTextCancelRemainsReachable() = verifyAction("media-date-cancel", narrow = true)
    @Test fun narrow320DpLargeTextClearRemainsReachable() = verifyAction("media-date-clear", narrow = true)

    private fun verifyAction(tag: String, narrow: Boolean = false) {
        val orientation = compose.activity.requestedOrientation
        try {
            // Rotate the actual Activity before installing content; Dialog has its own Android window.
            compose.activityRule.scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            compose.waitUntil(10_000L) {
                var ready = false
                compose.activityRule.scenario.onActivity {
                    ready = it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        it.window.decorView.isLaidOut && it.window.decorView.width > it.window.decorView.height
                }
                ready
            }
            val visible = mutableStateOf(true)
            val range = requireNotNull(mediaDateRangeFromInput("2026-08-14", "2026-08-14"))
            var applyCount = 0
            var cancelCount = 0
            var applied: MediaDateRange? = range
            compose.setContent {
                DialogFontScaleOverride(2f) {
                    MaterialTheme {
                        if (visible.value) MediaDateRangeDialog(range, ZoneId.of("Asia/Taipei"), {
                            applyCount++
                            applied = it
                            visible.value = false
                        }, {
                            cancelCount++
                            visible.value = false
                        }, modifier = if (narrow) Modifier.size(320.dp, 320.dp) else Modifier)
                    }
                }
            }
            compose.runOnIdle { assertLandscapeActivityWindow(compose.activity) }
            if (narrow) {
                compose.onNodeWithTag("media-date-dialog").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
            }
            val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().assertIsEnabled()
            // The narrow case intentionally has a square Dialog inside the real landscape Activity.
            assertFullBoundsVisible(action, requireLandscapeDialog = !narrow)
            action.performTouchInput { click() }
            compose.onNodeWithTag("media-date-dialog").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(if (tag == "media-date-cancel") 1 else 0, cancelCount)
                assertEquals(if (tag == "media-date-cancel") 0 else 1, applyCount)
                assertEquals(if (tag == "media-date-clear") null else range, applied)
            }
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = orientation }
        }
    }

    private fun assertFullBoundsVisible(control: SemanticsNodeInteraction, requireLandscapeDialog: Boolean) {
        val node = control.fetchSemanticsNode()
        compose.runOnIdle {
            assertFullyVisibleDialogAction(node, requireLandscapeDialog)
        }
    }
}

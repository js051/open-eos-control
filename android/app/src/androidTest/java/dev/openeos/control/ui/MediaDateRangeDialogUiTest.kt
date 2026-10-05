package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.toSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
                DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
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
            if (narrow) {
                compose.onNodeWithTag("media-date-dialog").assertWidthIsEqualTo(320.dp).assertHeightIsEqualTo(320.dp)
            }
            val action = compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            assertFullBoundsVisible(action)
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

    private fun assertFullBoundsVisible(control: SemanticsNodeInteraction) {
        val node = control.fetchSemanticsNode()
        compose.runOnIdle {
            val fullWindow = Rect(node.positionInWindow, node.size.toSize())
            contains(node.boundsInWindow, fullWindow)
            val view = (node.root as ViewRootForTest).view
            val localVisible = android.graphics.Rect()
            assertTrue(view.getLocalVisibleRect(localVisible))
            val location = IntArray(2).also(view::getLocationOnScreen)
            val visible = Rect(
                (localVisible.left + location[0]).toFloat(), (localVisible.top + location[1]).toFloat(),
                (localVisible.right + location[0]).toFloat(), (localVisible.bottom + location[1]).toFloat(),
            )
            val fullScreen = Rect(node.positionOnScreen, node.size.toSize())
            contains(visible, fullScreen)
            val density = view.resources.displayMetrics.density
            assertTrue("Full action touch target must be at least 48dp tall", fullScreen.height >= 48f * density - 1f)
            assertTrue("The dialog must be in a real landscape window", visible.width > visible.height)
        }
    }

    private fun contains(outer: Rect, inner: Rect) {
        assertTrue("Full measured bounds must be visible: $inner inside $outer", inner.width > 0 && inner.height > 0 &&
            inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
            inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1)
    }
}

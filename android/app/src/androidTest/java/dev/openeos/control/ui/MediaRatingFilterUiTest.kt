package dev.openeos.control.ui

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import dev.openeos.control.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MediaRatingFilterUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun landscapeLargeFontUnknownOptionReceivesExactlyOneTap() = verifyMenu(MediaRatingFilter.UNKNOWN)
    @Test fun narrow320DpLargeFontUnknownOptionRemainsFullyReachable() = verifyMenu(MediaRatingFilter.UNKNOWN, narrow = true)
    @Test fun narrow320DpLargeFontUnratedOptionRemainsFullyReachable() = verifyMenu(MediaRatingFilter.UNRATED, narrow = true)
    @Test fun narrow320DpLargeFontAllRatingsCanClearFilter() = verifyMenu(MediaRatingFilter.ALL, narrow = true)

    @Test fun narrow320DpLargeFontTriggerFitsActualClippedFilterRow() = inLandscape {
        val selected = mutableStateOf(MediaRatingFilter.AT_LEAST_THREE)
        var calls = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    Box(Modifier.size(320.dp, 320.dp).clipToBounds()) {
                        MediaFilterBar(MediaFilter.ALL, emptyList(), {}, {}, false, selected.value, {
                            selected.value = it
                            calls++
                        })
                    }
                }
            }
        }
        val trigger = compose.onNodeWithTag("media-rating-filter").performScrollTo().assertIsDisplayed()
        assertFullBoundsVisible(trigger)
        trigger.performTouchInput { click() }
        val option = compose.onNodeWithTag("media-rating-UNKNOWN").performScrollTo().assertIsDisplayed()
        assertFullBoundsVisible(option)
        option.performTouchInput { click() }
        compose.onNodeWithTag("media-rating-menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(MediaRatingFilter.UNKNOWN, selected.value) }
    }

    @Test fun landscapeLargeFontRatingSortOptionsAreFullyVisibleAndApplyOnce() = inLandscape {
        val selected = mutableStateOf(MediaSort.NEWEST)
        var calls = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme { MediaSortButton(selected.value, { selected.value = it; calls++ }) }
            }
        }
        listOf(MediaSort.RATING_HIGH, MediaSort.RATING_LOW).forEachIndexed { index, sort ->
            val trigger = compose.onNodeWithContentDescription(compose.activity.getString(
                R.string.media_sort_current, compose.activity.getString(selected.value.labelResource),
            )).assertIsDisplayed()
            assertFullBoundsVisible(trigger)
            trigger.performTouchInput { click() }
            val option = compose.onNodeWithText(compose.activity.getString(sort.labelResource)).performScrollTo().assertIsDisplayed()
            assertFullBoundsVisible(option)
            option.performTouchInput { click() }
            compose.runOnIdle { assertEquals(index + 1, calls); assertEquals(sort, selected.value) }
        }
    }

    private fun verifyMenu(choice: MediaRatingFilter, narrow: Boolean = false) = inLandscape {
        val selected = mutableStateOf(MediaRatingFilter.AT_LEAST_THREE)
        var calls = 0
        compose.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(2f)) {
                MaterialTheme {
                    MediaRatingFilterButton(selected.value, {
                        selected.value = it
                        calls++
                    }, menuModifier = if (narrow) Modifier.width(320.dp).heightIn(max = 320.dp) else Modifier)
                }
            }
        }
        val trigger = compose.onNodeWithTag("media-rating-filter").assertIsDisplayed()
        assertFullBoundsVisible(trigger)
        trigger.performTouchInput { click() }
        if (narrow) compose.onNodeWithTag("media-rating-menu").assertWidthIsEqualTo(320.dp)
        val option = compose.onNodeWithTag("media-rating-${choice.name}").performScrollTo().assertIsDisplayed()
        assertFullBoundsVisible(option)
        option.performTouchInput { click() }
        compose.onNodeWithTag("media-rating-menu").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, calls); assertEquals(choice, selected.value) }
    }

    private fun inLandscape(test: () -> Unit) {
        val previous = compose.activity.requestedOrientation
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
            test()
        } finally {
            compose.activityRule.scenario.onActivity { it.requestedOrientation = previous }
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
            assertTrue("The full touch target must be at least 48dp tall", fullScreen.height >= 48f * view.resources.displayMetrics.density - 1f)
        }
    }

    private fun contains(outer: Rect, inner: Rect) = assertTrue("Full measured bounds must be visible: $inner inside $outer",
        inner.width > 0 && inner.height > 0 && inner.left >= outer.left - 1 && inner.top >= outer.top - 1 &&
            inner.right <= outer.right + 1 && inner.bottom <= outer.bottom + 1)
}

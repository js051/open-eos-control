package dev.openeos.control.ui

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Give separate Dialog windows the overridden Android context, including nested AlertDialogs. */
@Composable
internal fun DialogFontScaleOverride(fontScale: Float, content: @Composable () -> Unit) {
    DeviceConfigurationOverride(DeviceConfigurationOverride.FontScale(fontScale)) {
        val currentContent by rememberUpdatedState(content)
        // Dialog uses LocalView.current.context, and its new AndroidComposeView installs its own
        // density. A composition-only FontScale override outside Dialog does not survive that.
        // AndroidView's factory receives the overridden LocalContext, so this real ComposeView
        // and every Dialog it opens obtain the requested font scale from Android resources too.
        AndroidView(factory = { context ->
            ComposeView(context).apply { setContent { currentContent() } }
        })
    }
}

/** A semantics click can run before WindowManager has foregrounded the Activity. */
internal fun ComposeTestRule.awaitForegroundActivityWindow(activity: Activity) {
    try {
        waitUntil(10_000L) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ready = activity.window.decorView.let { it.isAttachedToWindow && it.hasWindowFocus() }
            }
            ready
        }
    } catch (failure: Throwable) {
        recordWindowFocusFailure("activity", activity, activity.window.decorView)
        throw failure
    }
}

/** One native Back after its real target gains focus; never replay or dismiss by semantics. */
internal fun ComposeTestRule.pressFocusedDialogBack(activity: Activity, tag: String) {
    val node = onNodeWithTag(tag).assertIsDisplayed().fetchSemanticsNode()
    val view = (node.root as ViewRootForTest).view
    try {
        waitUntil(10_000L) {
            var ready = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                ready = view.isAttachedToWindow && view.hasWindowFocus()
            }
            ready
        }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        waitUntil(10_000L) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
    } catch (failure: Throwable) {
        recordWindowFocusFailure(tag, activity, view)
        throw failure
    }
}

private fun recordWindowFocusFailure(tag: String, activity: Activity, view: android.view.View) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.runOnMainSync {
        println("WINDOW_FOCUS_FAILURE tag=$tag activityFocus=${activity.window.decorView.hasWindowFocus()} " +
            "activityAttached=${activity.window.decorView.isAttachedToWindow} activityDestroyed=${activity.isDestroyed} " +
            "targetFocus=${view.hasWindowFocus()} targetAttached=${view.isAttachedToWindow} targetShown=${view.isShown} " +
            "targetVisibility=${view.windowVisibility} targetSize=${view.width}x${view.height}")
    }
    // This suite contains synthetic UI only. Capture the actual display, including a window
    // that might cover the intended target, while preserving the original assertion failure.
    runCatching {
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            TestStorage().openOutputFile("window-focus-$tag-${android.os.SystemClock.uptimeMillis()}.png").use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            }
        } finally { bitmap.recycle() }
    }.onFailure { println("WINDOW_FOCUS_SCREENSHOT_UNAVAILABLE ${it.javaClass.simpleName}") }
    runCatching { recordFocusedWindowOwner(tag) }.onFailure {
        println("WINDOW_FOCUS_OWNER_UNAVAILABLE tag=$tag reason=${it.cause?.javaClass?.simpleName ?: it.javaClass.simpleName}")
    }
}

/** Extra system-window evidence is limited to the explicitly synthetic emulator suite. */
private fun recordFocusedWindowOwner(tag: String) {
    if (InstrumentationRegistry.getArguments().getString("requireSimulator") != "true" ||
        Build.HARDWARE !in setOf("ranchu", "goldfish")) {
        println("WINDOW_FOCUS_OWNER_SKIPPED tag=$tag reason=not_synthetic_emulator")
        return
    }
    val stopped = AtomicBoolean(false)
    val openPipe = AtomicReference<ParcelFileDescriptor?>()
    val snapshot = FutureTask<String> {
        // AOSP dumpsys -t is a seconds-based service deadline. Keep both the command and
        // its pipe reads off main, with an outer deadline even if shell startup stalls.
        val pipe = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("dumpsys -t 1 window displays"))
        openPipe.set(pipe)
        ParcelFileDescriptor.AutoCloseInputStream(pipe).bufferedReader().use { reader ->
            if (stopped.get()) return@FutureTask "cancelled"
            // Bound memory without readNBytes(), which is unavailable on our API 26 minimum.
            val buffer = CharArray(65_536)
            var used = 0
            while (used < buffer.size && !stopped.get()) {
                val count = reader.read(buffer, used, buffer.size - used)
                if (count < 0) break
                used += count
            }
            // Log only focus identifiers, never the full display/window/app dump. On API 36
            // these fields live in the displays section, not the windows-only section.
            val fields = String(buffer, 0, used).lineSequence().map(String::trim).filter {
                it.startsWith("mCurrentFocus=") || it.startsWith("mFocusedApp=") ||
                    it.startsWith("mTopFocusedDisplayId=")
            }.take(4).joinToString(" | ") { it.take(512) }
            "scanLimitReached=${used == buffer.size} ${fields.ifEmpty { "no_focus_fields" }}"
        }
    }
    try {
        Thread(snapshot, "window-focus-diagnostics").apply { isDaemon = true; start() }
        // Emit on the calling test thread so a late worker can never log into another case.
        println("WINDOW_FOCUS_OWNER tag=$tag ${snapshot.get(2, TimeUnit.SECONDS)}")
    } finally {
        stopped.set(true)
        snapshot.cancel(true)
        runCatching { openPipe.getAndSet(null)?.close() }
    }
}

/** A deliberately square Dialog still needs a real landscape Activity behind it. Call on the UI thread. */
internal fun assertLandscapeActivityWindow(activity: Activity) {
    assertEquals(
        "The Activity must use the real landscape configuration",
        Configuration.ORIENTATION_LANDSCAPE,
        activity.resources.configuration.orientation,
    )
    val decor = activity.window.decorView
    assertTrue(
        "The Activity must have a laid-out landscape window: ${decor.width} x ${decor.height}",
        decor.isLaidOut && decor.width > decor.height && decor.height > 0,
    )
}

/** Call on the UI thread. Returns the entire touch target in screen coordinates. */
internal fun assertFullyVisibleDialogAction(node: SemanticsNode, requireLandscapeDialog: Boolean = false): Rect {
    val density = node.layoutInfo.density
    assertEquals("The Dialog must use the requested 2x font scale", 2f, density.fontScale, 0.01f)

    // Semantic layout bounds and the clickable minimum target are different: Material buttons
    // can have a 40dp visual surface with a 48dp touch target. Neither may be clipped.
    val fullWindow = Rect(node.positionInWindow, node.size.toSize())
    assertContainsDialogBounds("Full action layout", node.boundsInWindow, fullWindow)
    val fullRoot = Rect(node.positionInRoot, node.size.toSize())
    val minimumTouchSize = with(density) { 48.dp.toPx() }
    val horizontalExpansion = ((minimumTouchSize - fullRoot.width) / 2f).coerceAtLeast(0f)
    val verticalExpansion = ((minimumTouchSize - fullRoot.height) / 2f).coerceAtLeast(0f)
    val requiredTouch = Rect(
        fullRoot.left - horizontalExpansion,
        fullRoot.top - verticalExpansion,
        fullRoot.right + horizontalExpansion,
        fullRoot.bottom + verticalExpansion,
    )
    val touchRoot = node.touchBoundsInRoot
    assertContainsDialogBounds("Full 48dp action touch target", touchRoot, requiredTouch)
    assertTrue(
        "The action touch target must be at least 48dp in both dimensions: $touchRoot, density=$density",
        touchRoot.width >= minimumTouchSize - 1f && touchRoot.height >= minimumTouchSize - 1f,
    )

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
    assertContainsDialogBounds("Full action layout on screen", visibleScreen, Rect(node.positionOnScreen, node.size.toSize()))
    val touchScreen = touchRoot.translate(node.positionOnScreen - node.positionInRoot)
    assertContainsDialogBounds("Full action touch target on screen", visibleScreen, touchScreen)
    if (requireLandscapeDialog) {
        assertTrue("The Dialog must use a real landscape window", visibleScreen.width > visibleScreen.height)
    }
    return touchScreen
}

private fun assertContainsDialogBounds(description: String, outer: Rect, inner: Rect) {
    assertTrue(
        "$description must be fully visible: $inner inside $outer",
        inner.width > 0 && inner.height > 0 &&
            inner.left >= outer.left - 1f && inner.top >= outer.top - 1f &&
            inner.right <= outer.right + 1f && inner.bottom <= outer.bottom + 1f,
    )
}

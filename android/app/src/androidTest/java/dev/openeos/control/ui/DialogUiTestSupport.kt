package dev.openeos.control.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.viewinterop.AndroidView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

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

/** Call on the UI thread. Returns the entire touch target in screen coordinates. */
internal fun assertFullyVisibleDialogAction(node: SemanticsNode, landscape: Boolean = false): Rect {
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
    if (landscape) {
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

package dev.openeos.control.ui

import android.view.View
import android.view.ViewParent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

@Composable
internal fun DarkSheetSystemBarsEffect() {
    val view = LocalView.current
    val window = view.parent.findDialogWindow() ?: return

    fun applySystemBars() {
        WindowInsetsControllerCompat(window, view).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(view, window) {
        applySystemBars()
        val focusListener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) applySystemBars()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)
        onDispose {
            if (view.viewTreeObserver.isAlive) {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
        }
    }
}

private fun ViewParent?.findDialogWindow(): Window? {
    var current = this
    while (current != null) {
        if (current is DialogWindowProvider) return current.window
        current = (current as? View)?.parent
    }
    return null
}

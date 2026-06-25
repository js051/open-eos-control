package dev.openeos.control.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val AppBackground = Color(0xFF0C0F14)
val AppPanel = Color(0xFF171C24)
val AppPanelAlt = Color(0xFF202736)
val AppMonitor = Color(0xFF05070A)
val AppBorder = Color(0xFF334155)
val AppText = Color(0xFFF8FAFC)
val AppSubtleText = Color(0xFFCBD5E1)
val AppMutedText = Color(0xFF94A3B8)
val AppAccent = Color(0xFFFACC15)
val AppDanger = Color(0xFFBE123C)

val OpenEosColorScheme = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color(0xFF111318),
    secondary = Color(0xFF38BDF8),
    onSecondary = Color(0xFF082F49),
    background = AppBackground,
    onBackground = AppText,
    surface = AppPanel,
    onSurface = AppText,
    surfaceVariant = AppPanelAlt,
    onSurfaceVariant = AppSubtleText,
    error = AppDanger,
    onError = Color.White,
)

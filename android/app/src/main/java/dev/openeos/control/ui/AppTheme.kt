package dev.openeos.control.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val AppBackground = Color(0xFF101214)
val AppSurface = Color(0xFF191C1F)
val AppSurfaceHigh = Color(0xFF24282C)
val AppBorder = Color(0xFF3A4046)
val AppText = Color(0xFFF4F6F7)
val AppSubtleText = Color(0xFFB5BDC4)
val AppMutedText = Color(0xFF7D8790)
val AppAccent = Color(0xFF39C5CF)
val AppSuccess = Color(0xFF58C77B)
val AppWarning = Color(0xFFF4C95D)
val AppRecord = Color(0xFFE94B4B)

val OpenEosColorScheme = darkColorScheme(
    primary = AppAccent,
    onPrimary = Color(0xFF061113),
    secondary = AppSuccess,
    background = AppBackground,
    onBackground = AppText,
    surface = AppSurface,
    onSurface = AppText,
    surfaceVariant = AppSurfaceHigh,
    onSurfaceVariant = AppSubtleText,
    error = AppRecord,
    onError = Color.White,
)

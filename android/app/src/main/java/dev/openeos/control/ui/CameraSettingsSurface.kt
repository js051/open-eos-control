package dev.openeos.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraSettingsSurface(
    onDismissRequest: () -> Unit,
    skipPartiallyExpanded: Boolean = false,
    content: @Composable () -> Unit,
) {
    val rotated = cameraRotationQuadrant(LocalCameraControlTargetRotation.current) != 0
    if (!rotated) {
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded),
            containerColor = AppSurface,
            contentWindowInsets = { WindowInsets.safeDrawing },
        ) {
            DarkSheetSystemBarsEffect()
            content()
        }
        return
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        DarkSheetSystemBarsEffect()
        Box(
            Modifier
                .fillMaxSize()
                .background(AppSurface)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .cameraLayoutRotation()
                    .testTag("rotated-settings-surface"),
            ) {
                content()
            }
        }
    }
}

internal fun cameraRotationQuadrant(rotationDegrees: Float): Int {
    val normalized = ((rotationDegrees % 360f) + 360f) % 360f
    return ((normalized + 45f) / 90f).toInt() % 4
}

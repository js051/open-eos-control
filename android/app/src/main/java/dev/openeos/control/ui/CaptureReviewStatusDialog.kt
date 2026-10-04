package dev.openeos.control.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.openeos.control.R

/** All actions share the scroll area so they remain reachable with large text and small windows. */
@Composable
internal fun CaptureReviewStatusDialog(state: CameraUiState, actions: CameraActions, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            CameraReadableSlot(width = 344.dp, height = 400.dp, animateRotation = false) {
                Surface(shape = RoundedCornerShape(8.dp), color = AppSurface) {
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
                            .testTag("capture-review-status-dialog"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(stringResource(R.string.capture_command_acknowledged), color = AppText, fontWeight = FontWeight.Bold)
                        if (state.captureStatusReadbackFailed) {
                            Text(stringResource(R.string.capture_status_readback_failed), color = AppWarning)
                        }
                        Text(stringResource(when (state.captureReviewStatus) {
                            CaptureReviewStatus.SEARCHING -> R.string.capture_review_searching
                            CaptureReviewStatus.NOT_READY -> R.string.capture_review_not_ready
                            CaptureReviewStatus.IDLE -> if (state.captureReviewItem != null)
                                R.string.capture_review_latest_visible else R.string.capture_review_not_ready
                        }), color = AppText)
                        Text(stringResource(R.string.capture_review_explanation), color = AppSubtleText)
                        if (state.captureReviewStatus != CaptureReviewStatus.IDLE) {
                            TextButton(
                                onClick = actions.retryCaptureReview,
                                enabled = state.connected && !state.previewMode && !state.captureReviewLoading &&
                                    state.captureReviewStatus == CaptureReviewStatus.NOT_READY && !state.isBusy(CameraOperation.CAPTURE) &&
                                    !state.isBusy(CameraOperation.MEDIA),
                                modifier = Modifier.fillMaxWidth().testTag("capture-review-retry"),
                            ) { Text(stringResource(R.string.capture_review_retry)) }
                        }
                        if (state.captureReviewItem != null) {
                            TextButton(onClick = { onDismiss(); actions.openCaptureReview() },
                                modifier = Modifier.fillMaxWidth().testTag("capture-review-open-existing")) {
                                Text(stringResource(if (state.captureReviewStatus == CaptureReviewStatus.IDLE)
                                    R.string.open_latest_media else R.string.capture_review_open_previous))
                            }
                        }
                        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().testTag("capture-review-dismiss")) {
                            Text(stringResource(R.string.dismiss))
                        }
                    }
                }
            }
        }
    }
}

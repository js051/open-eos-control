package dev.openeos.control.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.openeos.control.R
import dev.openeos.control.data.ConnectionFailureReason

/** Connection choices wrap with font scaling without changing the compact camera HUD. */
@Composable
internal fun ConnectionChoiceSegment(
    firstLabel: String,
    secondLabel: String,
    firstSelected: Boolean,
    onFirst: () -> Unit,
    onSecond: () -> Unit,
    tag: String,
) {
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min)
            .background(AppSurfaceHigh, RoundedCornerShape(6.dp)).selectableGroup(),
    ) {
        listOf(Triple(firstLabel, firstSelected, onFirst), Triple(secondLabel, !firstSelected, onSecond))
            .forEachIndexed { index, (label, selected, onClick) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().heightIn(min = 48.dp)
                        .background(if (selected) AppBorder else AppSurfaceHigh, RoundedCornerShape(6.dp))
                        .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                        .testTag("$tag-$index")
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = if (selected) AppText else AppSubtleText, fontWeight = FontWeight.SemiBold)
                }
            }
    }
}

@Composable
internal fun ConnectionRecoveryCard(recovery: ConnectionRecovery, onDismiss: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(AppSurfaceHigh, RoundedCornerShape(6.dp))
            .padding(16.dp).testTag("connection-recovery"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(R.string.connection_recovery_title),
            color = AppText,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(connectionRecoveryMessage(recovery)),
            color = AppSubtleText,
            modifier = Modifier.testTag("connection-recovery-message").semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("connection-recovery-dismiss"),
        ) {
            Text(stringResource(R.string.dismiss), color = AppText)
        }
    }
}

@StringRes
private fun connectionRecoveryMessage(recovery: ConnectionRecovery): Int {
    if (recovery.target == ConnectionAttemptTarget.USB) return R.string.connection_recovery_usb
    return when (recovery.reason) {
        ConnectionFailureReason.INVALID_ADDRESS -> R.string.connection_recovery_invalid_address
        ConnectionFailureReason.AUTHENTICATION_REJECTED ->
            if (recovery.target == ConnectionAttemptTarget.CCAPI) R.string.connection_recovery_camera_auth
            else R.string.connection_recovery_bridge_auth
        ConnectionFailureReason.TLS_ERROR -> R.string.connection_recovery_tls
        ConnectionFailureReason.HOST_NOT_FOUND -> R.string.connection_recovery_host_not_found
        ConnectionFailureReason.TIMEOUT -> R.string.connection_recovery_timeout
        ConnectionFailureReason.UNREACHABLE -> R.string.connection_recovery_unreachable
        ConnectionFailureReason.ENDPOINT_NOT_FOUND,
        ConnectionFailureReason.DISCOVERY_FAILED ->
            if (recovery.target == ConnectionAttemptTarget.CCAPI) R.string.connection_recovery_ccapi_endpoint
            else R.string.connection_recovery_bridge_endpoint
        ConnectionFailureReason.HTTP_ERROR -> R.string.connection_recovery_http
        ConnectionFailureReason.UNKNOWN -> R.string.connection_recovery_unknown
    }
}

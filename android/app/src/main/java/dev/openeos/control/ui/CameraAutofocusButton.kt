package dev.openeos.control.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as LucideR
import dev.openeos.control.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraAutofocusButton(state: CameraUiState, actions: CameraActions, modifier: Modifier = Modifier) {
    val currentActions by rememberUpdatedState(actions)
    val canStart by rememberUpdatedState(state.canStartHeldAutofocus())
    val retry = state.autofocusHoldState == AutofocusHoldState.RELEASE_FAILED
    val canRetry by rememberUpdatedState(retry)
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    DisposableEffect(Unit) { onDispose { currentActions.stopHeldAutofocus() } }
    LaunchedEffect(windowFocused) { if (!windowFocused) currentActions.stopHeldAutofocus() }

    val description = stringResource(if (retry) R.string.retry_af_stop else R.string.hold_af_on)
    val phase = stringResource(when (state.autofocusHoldState) {
        AutofocusHoldState.IDLE -> R.string.af_hold_idle
        AutofocusHoldState.STARTING -> R.string.af_hold_starting
        AutofocusHoldState.HOLDING -> R.string.af_hold_active
        AutofocusHoldState.RELEASING -> R.string.af_hold_releasing
        AutofocusHoldState.RELEASE_FAILED -> R.string.af_hold_release_failed
    })
    val active = state.autofocusHoldState != AutofocusHoldState.IDLE
    val tint = if (retry) AppWarning else if (active || canStart) AppAccent else AppMutedText
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val tooltip = rememberTooltipState()
    LaunchedEffect(hovered) { if (hovered) tooltip.show() else tooltip.dismiss() }
    // Long-press tooltips must not steal the gesture that owns the remote AF command.
    Box(modifier) {
        TooltipBox(
            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
            tooltip = { PlainTooltip { Text(description) } },
            state = tooltip,
            enableUserInput = false,
        ) {
            CameraRotatingSlot(
                modifier = Modifier.size(56.dp).testTag("held-autofocus")
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .border(1.dp, if (active) tint else AppMutedText.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                    .hoverable(interactionSource)
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            if (canRetry) {
                                down.consume()
                                if (waitForUpOrCancellation() != null) currentActions.retryHeldAutofocusStop()
                            } else if (canStart) {
                                down.consume()
                                currentActions.startHeldAutofocus()
                                try { waitForUpOrCancellation()?.consume() }
                                finally { currentActions.stopHeldAutofocus() }
                            }
                        }
                    }
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        stateDescription = phase
                        role = Role.Button
                        if (!canStart && !retry && !active) disabled()
                        onClick(label = description) {
                            when {
                                retry -> currentActions.retryHeldAutofocusStop()
                                canStart -> currentActions.autofocus()
                                active -> currentActions.stopHeldAutofocus()
                                else -> return@onClick false
                            }
                            true
                        }
                    }
                    .onKeyEvent {
                        if (it.key !in setOf(Key.Enter, Key.NumPadEnter, Key.DirectionCenter, Key.Spacebar)) false
                        else {
                            if (it.type == KeyEventType.KeyUp) {
                                if (canRetry) currentActions.retryHeldAutofocusStop()
                                else if (canStart) currentActions.autofocus()
                                else currentActions.stopHeldAutofocus()
                            }
                            true
                        }
                    }
                    .focusable(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(painterResource(if (retry) LucideR.drawable.lucide_ic_square else LucideR.drawable.lucide_ic_focus),
                        contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.af_on_label), color = tint, fontSize = 10.sp, maxLines = 1)
                }
            }
        }
    }
}

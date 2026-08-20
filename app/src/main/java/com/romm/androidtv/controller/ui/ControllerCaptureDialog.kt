package com.romm.androidtv.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.romm.androidtv.R
import com.romm.androidtv.controller.capture.ControllerBindingCaptureState
import com.romm.androidtv.controller.config.BindingLabelFormatter
import com.romm.androidtv.library.ui.RommTvColors
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Stateless capture dialog for mapping a single console control to a physical binding.
 *
 * Renders a centered modal with dark RomM styling. Shows different content per
 * [captureState] per the CONTROLLER_SETTINGS.md "Capture dialog" specification.
 *
 * Per spec: does NOT render any focusable Cancel/OK button. Remote Back is the
 * safe escape, handled by the caller's existing dispatchKeyEvent interception.
 *
 * @param controlLabel The console control being mapped (e.g. "A Button").
 * @param playerLabel The player/controller label (e.g. "Controller 1").
 * @param captureState The current capture lifecycle state.
 * @param connectedDeviceName The connected controller device name, or null.
 * @param onDismiss Called when a quick Back should cancel (caller wires cancel()).
 * @param onClear Called when Back is held to clear the selected mapping.
 */
@Composable
fun ControllerCaptureDialog(
    controlLabel: String,
    playerLabel: String,
    captureState: ControllerBindingCaptureState,
    connectedDeviceName: String?,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Result and Cancelled are terminal states — the caller is expected to
    // dismiss the dialog on those. We render a neutral placeholder so the
    // composable doesn't crash if briefly shown in those states.
    val (bodyText, secondaryText, isErrorState) = when (captureState) {
        is ControllerBindingCaptureState.Idle ->
            Triple("", "", false)
        is ControllerBindingCaptureState.AwaitingNeutral ->
            Triple(
                stringResource(R.string.controller_capture_dialog_awaiting_input, playerLabel),
                stringResource(R.string.controller_capture_dialog_back_hint),
                false,
            )
        is ControllerBindingCaptureState.Capturing ->
            Triple(
                stringResource(R.string.controller_capture_dialog_awaiting_input, playerLabel),
                stringResource(R.string.controller_capture_dialog_back_hint),
                false,
            )
        is ControllerBindingCaptureState.Result ->
            Triple(
                stringResource(
                    R.string.controller_capture_dialog_result,
                    BindingLabelFormatter.label(captureState.binding),
                ),
                "",
                false,
            )
        is ControllerBindingCaptureState.Cancelled ->
            Triple("", "", false)
        is ControllerBindingCaptureState.TimedOut ->
            Triple(
                stringResource(R.string.controller_capture_dialog_timeout),
                stringResource(R.string.controller_capture_dialog_back_hint),
                true,
            )
        is ControllerBindingCaptureState.NoDeviceAssigned ->
            Triple(
                stringResource(R.string.controller_capture_dialog_no_device, playerLabel),
                stringResource(R.string.controller_capture_dialog_back_hint),
                true,
            )
    }

    // For terminal Result/Cancelled/Idle states, render a minimal placeholder.
    val shouldShowContent = captureState !is ControllerBindingCaptureState.Result &&
        captureState !is ControllerBindingCaptureState.Cancelled &&
        captureState !is ControllerBindingCaptureState.Idle

    if (!shouldShowContent) {
        // Render an invisible placeholder so the Dialog container doesn't collapse.
        Text(
            text = "",
            modifier = Modifier
                .padding(32.dp)
                .background(Color.Transparent),
        )
        return
    }

    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var clearJob by remember { mutableStateOf<Job?>(null) }
    var clearedByBackHold by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    DisposableEffect(Unit) {
        onDispose { clearJob?.cancel() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != android.view.KeyEvent.KEYCODE_BACK) {
                    return@onPreviewKeyEvent true
                }
                when (event.nativeKeyEvent.action) {
                    android.view.KeyEvent.ACTION_DOWN -> {
                        if (event.nativeKeyEvent.repeatCount == 0) {
                            clearedByBackHold = false
                            clearJob?.cancel()
                            clearJob = scope.launch {
                                delay(HOLD_BACK_TO_CLEAR_MAPPING_MILLIS)
                                onClear()
                                clearedByBackHold = true
                            }
                        }
                        true
                    }
                    android.view.KeyEvent.ACTION_UP -> {
                        clearJob?.cancel()
                        clearJob = null
                        if (!clearedByBackHold) onDismiss()
                        clearedByBackHold = false
                        true
                    }
                    else -> true
                }
            }
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth(0.46f)
                .clip(RoundedCornerShape(20.dp))
                .background(RommTvColors.NightLo)
                .padding(horizontal = 36.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.controller_capture_dialog_title, controlLabel),
                style = MaterialTheme.typography.headlineSmall,
                color = RommTvColors.TextPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = bodyText,
                style = MaterialTheme.typography.bodyMedium,
                color = if (isErrorState) Color(0xFFF44336) else RommTvColors.TextPrimary,
                textAlign = TextAlign.Center,
            )

            connectedDeviceName?.let { deviceName ->
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = stringResource(R.string.controller_capture_dialog_connected_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = RommTvColors.TextSecondary,
                    )
                    Text(
                        text = deviceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = RommTvColors.Romm300,
                    )
                }
            }

            if (secondaryText.isNotBlank()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = secondaryText,
                    style = MaterialTheme.typography.bodySmall,
                    color = RommTvColors.TextSecondary,
                    textAlign = TextAlign.Center,
                )
            }
        }

    }
}

private const val HOLD_BACK_TO_CLEAR_MAPPING_MILLIS = 600L

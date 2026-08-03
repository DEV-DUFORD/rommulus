package com.romm.androidtv.controller.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.capture.ControllerBindingCaptureState
import com.romm.androidtv.library.ui.RommTvColors

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
 * @param onDismiss Called when the remote Back should cancel (caller wires cancel()).
 */
@Composable
fun ControllerCaptureDialog(
    controlLabel: String,
    playerLabel: String,
    captureState: ControllerBindingCaptureState,
    connectedDeviceName: String?,
    @Suppress("UNUSED_PARAMETER") onDismiss: () -> Unit,
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
                "Press a button or move a stick on the controller for $playerLabel.",
                "Press Back on the remote to cancel.",
                false,
            )
        is ControllerBindingCaptureState.Capturing ->
            Triple(
                "Press a button or move a stick on the controller for $playerLabel.",
                "Press Back on the remote to cancel.",
                false,
            )
        is ControllerBindingCaptureState.Result ->
            Triple(
                "Captured: ${com.romm.androidtv.controller.config.BindingLabelFormatter.label(captureState.binding)}",
                "",
                false,
            )
        is ControllerBindingCaptureState.Cancelled ->
            Triple("", "", false)
        is ControllerBindingCaptureState.TimedOut ->
            Triple("No input detected", "Press Back on the remote to cancel.", true)
        is ControllerBindingCaptureState.NoDeviceAssigned ->
            Triple(
                "Connect $playerLabel to remap inputs",
                "Press Back on the remote to cancel.",
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

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(RommTvColors.NightLo)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---- Title ----
        Text(
            text = "Map $controlLabel",
            style = MaterialTheme.typography.headlineSmall,
            color = RommTvColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ---- Body ----
        Text(
            text = bodyText,
            style = MaterialTheme.typography.bodyMedium,
            color = if (isErrorState) Color(0xFFf44336) else RommTvColors.TextPrimary,
            textAlign = TextAlign.Center,
        )

        // ---- Connected device name ----
        connectedDeviceName?.let { deviceName ->
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Connected: ",
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

        // ---- Secondary text ----
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

package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * A circular/rounded pressable button with a text label.
 *
 * Uses independent pointer tracking via [pointerInput] so that holding the button
 * sends continuous press events and release/cancellation sends release events.
 * Multiple [TouchButton]s can be pressed simultaneously without interfering with each other.
 *
 * @param label the text displayed on the button
 * @param onPressChange callback invoked with `true` on press and `false` on release/cancel
 * @param modifier optional modifier for layout customization
 */
@Composable
fun TouchButton(
    label: String,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }

    val backgroundColor = when {
        isPressed -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)
    }

    val borderColor = if (isPressed) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(backgroundColor)
            .border(2.dp, borderColor, CircleShape)
            .pointerInput(label) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPressed = true
                        onPressChange(true)

                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                        }
                        isPressed = false
                        onPressChange(false)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Internal helper: a pressable zone that emits press/release for a specific [LogicalControl].
 * Used by [TouchDpad] and [TouchAnalogStick] for individual directional zones.
 */
@Composable
internal fun TouchZone(
    logicalControl: com.romm.androidtv.controller.model.LogicalControl,
    onButtonChange: (com.romm.androidtv.controller.model.LogicalControl, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
) {
    var isPressed by remember { mutableStateOf(false) }

    val activeBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val activeBorder = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isPressed) activeBg else backgroundColor)
            .border(2.dp, if (isPressed) activeBorder else borderColor, CircleShape)
            .pointerInput(logicalControl) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPressed = true
                        onButtonChange(logicalControl, true)

                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                        }
                        isPressed = false
                        onButtonChange(logicalControl, false)
                    }
                }
            },
    )
}

/**
 * Internal helper: a pressable zone for axis values used by [TouchAnalogStick].
 */
@Composable
internal fun TouchAxisZone(
    logicalControl: com.romm.androidtv.controller.model.LogicalControl,
    onAxisChange: (com.romm.androidtv.controller.model.LogicalControl, Float) -> Unit,
    modifier: Modifier = Modifier,
    backgroundColor: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f),
    borderColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
    defaultValue: Float = 0f,
) {
    var isPressed by remember { mutableStateOf(false) }

    val activeBg = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
    val activeBorder = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(if (isPressed) activeBg else backgroundColor)
            .border(2.dp, if (isPressed) activeBorder else borderColor, CircleShape)
            .pointerInput(logicalControl) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        down.consume()
                        isPressed = true
                        onAxisChange(logicalControl, 1f)

                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            up.consume()
                        }
                        isPressed = false
                        onAxisChange(logicalControl, defaultValue)
                    }
                }
            },
    )
}

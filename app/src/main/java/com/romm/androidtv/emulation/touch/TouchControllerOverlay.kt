package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControlDescriptor
import com.romm.androidtv.controller.config.InputKind
import com.romm.androidtv.controller.model.LogicalControl

/**
 * A full on-screen touch controller overlay that lays out controls from a
 * [CoreControlDescriptor] list.
 *
 * Layout strategy:
 * - D-pad controls (DPAD) → [TouchDpad] on the LEFT side.
 * - Analog sticks (ANALOG_STICK) → [TouchAnalogStick] widget(s).
 *   Left stick lower-left, right stick lower-right if present.
 * - Buttons (BUTTON) → [TouchButton] on the RIGHT side.
 * - Triggers (TRIGGER) → [TouchButton] near the top.
 * - Start/Select → near the center bottom.
 *
 * The overlay respects display cutouts, rounded corners, gesture insets,
 * and fold/hinge bounds via [Modifier.safeDrawingPadding] and
 * [Modifier.windowInsetsPadding(WindowInsets.systemGestures)].
 *
 * A small "Pause" [TouchButton] is placed top-right for menu access.
 *
 * @param controls the list of [CoreControlDescriptor] to render
 * @param onButtonChange callback for button press/release events
 * @param onAxisChange callback for axis value changes
 * @param onPause callback invoked when the pause button is tapped
 * @param modifier optional modifier for layout customization
 */
@Composable
fun TouchControllerOverlay(
    controls: List<CoreControlDescriptor>,
    onButtonChange: (LogicalControl, Boolean) -> Unit,
    onAxisChange: (LogicalControl, Float) -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Categorize controls
    val dpadControls = controls.filter { it.inputKind == InputKind.DPAD }
    val stickControls = controls.filter { it.inputKind == InputKind.ANALOG_STICK }
    val buttonControls = controls.filter { it.inputKind == InputKind.BUTTON }
    val triggerControls = controls.filter { it.inputKind == InputKind.TRIGGER }

    // Separate left and right sticks
    val leftStickDescriptors = stickControls.filter {
        it.target == LogicalControl.AXIS_LX || it.target == LogicalControl.AXIS_LY
    }
    val rightStickDescriptors = stickControls.filter {
        it.target == LogicalControl.AXIS_RX || it.target == LogicalControl.AXIS_RY
    }

    // Identify start/select buttons
    val startSelectButtons = buttonControls.filter {
        it.target == LogicalControl.BUTTON_START || it.target == LogicalControl.BUTTON_SELECT
    }
    val otherButtons = buttonControls.filter {
        it.target != LogicalControl.BUTTON_START && it.target != LogicalControl.BUTTON_SELECT
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .windowInsetsPadding(WindowInsets.systemGestures)
            .background(Color.Transparent),
    ) {
        // D-Pad: LEFT side, vertically centered
        if (dpadControls.isNotEmpty()) {
            TouchDpad(
                onDirectionChange = onButtonChange,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp),
            )
        }

        // Left analog stick: lower-left
        if (leftStickDescriptors.isNotEmpty()) {
            TouchAnalogStick(
                onAxisChange = onAxisChange,
                xAxis = LogicalControl.AXIS_LX,
                yAxis = LogicalControl.AXIS_LY,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 32.dp, bottom = 48.dp),
            )
        }

        // Right analog stick: lower-right
        if (rightStickDescriptors.isNotEmpty()) {
            TouchAnalogStick(
                onAxisChange = onAxisChange,
                xAxis = LogicalControl.AXIS_RX,
                yAxis = LogicalControl.AXIS_RY,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 32.dp, bottom = 48.dp),
            )
        }

        // Triggers: top row
        if (triggerControls.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                triggerControls.forEach { descriptor ->
                    TouchButton(
                        label = descriptor.label,
                        onPressChange = { pressed -> onButtonChange(descriptor.target, pressed) },
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }

        // Action buttons: RIGHT side
        if (otherButtons.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                otherButtons.forEach { descriptor ->
                    TouchButton(
                        label = descriptor.label,
                        onPressChange = { pressed -> onButtonChange(descriptor.target, pressed) },
                        modifier = Modifier.size(52.dp),
                    )
                }
            }
        }

        // Start/Select: center bottom
        if (startSelectButtons.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                startSelectButtons.forEach { descriptor ->
                    TouchButton(
                        label = descriptor.label,
                        onPressChange = { pressed -> onButtonChange(descriptor.target, pressed) },
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }

        // Pause button: top-right corner
        TouchButton(
            label = "Pause",
            onPressChange = { pressed ->
                if (pressed) onPause()
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 16.dp, top = 16.dp)
                .size(48.dp),
        )
    }
}

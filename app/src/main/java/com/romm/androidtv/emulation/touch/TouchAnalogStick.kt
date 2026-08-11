package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.model.LogicalControl

/**
 * An analog joystick with a movable knob.
 *
 * Tracks pointer drag within the base circle and computes normalized X/Y
 * values in [-1f, 1f] from the knob displacement relative to the base center.
 * The knob is clamped to the base radius.
 *
 * During drag, [onAxisChange] is called continuously for both LX and LY axes.
 * On release/cancel, both axes are reset to 0f.
 *
 * @param onAxisChange callback invoked with the [LogicalControl] axis and value in [-1f, 1f]
 * @param xAxis logical horizontal axis emitted by this stick
 * @param yAxis logical vertical axis emitted by this stick
 * @param modifier optional modifier for layout customization
 */
@Composable
fun TouchAnalogStick(
    onAxisChange: (LogicalControl, Float) -> Unit,
    xAxis: LogicalControl = LogicalControl.AXIS_LX,
    yAxis: LogicalControl = LogicalControl.AXIS_LY,
    modifier: Modifier = Modifier,
) {
    val baseSizeDp = 112.dp
    val knobSizeDp = 48.dp

    var knobOffset by remember { mutableStateOf(Offset.Zero) }
    var baseWidthPx by remember { mutableStateOf(0f) }
    var baseHeightPx by remember { mutableStateOf(0f) }

    val baseColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.25f)
    val baseBorder = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val knobColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
    val knobBorder = MaterialTheme.colorScheme.onPrimary

    Box(
        modifier = modifier
            .size(baseSizeDp)
            .onSizeChanged { size ->
                baseWidthPx = size.width.toFloat()
                baseHeightPx = size.height.toFloat()
            }
            .clip(CircleShape)
            .background(baseColor)
            .border(2.dp, baseBorder, CircleShape),
    ) {
        val knobPx = knobSizeDp.value * (baseWidthPx / baseSizeDp.value)
        val centerX = baseWidthPx / 2f
        val centerY = baseHeightPx / 2f
        val maxDisplacement = (baseWidthPx / 2f) - (knobPx / 2f)

        Box(
            modifier = Modifier
                .size(knobSizeDp)
                .offset {
                    IntOffset(
                        (centerX + knobOffset.x - knobPx / 2f).toInt(),
                        (centerY + knobOffset.y - knobPx / 2f).toInt(),
                    )
                }
                .clip(CircleShape)
                .background(knobColor)
                .border(2.dp, knobBorder, CircleShape),
        )

        // Pointer input for drag tracking
        Box(
            modifier = Modifier
                .size(baseSizeDp)
                .pointerInput(centerX, centerY, maxDisplacement, xAxis, yAxis) {
                    trackStick(
                        centerX = centerX,
                        centerY = centerY,
                        maxDisplacement = maxDisplacement,
                        xAxis = xAxis,
                        yAxis = yAxis,
                        onOffsetChange = { offset -> knobOffset = offset },
                        onAxisChange = onAxisChange,
                    )
                },
        )
    }
}

private suspend fun PointerInputScope.trackStick(
    centerX: Float,
    centerY: Float,
    maxDisplacement: Float,
    xAxis: LogicalControl,
    yAxis: LogicalControl,
    onOffsetChange: (Offset) -> Unit,
    onAxisChange: (LogicalControl, Float) -> Unit,
) {
    awaitPointerEventScope {
        while (true) {
            val down = awaitFirstDown(requireUnconsumed = false)
            down.consume()

            var currentOffset = down.position - Offset(centerX, centerY)
            currentOffset = clampOffset(currentOffset, maxDisplacement)
            onOffsetChange(currentOffset)
            emitAxes(onAxisChange, xAxis, yAxis, currentOffset, maxDisplacement)

            // Track drag movements
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.pressed }
                if (change == null) break
                if (change.position.x != change.previousPosition.x || change.position.y != change.previousPosition.y) {
                    val pos = change.position - Offset(centerX, centerY)
                    val clamped = clampOffset(pos, maxDisplacement)
                    onOffsetChange(clamped)
                    emitAxes(onAxisChange, xAxis, yAxis, clamped, maxDisplacement)
                    change.consume()
                }
            }

            // Release: reset knob and axes
            onOffsetChange(Offset.Zero)
            onAxisChange(xAxis, 0f)
            onAxisChange(yAxis, 0f)
        }
    }
}

private fun clampOffset(offset: Offset, maxDisplacement: Float): Offset {
    if (maxDisplacement <= 0f) return Offset.Zero
    val distance = offset.getDistance()
    return if (distance > maxDisplacement) {
        offset * (maxDisplacement / distance)
    } else offset
}

private fun emitAxes(
    onAxisChange: (LogicalControl, Float) -> Unit,
    xAxis: LogicalControl,
    yAxis: LogicalControl,
    offset: Offset,
    maxDisplacement: Float,
) {
    if (maxDisplacement <= 0f) {
        return
    }
    val normX = (offset.x / maxDisplacement).coerceIn(-1f, 1f)
    val normY = (offset.y / maxDisplacement).coerceIn(-1f, 1f)
    onAxisChange(xAxis, normX)
    onAxisChange(yAxis, normY)
}

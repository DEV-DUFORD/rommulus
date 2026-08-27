package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.LogicalControl
import kotlin.math.abs
import kotlin.math.atan2

data class TouchPoint(val x: Float, val y: Float)

data class TouchBounds(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
) {
    fun normalized(point: TouchPoint): TouchPoint = TouchPoint(
        x = (point.x - centerX) / (width / 2f),
        y = (point.y - centerY) / (height / 2f),
    )
}

sealed interface TouchHitRegion {
    val bounds: TouchBounds

    data class Button(
        override val bounds: TouchBounds,
        val target: LogicalControl,
        val shape: TouchControlShape,
    ) : TouchHitRegion

    data class Dpad(
        override val bounds: TouchBounds,
        val up: LogicalControl,
        val down: LogicalControl,
        val left: LogicalControl,
        val right: LogicalControl,
    ) : TouchHitRegion

    data class Stick(
        override val bounds: TouchBounds,
        val xAxis: LogicalControl,
        val yAxis: LogicalControl,
    ) : TouchHitRegion

    data class Menu(override val bounds: TouchBounds) : TouchHitRegion
}

data class TouchGestureFrame(
    val buttons: Set<LogicalControl>,
    val axes: Map<LogicalControl, Float>,
    val menuPressed: Boolean,
)

fun resolveTouchGestureFrame(
    regions: List<TouchHitRegion>,
    pointers: List<TouchPoint>,
): TouchGestureFrame {
    val buttons = mutableSetOf<LogicalControl>()
    val axes = mutableMapOf<LogicalControl, Float>()
    var menuPressed = false

    for (region in regions) {
        when (region) {
            is TouchHitRegion.Button -> {
                if (pointers.any { region.containsButton(it) }) buttons += region.target
            }
            is TouchHitRegion.Dpad -> {
                val point = pointers.firstOrNull { region.bounds.contains(it, expansion = 1.12f) }
                if (point != null) {
                    val normalized = region.bounds.normalized(point)
                    val horizontalMagnitude = abs(normalized.x)
                    val verticalMagnitude = abs(normalized.y)
                    if (maxOf(horizontalMagnitude, verticalMagnitude) >= DPAD_DIRECTION_THRESHOLD) {
                        val angle = atan2(verticalMagnitude, horizontalMagnitude)
                        if (angle <= DIAGONAL_MAX_HORIZONTAL_ANGLE) {
                            buttons += if (normalized.x < 0f) region.left else region.right
                        } else if (angle >= DIAGONAL_MIN_VERTICAL_ANGLE) {
                            buttons += if (normalized.y < 0f) region.up else region.down
                        } else {
                            buttons += if (normalized.x < 0f) region.left else region.right
                            buttons += if (normalized.y < 0f) region.up else region.down
                        }
                    }
                }
            }
            is TouchHitRegion.Stick -> {
                val point = pointers.firstOrNull { region.bounds.contains(it, expansion = 1.18f) }
                if (point != null) {
                    val normalized = region.bounds.normalized(point)
                    axes[region.xAxis] = normalized.x.coerceIn(-1f, 1f)
                    axes[region.yAxis] = normalized.y.coerceIn(-1f, 1f)
                }
            }
            is TouchHitRegion.Menu -> {
                menuPressed = pointers.any { region.bounds.contains(it, expansion = 1.08f) }
            }
        }
    }
    return TouchGestureFrame(buttons, axes, menuPressed)
}

private const val DPAD_DIRECTION_THRESHOLD = 0.18f
private val DIAGONAL_MAX_HORIZONTAL_ANGLE = (Math.PI * 3.0 / 16.0).toFloat()
private val DIAGONAL_MIN_VERTICAL_ANGLE = (Math.PI * 5.0 / 16.0).toFloat()
// A thumb centered in the visual gap between adjacent face buttons should chord both.
private const val BUTTON_HIT_EXPANSION = 1.70f

private fun TouchHitRegion.Button.containsButton(point: TouchPoint): Boolean {
    val normalized = bounds.normalized(point)
    return when (shape) {
        TouchControlShape.CIRCLE ->
            normalized.x * normalized.x + normalized.y * normalized.y <=
                BUTTON_HIT_EXPANSION * BUTTON_HIT_EXPANSION
        TouchControlShape.ROUNDED_RECT ->
            abs(normalized.x) <= BUTTON_HIT_EXPANSION &&
                abs(normalized.y) <= BUTTON_HIT_EXPANSION
    }
}

private fun TouchBounds.contains(point: TouchPoint, expansion: Float): Boolean {
    val normalized = normalized(point)
    return abs(normalized.x) <= expansion && abs(normalized.y) <= expansion
}

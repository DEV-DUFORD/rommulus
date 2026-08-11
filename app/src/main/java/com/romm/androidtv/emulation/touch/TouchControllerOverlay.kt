package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.config.CoreControllerProfile
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.platform.rememberDeviceProfile
import androidx.window.layout.FoldingFeature

/**
 * Renders versioned layout data. Console control IDs are resolved through the immutable core
 * profile at render time; visual overrides therefore cannot alter logical input mappings.
 */
@Composable
fun TouchControllerOverlay(
    profile: CoreControllerProfile,
    onButtonChange: (LogicalControl, Boolean) -> Unit,
    onAxisChange: (LogicalControl, Float) -> Unit,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
    layoutOverride: TouchLayoutOverrideDocument? = null,
) {
    val deviceProfile = rememberDeviceProfile()
    if (!deviceProfile.hasTouchscreen) return

    val layout = remember(profile.coreId, layoutOverride) {
        DefaultTouchLayouts.forProfile(profile, layoutOverride)
    }
    val resolvedControls = remember(profile, layout) {
        layout.controls.filter { it.visible }.map { it.resolve(profile) }
    }
    val density = LocalDensity.current
    var visualFrame by remember {
        mutableStateOf(TouchGestureFrame(emptySet(), emptyMap(), menuPressed = false))
    }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize().displayCutoutPadding(),
    ) {
        val hitRegions = remember(
            resolvedControls,
            maxWidth,
            maxHeight,
            density,
            deviceProfile.foldingFeature,
        ) {
            resolvedControls.map { resolved ->
                val definition = resolved.definition
                val center = avoidFold(definition.center, deviceProfile.foldingFeature)
                val size = renderedSize(definition, maxWidth, maxHeight)
                val bounds = with(density) {
                    TouchBounds(
                        centerX = (maxWidth * center.x).toPx(),
                        centerY = (maxHeight * center.y).toPx(),
                        width = size.width.toPx(),
                        height = size.height.toPx(),
                    )
                }
                when (resolved) {
                    is ResolvedTouchControl.Button -> TouchHitRegion.Button(
                        bounds = bounds,
                        target = resolved.descriptor.target,
                        shape = resolved.definition.shape,
                    )
                    is ResolvedTouchControl.Dpad -> TouchHitRegion.Dpad(
                        bounds = bounds,
                        up = resolved.up.target,
                        down = resolved.down.target,
                        left = resolved.left.target,
                        right = resolved.right.target,
                    )
                    is ResolvedTouchControl.Stick -> TouchHitRegion.Stick(
                        bounds = bounds,
                        xAxis = resolved.xAxis.target,
                        yAxis = resolved.yAxis.target,
                    )
                    is ResolvedTouchControl.Menu -> TouchHitRegion.Menu(bounds)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(hitRegions) {
                    awaitPointerEventScope {
                        var previousButtons = emptySet<LogicalControl>()
                        var previousAxes = emptyMap<LogicalControl, Float>()
                        var previousMenuPressed = false
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pointers = event.changes
                                .filter { it.pressed }
                                .map { TouchPoint(it.position.x, it.position.y) }
                            val frame = resolveTouchGestureFrame(hitRegions, pointers)
                            visualFrame = frame

                            (previousButtons - frame.buttons).forEach {
                                onButtonChange(it, false)
                            }
                            (frame.buttons - previousButtons).forEach {
                                onButtonChange(it, true)
                            }
                            (previousAxes.keys + frame.axes.keys).forEach { axis ->
                                val previous = previousAxes[axis] ?: 0f
                                val current = frame.axes[axis] ?: 0f
                                if (previous != current) onAxisChange(axis, current)
                            }
                            if (frame.menuPressed && !previousMenuPressed) onPause()

                            previousButtons = frame.buttons
                            previousAxes = frame.axes
                            previousMenuPressed = frame.menuPressed
                            event.changes.forEach { if (it.pressed) it.consume() }
                        }
                    }
                },
        )

        resolvedControls.forEach { resolved ->
            key(resolved.definition.visualId) {
                val definition = resolved.definition
                val center = avoidFold(definition.center, deviceProfile.foldingFeature)
                val controlSize = renderedSize(definition, maxWidth, maxHeight)
                val x = (maxWidth * center.x - controlSize.width / 2)
                    .coerceIn(0.dp, (maxWidth - controlSize.width).coerceAtLeast(0.dp))
                val y = (maxHeight * center.y - controlSize.height / 2)
                    .coerceIn(0.dp, (maxHeight - controlSize.height).coerceAtLeast(0.dp))

                Box(
                    modifier = Modifier
                        .offset(x, y)
                        .size(controlSize.width, controlSize.height),
                ) {
                    when (resolved) {
                        is ResolvedTouchControl.Button -> TouchButton(
                            label = resolved.definition.displayLabel ?: resolved.descriptor.label,
                            onPressChange = { pressed ->
                                onButtonChange(resolved.descriptor.target, pressed)
                            },
                            shape = resolved.definition.shape,
                            opacity = resolved.definition.opacity,
                            inputEnabled = false,
                            pressedOverride = resolved.descriptor.target in visualFrame.buttons,
                        )
                        is ResolvedTouchControl.Dpad -> TouchDpad(
                            directions = DpadLogicalControls(
                                up = resolved.up.target,
                                down = resolved.down.target,
                                left = resolved.left.target,
                                right = resolved.right.target,
                            ),
                            onDirectionChange = onButtonChange,
                            opacity = resolved.definition.opacity,
                            inputEnabled = false,
                            pressedDirections = visualFrame.buttons,
                        )
                        is ResolvedTouchControl.Stick -> TouchAnalogStick(
                            onAxisChange = onAxisChange,
                            xAxis = resolved.xAxis.target,
                            yAxis = resolved.yAxis.target,
                            opacity = resolved.definition.opacity,
                            inputEnabled = false,
                            xValueOverride = visualFrame.axes[resolved.xAxis.target] ?: 0f,
                            yValueOverride = visualFrame.axes[resolved.yAxis.target] ?: 0f,
                        )
                        is ResolvedTouchControl.Menu -> TouchButton(
                            label = "Menu",
                            onPressChange = { pressed -> if (pressed) onPause() },
                            shape = TouchControlShape.ROUNDED_RECT,
                            opacity = resolved.definition.opacity,
                            inputEnabled = false,
                            pressedOverride = visualFrame.menuPressed,
                        )
                    }
                }
            }
        }
    }
}

internal data class RenderedTouchSize(val width: Dp, val height: Dp)

internal fun renderedSize(
    definition: TouchControlDefinition,
    availableWidth: Dp,
    availableHeight: Dp,
): RenderedTouchSize {
    val requestedWidth = availableWidth * definition.size.width
    val requestedHeight = availableHeight * definition.size.height
    return when (definition) {
        is TouchControlDefinition.Dpad -> {
            val side = minOf(requestedWidth, requestedHeight).coerceIn(144.dp, 220.dp)
            RenderedTouchSize(side, side)
        }
        is TouchControlDefinition.Stick -> {
            val side = minOf(requestedWidth, requestedHeight).coerceIn(112.dp, 156.dp)
            RenderedTouchSize(side, side)
        }
        is TouchControlDefinition.Button -> {
            if (definition.shape == TouchControlShape.CIRCLE) {
                val side = minOf(requestedWidth, requestedHeight).coerceIn(48.dp, 88.dp)
                RenderedTouchSize(side, side)
            } else {
                RenderedTouchSize(
                    requestedWidth.coerceIn(64.dp, 120.dp),
                    requestedHeight.coerceIn(48.dp, 64.dp),
                )
            }
        }
        is TouchControlDefinition.Menu -> RenderedTouchSize(
            requestedWidth.coerceIn(64.dp, 104.dp),
            requestedHeight.coerceIn(48.dp, 64.dp),
        )
    }
}

private fun avoidFold(
    center: NormalizedPoint,
    foldingFeature: FoldingFeature?,
): NormalizedPoint {
    val fold = foldingFeature ?: return center
    if (!fold.isSeparating) return center
    val vertical = fold.bounds.height() > fold.bounds.width()
    return if (vertical && center.x in 0.44f..0.56f) {
        center.copy(x = if (center.x < 0.5f) 0.38f else 0.62f)
    } else if (!vertical && center.y in 0.44f..0.56f) {
        center.copy(y = if (center.y < 0.5f) 0.36f else 0.64f)
    } else {
        center
    }
}

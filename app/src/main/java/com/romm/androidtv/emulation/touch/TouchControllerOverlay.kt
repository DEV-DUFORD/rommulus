package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
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

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .windowInsetsPadding(WindowInsets.systemGestures),
    ) {
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
                        )
                        is ResolvedTouchControl.Stick -> TouchAnalogStick(
                            onAxisChange = onAxisChange,
                            xAxis = resolved.xAxis.target,
                            yAxis = resolved.yAxis.target,
                            opacity = resolved.definition.opacity,
                        )
                        is ResolvedTouchControl.Menu -> TouchButton(
                            label = "Menu",
                            onPressChange = { pressed -> if (pressed) onPause() },
                            shape = TouchControlShape.ROUNDED_RECT,
                            opacity = resolved.definition.opacity,
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
        is TouchControlDefinition.Dpad -> RenderedTouchSize(
            requestedWidth.coerceIn(144.dp, 220.dp),
            requestedHeight.coerceIn(144.dp, 220.dp),
        )
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

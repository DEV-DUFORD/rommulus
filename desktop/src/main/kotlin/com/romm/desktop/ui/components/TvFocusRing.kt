package com.romm.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.romm.desktop.ui.components.LocalRommulusColors

private val DefaultFocusShape = RoundedCornerShape(8.dp)

// ---------------------------------------------------------------------------
// Focus ring modifier
// ---------------------------------------------------------------------------

/**
 * Adds a 3.dp accent border when [isFocused] is true, using the current
 * theme's Romm500 color. No-op when [isFocused] is false.
 */
fun Modifier.tvFocusRing(
    isFocused: Boolean,
    shape: Shape = DefaultFocusShape,
): Modifier = composed {
    val palette = LocalRommulusColors.current
    if (isFocused) {
        border(width = 3.dp, color = palette.romm500, shape = shape)
    } else {
        this
    }
}

/**
 * Auto-tracking variant: observes focus state changes and applies the ring
 * only when the component is both focused and [enabled].
 */
fun Modifier.tvFocusRing(
    shape: Shape = DefaultFocusShape,
    enabled: Boolean = true,
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = enabled && it.isFocused }
        .tvFocusRing(isFocused = isFocused && enabled, shape = shape)
}

// ---------------------------------------------------------------------------
// Button focus ring (drawn via drawWithContent for rounded-rect stroke)
// ---------------------------------------------------------------------------

/**
 * Draws a rounded-rect stroke border around the content when focused.
 * Uses [drawWithContent] so the border is clipped to the button's shape
 * without affecting layout.
 */
fun Modifier.tvButtonFocus(
    isFocused: Boolean,
    enabled: Boolean = true,
): Modifier = composed {
    val palette = LocalRommulusColors.current
    if (isFocused && enabled) {
        drawWithContent {
            drawContent()
            val strokeWidth = 3.dp.toPx()
            val inset = strokeWidth / 2f
            drawRoundRect(
                color = palette.romm300,
                topLeft = Offset(inset, inset),
                size = Size(size.width - strokeWidth, size.height - strokeWidth),
                cornerRadius = CornerRadius((size.height - strokeWidth) / 2f),
                style = Stroke(width = strokeWidth),
            )
        }
    } else {
        this
    }
}

/** Auto-tracking variant for [tvButtonFocus]. */
fun Modifier.tvButtonFocus(enabled: Boolean = true): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = enabled && it.isFocused }
        .tvButtonFocus(isFocused = isFocused, enabled = enabled)
}

// ---------------------------------------------------------------------------
// Themed button / switch composables
// ---------------------------------------------------------------------------

/**
 * A Material3 [Button] with a focus border drawn in the theme's Romm300
 * accent color. No testTag — desktop uses keyboard/mouse focus, not
 * accessibility test tags.
 */
@Composable
fun TvButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        border = if (isFocused) BorderStroke(3.dp, LocalRommulusColors.current.romm300) else null,
        content = content,
    )
}

/**
 * A Material3 [OutlinedButton] with a focus-responsive border: 3.dp
 * Romm300 when focused, 1.dp muted secondary otherwise.
 */
@Composable
fun TvOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        border = BorderStroke(
            width = if (isFocused) 3.dp else 1.dp,
            color = if (isFocused) LocalRommulusColors.current.romm300 else LocalRommulusColors.current.textSecondary.copy(alpha = 0.7f),
        ),
        content = content,
    )
}

/**
 * A [Switch] with a focus border drawn around its track. The border color
 * is the theme's Romm300 accent when focused, transparent otherwise.
 */
@Composable
fun TvSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val palette = LocalRommulusColors.current
    val borderColor = if (isFocused) palette.romm300 else Color.Transparent
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        interactionSource = interactionSource,
        colors = SwitchDefaults.colors(
            checkedBorderColor = borderColor,
            uncheckedBorderColor = borderColor,
        ),
    )
}

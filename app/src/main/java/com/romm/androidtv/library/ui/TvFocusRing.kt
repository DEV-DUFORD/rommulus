package com.romm.androidtv.library.ui

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

private val DefaultFocusShape = RoundedCornerShape(8.dp)

fun Modifier.tvFocusRing(
    isFocused: Boolean,
    shape: Shape = DefaultFocusShape,
): Modifier = if (isFocused) {
    border(width = 3.dp, color = RommTvColors.Romm500, shape = shape)
} else {
    this
}

fun Modifier.tvFocusRing(
    shape: Shape = DefaultFocusShape,
    enabled: Boolean = true,
): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = enabled && it.isFocused }
        .tvFocusRing(isFocused = isFocused && enabled, shape = shape)
}

fun Modifier.tvButtonFocus(
    isFocused: Boolean,
    enabled: Boolean = true,
): Modifier = if (isFocused && enabled) {
    drawWithContent {
        drawContent()
        val strokeWidth = 3.dp.toPx()
        val inset = strokeWidth / 2f
        drawRoundRect(
            color = RommTvColors.Romm300,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokeWidth, size.height - strokeWidth),
            cornerRadius = CornerRadius((size.height - strokeWidth) / 2f),
            style = Stroke(width = strokeWidth),
        )
    }
} else {
    this
}

fun Modifier.tvButtonFocus(enabled: Boolean = true): Modifier = composed {
    var isFocused by remember { mutableStateOf(false) }
    onFocusChanged { isFocused = enabled && it.isFocused }
        .tvButtonFocus(isFocused = isFocused, enabled = enabled)
}

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
        border = if (isFocused) BorderStroke(3.dp, RommTvColors.Romm300) else null,
        content = content,
    )
}

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
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextSecondary.copy(alpha = 0.7f),
        ),
        content = content,
    )
}

@Composable
fun TvSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) RommTvColors.Romm300 else Color.Transparent
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

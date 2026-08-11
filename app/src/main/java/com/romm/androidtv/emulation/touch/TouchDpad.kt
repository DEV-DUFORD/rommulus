package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.romm.androidtv.controller.model.LogicalControl

/**
 * A cross-shaped D-pad with four directional zones (Up, Down, Left, Right).
 *
 * Each zone is independently pressable using [TouchZone], allowing simultaneous
 * directional inputs (e.g. Up+Right for diagonal movement).
 *
 * @param onDirectionChange callback invoked with the [LogicalControl] direction and pressed state
 * @param modifier optional modifier for layout customization
 */
@Composable
fun TouchDpad(
    directions: DpadLogicalControls = DpadLogicalControls(
        up = LogicalControl.DPAD_UP,
        down = LogicalControl.DPAD_DOWN,
        left = LogicalControl.DPAD_LEFT,
        right = LogicalControl.DPAD_RIGHT,
    ),
    onDirectionChange: (LogicalControl, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    opacity: Float = 0.72f,
    inputEnabled: Boolean = true,
    pressedDirections: Set<LogicalControl> = emptySet(),
) {
    val dpadColor = MaterialTheme.colorScheme.surface.copy(alpha = opacity * 0.45f)
    val borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(DpadShape)
            .background(dpadColor)
            .border(2.dp, borderColor, DpadShape),
        contentAlignment = Alignment.Center,
    ) {
        // Vertical column: Up / Center / Down
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(0.34f),
            contentAlignment = Alignment.Center,
        ) {
            // Up zone
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.up,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                    inputEnabled = inputEnabled,
                    pressedOverride = if (inputEnabled) null else directions.up in pressedDirections,
                )
            }

            // Center (non-pressable crossbar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f),
            )

            // Down zone
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.down,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                    inputEnabled = inputEnabled,
                    pressedOverride = if (inputEnabled) null else directions.down in pressedDirections,
                )
            }
        }

        // Horizontal row: Left / (overlaps center) / Right
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.34f),
            contentAlignment = Alignment.Center,
        ) {
            // Left zone
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .fillMaxWidth(0.34f)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.left,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                    inputEnabled = inputEnabled,
                    pressedOverride = if (inputEnabled) null else directions.left in pressedDirections,
                )
            }

            // Right zone
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.34f)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.right,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                    inputEnabled = inputEnabled,
                    pressedOverride = if (inputEnabled) null else directions.right in pressedDirections,
                )
            }
        }

    }
}

private object DpadShape : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val armStartX = size.width * 0.33f
        val armEndX = size.width * 0.67f
        val armStartY = size.height * 0.33f
        val armEndY = size.height * 0.67f
        val radius = with(density) { 12.dp.toPx() }
            .coerceAtMost(minOf(armStartX, armStartY))
        val path = Path().apply {
            moveTo(armStartX, radius)
            quadraticBezierTo(armStartX, 0f, armStartX + radius, 0f)
            lineTo(armEndX - radius, 0f)
            quadraticBezierTo(armEndX, 0f, armEndX, radius)
            lineTo(armEndX, armStartY)
            lineTo(size.width - radius, armStartY)
            quadraticBezierTo(size.width, armStartY, size.width, armStartY + radius)
            lineTo(size.width, armEndY - radius)
            quadraticBezierTo(size.width, armEndY, size.width - radius, armEndY)
            lineTo(armEndX, armEndY)
            lineTo(armEndX, size.height - radius)
            quadraticBezierTo(armEndX, size.height, armEndX - radius, size.height)
            lineTo(armStartX + radius, size.height)
            quadraticBezierTo(armStartX, size.height, armStartX, size.height - radius)
            lineTo(armStartX, armEndY)
            lineTo(radius, armEndY)
            quadraticBezierTo(0f, armEndY, 0f, armEndY - radius)
            lineTo(0f, armStartY + radius)
            quadraticBezierTo(0f, armStartY, radius, armStartY)
            lineTo(armStartX, armStartY)
            close()
        }
        return Outline.Generic(path)
    }
}

data class DpadLogicalControls(
    val up: LogicalControl,
    val down: LogicalControl,
    val left: LogicalControl,
    val right: LogicalControl,
)

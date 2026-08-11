package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
) {
    val dpadColor = MaterialTheme.colorScheme.surface.copy(alpha = opacity * 0.45f)

    Box(
        modifier = modifier
            .fillMaxSize(),
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
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.up,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                )
            }

            // Center (non-pressable crossbar)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f)
                    .background(dpadColor),
            )

            // Down zone
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.34f)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.down,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
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
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.left,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                )
            }

            // Right zone
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(0.34f)
                    .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = directions.right,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                )
            }
        }

    }
}

data class DpadLogicalControls(
    val up: LogicalControl,
    val down: LogicalControl,
    val left: LogicalControl,
    val right: LogicalControl,
)

package com.romm.androidtv.emulation.touch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
    onDirectionChange: (LogicalControl, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dpadColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.3f)

    Box(
        modifier = modifier
            .width(200.dp)
            .height(200.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Vertical column: Up / Center / Down
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Up zone
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = LogicalControl.DPAD_UP,
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
                    .height(56.dp)
                    .background(dpadColor),
            )

            // Down zone
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = LogicalControl.DPAD_DOWN,
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
                .height(64.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Left zone
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(56.dp)
                    .clip(RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = LogicalControl.DPAD_LEFT,
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
                    .width(56.dp)
                    .clip(RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp))
                    .background(dpadColor)
                    .padding(2.dp),
                contentAlignment = Alignment.Center,
            ) {
                TouchZone(
                    logicalControl = LogicalControl.DPAD_RIGHT,
                    onButtonChange = onDirectionChange,
                    modifier = Modifier.fillMaxSize(),
                    backgroundColor = Color.Transparent,
                    borderColor = Color.Transparent,
                )
            }
        }
    }
}

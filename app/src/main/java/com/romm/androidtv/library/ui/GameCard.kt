package com.romm.androidtv.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.romm.androidtv.platform.currentDeviceProfile

private val GameCardShape = RoundedCornerShape(8.dp)

/**
 * A poster-style cover-art card for a Home shelf, styled after standard
 * Android TV / Leanback card conventions (as seen in apps like Jellyfin's
 * Android TV client) rather than tvOS-style chrome: title is always visible
 * below the poster (not focus-only), and D-pad focus is communicated with a
 * scale-up + elevation shadow + accent border, not a flat color overlay.
 * Falls back to a themed placeholder icon when [coverUrl] is null.
 */
@Composable
fun GameCard(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit = {},
) {
    val profile = currentDeviceProfile()
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .width(if (profile.usePortraitTouchLayout) 112.dp else 136.dp)
            // `clickable` (and thus the focus target Compose uses for "bring focused item
            // into view" when scrolling) lives on the whole Column, not just the image Box
            // below — otherwise bring-into-view only guarantees the poster is on-screen,
            // leaving the title/subtitle Text (a sibling outside that Box) clipped at the
            // bottom edge for the last shelf/grid row.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(2f / 3f)
                .graphicsLayer {
                    val focusedScale = if (isFocused) 1.1f else 1f
                    scaleX = focusedScale
                    scaleY = focusedScale
                    shadowElevation = if (isFocused) 12.dp.toPx() else 0f
                    shape = GameCardShape
                    clip = true
                }
                .background(RommTvColors.NightLo)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) RommTvColors.Romm500 else Color.Transparent,
                    shape = GameCardShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null) {
                AsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = RommTvColors.TextSecondary,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = if (isFocused) RommTvColors.Romm300 else RommTvColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = RommTvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

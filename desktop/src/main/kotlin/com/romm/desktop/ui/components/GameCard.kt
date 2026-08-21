package com.romm.desktop.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.romm.androidtv.library.LibraryRom
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.image.RommAsyncImage

private val GameCardShape = RoundedCornerShape(8.dp)

/** Fixed card width for desktop — matches the Android "wide" (non-portrait) width. */
private val DesktopGameCardWidth: Dp = 136.dp

/**
 * A poster-style cover-art card for a ROM entry, styled after standard
 * Android TV / Leanback card conventions: title is always visible below the
 * poster (not focus-only), and keyboard/mouse focus is communicated with a
 * scale-up + elevation shadow + accent border.
 *
 * Falls back to a themed placeholder icon when [LibraryRom.coverUrl] is null.
 * Uses the desktop-native [RommAsyncImage] for cover loading.
 */
@Composable
fun GameCard(
    rom: LibraryRom,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GameCard(
        title = rom.title,
        coverUrl = rom.coverUrl,
        subtitle = rom.platformDisplayName,
        onClick = onClick,
        modifier = modifier,
    )
}

/**
 * Overload accepting raw fields so callers that don't have a [LibraryRom]
 * can still use the card (e.g. search results, custom adapters).
 */
@Composable
fun GameCard(
    title: String,
    coverUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClick: () -> Unit = {},
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    Column(
        modifier = modifier
            .width(DesktopGameCardWidth)
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
                .background(LocalRommulusColors.current.nightLo)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) LocalRommulusColors.current.romm500 else Color.Transparent,
                    shape = GameCardShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (coverUrl != null && coverUrl.isNotBlank()) {
                RommAsyncImage(
                    model = coverUrl,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // No-cover placeholder: the same SportsEsports icon Android's GameCard shows.
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = LocalRommulusColors.current.textSecondary,
                )
            }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.bodySmall,
            color = if (isFocused) LocalRommulusColors.current.romm300 else LocalRommulusColors.current.textPrimary,
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
                color = LocalRommulusColors.current.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

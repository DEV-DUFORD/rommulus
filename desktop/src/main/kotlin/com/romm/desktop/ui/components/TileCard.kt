package com.romm.desktop.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.romm.desktop.ui.components.LocalRommulusColors
import com.romm.desktop.ui.image.RommAsyncImage

private val TileCardShape = RoundedCornerShape(8.dp)

/**
 * A 16:10 tile card for platforms, collections, or other categorical items.
 *
 * Displays an image (or placeholder icon when [imageUrl] is null/empty),
 * a title line, and an optional subtitle. Focus is communicated via a
 * scale-up + accent border, matching the Android TV card convention.
 */
@Composable
fun TileCard(
    title: String,
    imageUrl: String?,
    fallbackImageUrls: List<String> = emptyList(),
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val imageUrls = remember(imageUrl, fallbackImageUrls) {
        (listOfNotNull(imageUrl?.takeIf { it.isNotBlank() }) + fallbackImageUrls)
            .filter(String::isNotBlank)
            .distinct()
    }
    var imageIndex by remember(imageUrls) { mutableIntStateOf(0) }

    Column(
        modifier = modifier
            .padding(4.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 10f)
                .clip(TileCardShape)
                .background(LocalRommulusColors.current.nightLo)
                .border(
                    width = if (isFocused) 3.dp else 0.dp,
                    color = if (isFocused) LocalRommulusColors.current.romm500 else Color.Transparent,
                    shape = TileCardShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            val currentImageUrl = imageUrls.getOrNull(imageIndex)
            if (currentImageUrl != null) {
                RommAsyncImage(
                    model = currentImageUrl,
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                    onError = {
                        if (imageIndex < imageUrls.lastIndex) imageIndex += 1
                    },
                )
            } else {
                // No icon placeholder on desktop — just the themed background.
            }
        }
        Text(
            text = title,
            color = if (isFocused) LocalRommulusColors.current.romm300 else LocalRommulusColors.current.textPrimary,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                color = LocalRommulusColors.current.textSecondary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

package com.romm.desktop.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Small Composable wrapper around a synchronous [DesktopImageLoader] for loading
 * ROM cover artwork by URL. Provides placeholder/error/loading states with a
 * styled box.
 *
 * On success the loaded bitmap is rendered on a Compose Canvas. The loading
 * and error states render styled placeholder boxes so consumers always see a
 * well-defined area regardless of network state.
 *
 * @param model URL string to load. Null/empty renders the error state.
 * @param contentDescription Accessibility label (reserved for future use).
 * @param modifier Modifier for layout/interaction.
 * @param size Target image size (default 200.dp).
 * @param contentScale How to resize/rotate image.
 * @param loader The [DesktopImageLoader] (defaults to one built via [buildRomMImageLoader]).
 */
@Composable
fun RommAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    contentScale: ContentScale = ContentScale.Crop,
    loader: DesktopImageLoader = buildRomMImageLoader(),
) {
    var loadResult by remember(model) { mutableStateOf<ImageLoadResult>(ImageLoadResult.Loading) }

    LaunchedEffect(model, loader) {
        val url = model?.takeIf { it.isNotBlank() }
        loadResult = if (url != null) {
            loader.load(url)
        } else {
            ImageLoadResult.Error
        }
    }

    val loadedSize = Modifier.then(Modifier.size(size))

    when (val s = loadResult) {
        is ImageLoadResult.Loading -> {
            LoadingPlaceholder(modifier = loadedSize)
        }
        is ImageLoadResult.Error -> {
            ErrorPlaceholder(modifier = loadedSize)
        }
        is ImageLoadResult.Success -> {
            LoadedImage(
                bitmap = s.bitmap,
                modifier = loadedSize,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

@Composable
private fun LoadedImage(
    bitmap: androidx.compose.ui.graphics.ImageBitmap,
    modifier: Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(modifier = Modifier.matchParentSize()) {
            drawImage(bitmap)
        }
    }
}

@Composable
private fun LoadingPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF333333)),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ErrorPlaceholder(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.background(Color(0xFF444444)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primaryContainer),
        )
    }
}

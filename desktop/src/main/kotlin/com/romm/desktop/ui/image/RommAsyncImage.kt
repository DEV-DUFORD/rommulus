package com.romm.desktop.ui.image

import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

val LocalDesktopImageLoader = compositionLocalOf<DesktopImageLoader> {
    error("LocalDesktopImageLoader is not provided")
}

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
 * @param size Target image size (default 200.dp), or null to use the caller's constraints.
 * @param contentScale How to resize/rotate image.
 * @param loader Optional loader override; the app-provided authenticated loader is used by default.
 * @param onError Invoked from the image-loading coroutine when this model cannot be loaded.
 */
@Composable
fun RommAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    size: Dp? = 200.dp,
    contentScale: ContentScale = ContentScale.Crop,
    loader: DesktopImageLoader? = null,
    onError: (() -> Unit)? = null,
) {
    val imageLoader = loader ?: LocalDesktopImageLoader.current
    var loadResult by remember(model) { mutableStateOf<ImageLoadResult>(ImageLoadResult.Loading) }

    LaunchedEffect(model, imageLoader) {
        val url = model?.takeIf { it.isNotBlank() }
        loadResult = if (url != null) {
            withContext(Dispatchers.IO) { imageLoader.load(url) }
        } else {
            ImageLoadResult.Error
        }
        if (loadResult is ImageLoadResult.Error) onError?.invoke()
    }

    val imageModifier = if (size != null) modifier.size(size) else modifier

    when (val s = loadResult) {
        is ImageLoadResult.Loading -> {
            LoadingPlaceholder(modifier = imageModifier)
        }
        is ImageLoadResult.Error -> {
            ErrorPlaceholder(modifier = imageModifier)
        }
        is ImageLoadResult.Success -> {
            Image(
                bitmap = s.bitmap,
                contentDescription = contentDescription,
                contentScale = contentScale,
                modifier = imageModifier,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Drawing helpers
// ---------------------------------------------------------------------------

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

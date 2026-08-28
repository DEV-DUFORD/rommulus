package com.romm.desktop.ui.image

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Loads a bundled image asset (SVG or PNG) from the classpath and decodes it into an
 * [ImageBitmap].
 *
 * Desktop has no Android resource system: bundled assets live under `src/main/resources`
 * and are addressed by absolute classpath path (e.g. `/icons/romm_logo.svg`), which is how
 * the desktop module substitutes for `R.raw.*` / `R.drawable.*` lookups.
 *
 * @param resourcePath absolute classpath path of the asset.
 * @param size square edge (px) used when rasterizing SVGs.
 * @return the decoded bitmap, or `null` when the resource is missing or fails to decode.
 */
fun loadBundledImage(
    resourcePath: String,
    size: Int = 512,
): ImageBitmap? {
    val bytes: ByteArray =
        DesktopImageLoader::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
            ?: return null
    return try {
        decodeImageBytes(bytes, resourcePath, size)
    } catch (_: Exception) {
        null
    }
}

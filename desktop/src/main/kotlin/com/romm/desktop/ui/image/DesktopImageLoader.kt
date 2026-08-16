package com.romm.desktop.ui.image

import okhttp3.OkHttpClient
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.imageio.ImageIO

/**
 * Result of a synchronous image load attempt.
 */
sealed interface ImageLoadResult {
    data class Success(val bitmap: androidx.compose.ui.graphics.ImageBitmap) : ImageLoadResult
    data object Loading : ImageLoadResult
    data object Error : ImageLoadResult
}

/**
 * Builds a simple synchronous image loader for the RomM desktop app.
 *
 * Uses `HttpURLConnection` for network fetching (no OkHttp fetcher plugin is
 * available for pure JVM desktop in Compose 1.6.x). The supplied [httpClient]
 * is retained in [cachedClient] so the coordinator can swap in an OkHttp-
 * backed client in a later wave.
 *
 * @param cacheDirBase where on-disk artwork is cached (defaults to JVM temp).
 */
class DesktopImageLoader(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val cacheDirBase: File =
        File(System.getProperty("java.io.tmpdir"), "romm-desktop-image-cache"),
) {

    private val cache: MutableMap<String, File?> = HashMap()

    /** A tiny solid-color placeholder bitmap used when no artwork is available. */
    private val placeholderBitmap: androidx.compose.ui.graphics.ImageBitmap by lazy {
        val bmp = androidx.compose.ui.graphics.ImageBitmap(width = 1, height = 1)
        bmp
    }

    /**
     * Load an image synchronously from [url]. Returns an [ImageLoadResult].
     *
     * Uses a small on-disk cache under [cacheDirBase] to avoid re-downloading
     * the same artwork. Reads the URL via [HttpURLConnection] (no OkHttp
     * integration on pure desktop yet).
     */
    fun load(url: String): ImageLoadResult {
        val cachedFile = cache[url]
        if (cachedFile != null && cachedFile.exists()) {
            try {
                val img = ImageIO.read(cachedFile) ?: return ImageLoadResult.Error
                return ImageLoadResult.Success(awtToComposeBitmap(img))
            } catch (_: Exception) {
                // stale cache entry — fall through to re-fetch.
            }
        }

        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) {
                conn.disconnect()
                return ImageLoadResult.Error
            }

            val img = ImageIO.read(conn.inputStream)
            conn.disconnect()

            if (img == null) return ImageLoadResult.Error

            // Write to cache.
            val cacheFile = cacheDirBase.resolve("${url.hashCode()}.png")
            cacheDirBase.mkdirs()
            ImageIO.write(img, "PNG", cacheFile)
            cache[url] = cacheFile

            ImageLoadResult.Success(awtToComposeBitmap(img))
        } catch (_: IOException) {
            ImageLoadResult.Error
        }
    }

    /**
     * Convert a `java.awt.image.BufferedImage` to a Compose [androidx.compose.ui.graphics.ImageBitmap].
     *
     * Uses the Compose Multiplatform 1.6.x `MutableImage` API to copy AWT
     * pixels into a Compose [ImageBitmap].
     */
    private fun awtToComposeBitmap(img: BufferedImage): androidx.compose.ui.graphics.ImageBitmap {
        val w = img.width
        val h = img.height
        // In Compose 1.6.x the ImageBitmap can be created via ImageBitmap(width, height)
        // and pixels are copied via the raster's setColor/setPixel.
        // We use the simplest available API: allocate an ImageBitmap then copy via Canvas.
        val bitmap = androidx.compose.ui.graphics.ImageBitmap(width = w, height = h)
        // Canvas-based pixel transfer (Compose Desktop uses ARGB8888 by default).
        val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
        val paint = androidx.compose.ui.graphics.Paint()
        // Draw each pixel via a 1x1 drawRect (slow but correct on desktop).
        for (y in 0 until h) {
            for (x in 0 until w) {
                val argb = img.getRGB(x, y)
                paint.color = androidx.compose.ui.graphics.Color((argb.toLong() and 0xFFFFFFFFL))
                canvas.drawRect(
                    androidx.compose.ui.geometry.Rect(x.toFloat(), y.toFloat(), x + 1f, y + 1f),
                    paint,
                )
            }
        }
        return bitmap
    }
}

/** Convenience function matching the original API. */
fun buildRomMImageLoader(
    httpClient: OkHttpClient = OkHttpClient(),
): DesktopImageLoader = DesktopImageLoader(httpClient)

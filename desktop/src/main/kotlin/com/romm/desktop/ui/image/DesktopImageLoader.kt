package com.romm.desktop.ui.image

import androidx.compose.ui.graphics.toComposeImageBitmap
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM
import java.io.File

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
 * Uses the application's OkHttp client directly so same-origin artwork requests receive the
 * same bearer authentication as library API requests.
 *
 * @param cacheDirBase where on-disk artwork is cached (defaults to JVM temp).
 */
class DesktopImageLoader(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val cacheDirBase: File =
        File(System.getProperty("java.io.tmpdir"), "romm-desktop-image-cache"),
) {

    private val cache: MutableMap<String, File?> = java.util.concurrent.ConcurrentHashMap()

    /**
     * Load an image synchronously from [url]. Returns an [ImageLoadResult].
     *
     * Uses a small on-disk cache under [cacheDirBase] to avoid re-downloading
     * the same artwork.
     */
    fun load(url: String): ImageLoadResult {
        val cachedFile = cache[url]
        if (cachedFile != null && cachedFile.exists()) {
            try {
                return ImageLoadResult.Success(decodeImage(cachedFile.readBytes(), url))
            } catch (_: Exception) {
                // stale cache entry — fall through to re-fetch.
            }
        }

        return try {
            val request = Request.Builder().url(url).get().build()
            val bytes = httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return ImageLoadResult.Error
                response.body?.bytes()
            }

            if (bytes == null) return ImageLoadResult.Error
            val bitmap = decodeImage(bytes, url)

            val cacheFile = cacheDirBase.resolve("${url.hashCode()}.image")
            cacheDirBase.mkdirs()
            cacheFile.writeBytes(bytes)
            cache[url] = cacheFile

            ImageLoadResult.Success(bitmap)
        } catch (_: Exception) {
            ImageLoadResult.Error
        }
    }

    private fun decodeImage(
        bytes: ByteArray,
        url: String,
    ): androidx.compose.ui.graphics.ImageBitmap = decodeImageBytes(bytes, url)
}

/**
 * Decodes image bytes (PNG/JPG via Skia, SVG via [SVGDOM]) into a Compose [ImageBitmap].
 *
 * Shared by the URL-based [DesktopImageLoader] and the classpath-based bundled-asset loader
 * ([loadBundledImage]).
 *
 * @param targetSize square edge (px) used when rasterizing SVGs.
 */
internal fun decodeImageBytes(
    bytes: ByteArray,
    url: String,
    targetSize: Int = 512,
): androidx.compose.ui.graphics.ImageBitmap {
    val isSvg = url.substringBefore('?').endsWith(".svg", ignoreCase = true) ||
        bytes.decodeToString(endIndex = minOf(bytes.size, 256)).trimStart().startsWith("<svg")
    if (!isSvg) {
        return Image.makeFromEncoded(bytes).toComposeImageBitmap()
    }

    return Data.makeFromBytes(inlineSvgClassStyles(bytes)).use { data ->
        SVGDOM(data).use { svg ->
            svg.setContainerSize(targetSize.toFloat(), targetSize.toFloat())
            Surface.makeRasterN32Premul(targetSize, targetSize).use { surface ->
                surface.canvas.clear(0x00000000)
                svg.render(surface.canvas)
                surface.makeImageSnapshot().toComposeImageBitmap()
            }
        }
    }
}

/**
 * Skia's SVG renderer does not apply CSS class rules from embedded `<style>` blocks.
 * RomM's bundled controller artwork uses those rules for every fill and stroke, so copy
 * the declarations onto each element as presentation attributes before rendering.
 *
 * A declaration replaces any same-named attribute already present on the element: per the
 * SVG spec, class rules outrank presentation attributes, so the class value wins (the ROMM
 * logo carries a redundant `fill="#000000"` on every classed shape). Appending blindly would
 * emit duplicate attributes and Skia's parser rejects the document.
 */
internal fun inlineSvgClassStyles(bytes: ByteArray): ByteArray {
    val svg = bytes.decodeToString()
    val declarationsByClass = linkedMapOf<String, LinkedHashMap<String, String>>()

    SVG_STYLE_BLOCK.findAll(svg).forEach { styleBlock ->
        SVG_CSS_RULE.findAll(styleBlock.groupValues[1]).forEach { rule ->
            val declarations = rule.groupValues[2]
                .split(';')
                .mapNotNull { declaration ->
                    val parts = declaration.split(':', limit = 2)
                    if (parts.size != 2) null
                    else parts[0].trim().takeIf(String::isNotEmpty)?.let { it to parts[1].trim() }
                }
            rule.groupValues[1].split(',').forEach { selector ->
                val className = selector.trim().removePrefix(".")
                if (className.matches(SVG_CLASS_NAME)) {
                    declarationsByClass.getOrPut(className, ::linkedMapOf).putAll(declarations)
                }
            }
        }
    }

    if (declarationsByClass.isEmpty()) return bytes

    val out = StringBuilder(svg.length + 64)
    var cursor = 0
    SVG_CLASS_ATTRIBUTE.findAll(svg).forEach { match ->
        val tagStart = svg.lastIndexOf('<', match.range.first)
        val tagEnd = svg.indexOf('>', match.range.last)
        if (tagStart < 0 || tagEnd < 0) return@forEach
        var tag = svg.substring(tagStart, tagEnd + 1)
        match.groupValues[1].split(Regex("\\s+")).forEach { className ->
            declarationsByClass[className]?.forEach { (name, value) ->
                tag = upsertAttribute(tag, name, value)
            }
        }
        out.append(svg, cursor, tagStart)
        out.append(tag)
        cursor = tagEnd + 1
    }
    out.append(svg, cursor, svg.length)
    return out.toString().encodeToByteArray()
}

/**
 * Sets `name="value"` on a start tag: replaces an existing same-named attribute in place,
 * or appends one before the tag end (keeping self-closing tags well-formed).
 */
private fun upsertAttribute(tag: String, name: String, value: String): String {
    val escaped = value.escapeXmlAttribute()
    val existing = Regex("""(?<![\w-])$name\s*=\s*"[^"]*"""")
    return when {
        existing.containsMatchIn(tag) -> existing.replaceFirst(tag, """$name="$escaped"""")
        tag.endsWith("/>") -> tag.dropLast(2) + " $name=\"$escaped\"/>"
        else -> tag.dropLast(1) + " $name=\"$escaped\">"
    }
}

private fun String.escapeXmlAttribute(): String =
    replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

private val SVG_STYLE_BLOCK = Regex("""<style\b[^>]*>(.*?)</style>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
private val SVG_CSS_RULE = Regex("""([^{}]+)\{([^{}]+)}""")
private val SVG_CLASS_ATTRIBUTE = Regex("""\bclass\s*=\s*"([^"]+)"""")
private val SVG_CLASS_NAME = Regex("""[A-Za-z_][A-Za-z0-9_-]*""")

/** Convenience function matching the original API. */
fun buildRomMImageLoader(
    httpClient: OkHttpClient = OkHttpClient(),
): DesktopImageLoader = DesktopImageLoader(httpClient)

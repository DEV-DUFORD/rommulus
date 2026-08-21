package com.romm.androidtv.emulation.video

import android.content.Context
import android.graphics.PixelFormat
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.romm.androidtv.emulation.nativehost.NativeLibretroHost
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

internal fun resolveDisplayAspectRatio(coreWidth: Int, coreHeight: Int, reportedAspectRatio: Float): Float =
    when {
        reportedAspectRatio.isFinite() && reportedAspectRatio > 0f -> reportedAspectRatio
        coreWidth > 0 && coreHeight > 0 -> coreWidth.toFloat() / coreHeight
        else -> 4f / 3f
    }

/**
 * Computes the largest integer VERTICAL scale factor whose aspect-corrected
 * footprint fits entirely within [maxWidthPx] x [maxHeightPx], or 0 when no
 * integer scale >= 1 fits (the caller then falls back to fractional fit).
 *
 * Only the vertical scale is guaranteed to be integer; the horizontal buffer
 * width is aspect-corrected (see [EmulationSurface] KDoc). The result is also
 * capped so that `coreWidth * scale` and `coreHeight * scale` cannot overflow
 * `Int`.
 */
internal fun computeIntegerScale(
    coreWidth: Int,
    coreHeight: Int,
    displayAspectRatio: Float,
    maxWidthPx: Float,
    maxHeightPx: Float,
): Int {
    if (coreWidth <= 0 || coreHeight <= 0 || maxWidthPx <= 0f || maxHeightPx <= 0f) return 0
    val aspect = resolveDisplayAspectRatio(coreWidth, coreHeight, displayAspectRatio)
    val displayWidth = coreHeight * aspect
    val scale = floor(min(maxWidthPx / displayWidth, maxHeightPx / coreHeight)).toInt()
    if (scale < 1) return 0
    // Guard against Int overflow in coreWidth * scale / coreHeight * scale.
    val maxSafeScale = min(Int.MAX_VALUE / coreWidth, Int.MAX_VALUE / coreHeight).coerceAtLeast(1)
    return min(scale, maxSafeScale)
}

/**
 * Returns the largest whole-pixel rectangle with [aspectRatio] that fits
 * within the supplied bounds.
 */
internal fun computeAspectFittedSize(
    aspectRatio: Float,
    maxWidthPx: Int,
    maxHeightPx: Int,
): IntSize {
    if (!aspectRatio.isFinite() || aspectRatio <= 0f || maxWidthPx <= 0 || maxHeightPx <= 0) {
        return IntSize.Zero
    }

    return if (maxWidthPx.toFloat() / maxHeightPx > aspectRatio) {
        IntSize(
            width = (maxHeightPx * aspectRatio).roundToInt().coerceAtMost(maxWidthPx),
            height = maxHeightPx,
        )
    } else {
        IntSize(
            width = maxWidthPx,
            height = (maxWidthPx / aspectRatio).roundToInt().coerceAtMost(maxHeightPx),
        )
    }
}

private class EmulationSurfaceView(context: Context) : SurfaceView(context) {
    private var bufferWidth = 0
    private var bufferHeight = 0

    init {
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    fun setBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (width == bufferWidth && height == bufferHeight) return
        bufferWidth = width
        bufferHeight = height
        holder.setFixedSize(width, height)
    }
}

/**
 * Renders the native Libretro host's video output (LIBRETRO_REFACTOR.md
 * section 8.1).
 *
 * A plain `SurfaceView` is used rather than `TextureView`: this is a single
 * full-screen video sink with no need for view transforms, alpha blending,
 * or thumbnailing, so `SurfaceView`'s direct compositor path (no extra
 * GPU copy through the view hierarchy) is the better fit and matches how
 * most Libretro/RetroArch-style frontends render on Android.
 *
 * Compose recalculates the aspect-corrected content bounds whenever the
 * available display area changes. Those same bounds are used for a sharp
 * buffer, keeping the fixed surface size in sync with the visible surface as
 * system bars or core geometry change.
 *
 * When [integerScalingEnabled] is true, the surface is sized to the largest
 * clean integer multiple of the core's native resolution that fits entirely on
 * screen. Only the VERTICAL scale is guaranteed integer: the horizontal width
 * is aspect-corrected (multiplied by [displayAspectRatio]) to preserve the
 * display aspect ratio when it differs from the native pixel aspect. Sharp
 * filtering remains independent: unless [sharpFilterEnabled] is on, the
 * compositor scales the native-resolution buffer normally.
 */
@Composable
fun EmulationSurface(
    host: NativeLibretroHost,
    coreWidth: Int,
    coreHeight: Int,
    displayAspectRatio: Float,
    scanlinesEnabled: Boolean = false,
    integerScalingEnabled: Boolean = false,
    sharpFilterEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val aspect = resolveDisplayAspectRatio(coreWidth, coreHeight, displayAspectRatio)
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        BoxWithConstraints {
            val density = LocalDensity.current
            val maxWidthPx = with(density) { maxWidth.roundToPx() }
            val maxHeightPx = with(density) { maxHeight.roundToPx() }
            val fittedSize = computeAspectFittedSize(aspect, maxWidthPx, maxHeightPx)
            val integerScale = if (integerScalingEnabled) {
                computeIntegerScale(
                    coreWidth,
                    coreHeight,
                    aspect,
                    maxWidthPx.toFloat(),
                    maxHeightPx.toFloat(),
                )
            } else {
                0
            }
            val contentSize = if (integerScale >= 1) {
                IntSize(
                    width = (coreHeight * aspect * integerScale).roundToInt(),
                    height = coreHeight * integerScale,
                )
            } else {
                fittedSize
            }
            val innerBoxModifier = if (contentSize != IntSize.Zero) {
                Modifier.size(
                    width = with(density) { contentSize.width.toDp() },
                    height = with(density) { contentSize.height.toDp() },
                )
            } else {
                Modifier.aspectRatio(aspect)
            }
            val bufferSize = if (sharpFilterEnabled && contentSize != IntSize.Zero) {
                contentSize
            } else {
                IntSize(coreWidth, coreHeight)
            }

            Box(modifier = innerBoxModifier) {
                AndroidView(
                    factory = { context ->
                        EmulationSurfaceView(context).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    host.nativeSetSurface(holder.surface)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    // Buffer sizing is driven by AndroidView.update below.
                                    // Native rendering continues against this same Surface.
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    host.nativeSetSurface(null)
                                }
                            })
                        }
                    },
                    update = { surfaceView ->
                        surfaceView.setBufferSize(bufferSize.width, bufferSize.height)
                    },
                    modifier = Modifier.matchParentSize(),
                )
                if (scanlinesEnabled) {
                    ScanlineOverlay(
                        coreHeight = coreHeight,
                        modifier = Modifier.matchParentSize(),
                    )
                }
            }
        }
    }
}

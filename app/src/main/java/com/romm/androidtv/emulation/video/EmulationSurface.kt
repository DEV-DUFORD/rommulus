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

private class EmulationSurfaceView(context: Context) : SurfaceView(context) {
    private var bufferWidth = 0
    private var bufferHeight = 0
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var sharpFilterEnabled = false

    init {
        holder.setFormat(PixelFormat.RGBA_8888)
    }

    fun setVideoConfiguration(width: Int, height: Int, sharpFilterEnabled: Boolean) {
        sourceWidth = width
        sourceHeight = height
        this.sharpFilterEnabled = sharpFilterEnabled
        applyBufferSize()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyBufferSize()
    }

    private fun applyBufferSize() {
        if (sourceWidth <= 0 || sourceHeight <= 0) return
        val width = if (sharpFilterEnabled && this.width > 0) this.width else sourceWidth
        val height = if (sharpFilterEnabled && this.height > 0) this.height else sourceHeight
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
 * The `AndroidView`'s `modifier` is re-applied by Compose on every
 * recomposition (unlike `factory`, which only runs once), so passing an
 * `aspectRatio` modifier here keeps the visible surface correctly
 * pillarboxed/letterboxed as [coreWidth]/[coreHeight] change — including
 * the synthetic test core's one deliberate geometry change
 * (320x240 -> 384x288 at frame 300).
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
        if (integerScalingEnabled && coreWidth > 0 && coreHeight > 0) {
            // Integer scaling branch: measure available space, compute largest integer scale.
            BoxWithConstraints {
                val density = LocalDensity.current
                val maxWidthPx = with(density) { maxWidth.toPx() }
                val maxHeightPx = with(density) { maxHeight.toPx() }

                val scale = computeIntegerScale(coreWidth, coreHeight, aspect, maxWidthPx, maxHeightPx)
                val useIntegerScale = scale >= 1

                // Size the box from the buffer (not the other way around) so the buffer can
                // never exceed the box: vertical is an integer multiple of coreHeight, and the
                // width is aspect-corrected (coreHeight * aspect * scale), preserving the
                // display aspect ratio while keeping the display box at an integer scale.
                val scaledWidthPx = if (useIntegerScale) (coreHeight * aspect * scale).roundToInt() else coreWidth
                val bufferH = if (useIntegerScale) coreHeight * scale else coreHeight

                // When integer scaling is active and valid, size the box to exact scaled px;
                // otherwise fall back to aspectRatio fit-to-screen.
                val innerBoxModifier = if (useIntegerScale) {
                    Modifier.size(
                        width = with(density) { scaledWidthPx.toDp() },
                        height = with(density) { bufferH.toDp() },
                    )
                } else {
                    Modifier.aspectRatio(aspect)
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
                                        // Synchronous: nativeSetSurface(null) blocks until the
                                        // native ANativeWindow reference is released, honoring
                                        // the "don't touch the Surface after this returns"
                                        // contract this callback requires.
                                        host.nativeSetSurface(null)
                                    }
                                })
                            }
                        },
                        update = { surfaceView ->
                            surfaceView.setVideoConfiguration(coreWidth, coreHeight, sharpFilterEnabled)
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
        } else {
            // Non-integer-scaling branch: original fit-to-screen with fractional upscale.
            Box(modifier = Modifier.aspectRatio(aspect)) {
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
                        surfaceView.setVideoConfiguration(coreWidth, coreHeight, sharpFilterEnabled)
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

package com.romm.androidtv.emulation.video

import android.content.Context
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

private class EmulationSurfaceView(context: Context) : SurfaceView(context) {
    private var bufferWidth = 0
    private var bufferHeight = 0

    fun setBufferSize(width: Int, height: Int) {
        if (width <= 0 || height <= 0 || (width == bufferWidth && height == bufferHeight)) return
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
 * clean integer multiple of the core resolution that fits entirely on screen.
 * The buffer size is set to match the scaled dimensions so the compositor
 * performs a 1:1 pixel mapping (no fractional upscale).
 */
@Composable
fun EmulationSurface(
    host: NativeLibretroHost,
    coreWidth: Int,
    coreHeight: Int,
    scanlinesEnabled: Boolean = false,
    integerScalingEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (integerScalingEnabled && coreWidth > 0 && coreHeight > 0) {
            // Integer scaling branch: measure available space, compute largest integer scale.
            BoxWithConstraints {
                val density = LocalDensity.current
                val maxWidthPx = with(density) { maxWidth.toPx() }
                val maxHeightPx = with(density) { maxHeight.toPx() }

                val scale = floor(min(maxWidthPx / coreWidth, maxHeightPx / coreHeight)).toInt()
                val useIntegerScale = scale >= 1

                val bufferW = if (useIntegerScale) coreWidth * scale else coreWidth
                val bufferH = if (useIntegerScale) coreHeight * scale else coreHeight

                // When integer scaling is active and valid, size the box to exact scaled px;
                // otherwise fall back to aspectRatio fit-to-screen.
                val innerBoxModifier = if (useIntegerScale) {
                    Modifier.size(
                        width = with(density) { bufferW.toDp() },
                        height = with(density) { bufferH.toDp() },
                    )
                } else {
                    val aspect = coreWidth.toFloat() / coreHeight.toFloat()
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
                            surfaceView.setBufferSize(bufferW, bufferH)
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
            val aspect = if (coreWidth > 0 && coreHeight > 0) {
                coreWidth.toFloat() / coreHeight.toFloat()
            } else {
                4f / 3f
            }

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
                        surfaceView.setBufferSize(coreWidth, coreHeight)
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

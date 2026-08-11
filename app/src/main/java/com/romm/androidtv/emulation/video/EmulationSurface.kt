package com.romm.androidtv.emulation.video

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.romm.androidtv.emulation.nativehost.NativeLibretroHost

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
 */
@Composable
fun EmulationSurface(
    host: NativeLibretroHost,
    coreWidth: Int,
    coreHeight: Int,
    scanlinesEnabled: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val aspect = if (coreWidth > 0 && coreHeight > 0) {
        coreWidth.toFloat() / coreHeight.toFloat()
    } else {
        4f / 3f
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    // Keep SurfaceView's BufferQueue and its Compose layout transaction in
                    // sync. Otherwise a late aspect-ratio layout can remain visually offset
                    // until another Compose draw (such as opening the pause menu) commits it.
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

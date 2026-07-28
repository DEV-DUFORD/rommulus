package com.romm.androidtv.emulation.video

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
    modifier: Modifier = Modifier
) {
    val aspect = if (coreWidth > 0 && coreHeight > 0) {
        coreWidth.toFloat() / coreHeight.toFloat()
    } else {
        4f / 3f
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.aspectRatio(aspect),
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            host.nativeSetSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            // No action needed: the native side re-derives buffer
                            // geometry from the core's own width/height on the
                            // next frame, not from the Surface's layout size.
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
            }
        )
    }
}

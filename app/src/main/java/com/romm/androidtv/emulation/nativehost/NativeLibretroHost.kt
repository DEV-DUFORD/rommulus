package com.romm.androidtv.emulation.nativehost

import android.content.Context
import android.os.Build
import android.view.Surface
import java.io.File
import java.util.zip.ZipFile

/**
 * JNI boundary to the native Libretro host (`app/src/main/cpp/libretro_host`).
 *
 * Phase 2: proves the NDK/CMake toolchain, core loading, environment
 * callbacks, and the emulation thread all work on both ABIs, in particular
 * on the physical 32-bit Google TV Streamer that motivated this native pivot
 * (LIBRETRO_REFACTOR.md section 1). Nothing in this class touches real ROM
 * content, the network, or WebView; it only ever loads the app-owned
 * synthetic test core until a licensed core is approved (section 4.1).
 */
class NativeLibretroHost {

    /** Calls into native code and returns a fixed diagnostic string. Proves the JNI round trip. */
    external fun nativePing(): String

    /** True once the native library has loaded successfully. */
    external fun nativeIsAvailable(): Boolean

    /**
     * Loads the synthetic test core from [corePath] (an absolute path to
     * `libtest_core.so`, resolved by the caller — never a raw user-supplied
     * path) and starts the emulation thread. [systemDir]/[saveDir] are
     * app-private directories exposed to the core via the environment
     * callbacks. Returns false on any failure; see [nativeGetLastError].
     */
    external fun nativeLoadTestCore(corePath: String, systemDir: String, saveDir: String): Boolean

    /** Stops the emulation thread and unloads the core. Safe to call even if nothing is loaded. */
    external fun nativeStopSession()

    external fun nativeIsRunning(): Boolean

    /**
     * `[frameCount, audioFramesProduced, lastWidth, lastHeight, pixelFormat, coreRequestedShutdown,
     * audioUnderrunFrames, audioOverrunFrames, port0ButtonMask, port1ButtonMask, port2ButtonMask, port3ButtonMask,
     * port0LeftX, port0LeftY, port1LeftX, port1LeftY, port2LeftX, port2LeftY, port3LeftX, port3LeftY]`.
     * `pixelFormat` is `-1` and all counts are `0` when no session is active.
     * The per-port button masks and left-stick analog values are
     * debug/diagnostics-only (LIBRETRO_REFACTOR.md section 9) — they show
     * live per-port RetroPad state regardless of which port a physical
     * controller was assigned to, since the synthetic test core itself
     * only ever reads port 0.
     */
    external fun nativeGetDiagnostics(): LongArray

    external fun nativeGetLastError(): String

    /**
     * Attaches [surface] as the native video output target
     * (LIBRETRO_REFACTOR.md section 8.1), or detaches the current one if
     * [surface] is null. This call is synchronous: passing null blocks
     * until the native side has released its `ANativeWindow` reference, so
     * it is safe to call from `SurfaceHolder.Callback.surfaceDestroyed`
     * without a further teardown race.
     */
    external fun nativeSetSurface(surface: Surface?)

    /**
     * Pushes the latest four-port RetroPad input snapshot
     * (LIBRETRO_REFACTOR.md section 9). [buttonMasks] must have length 4
     * (one packed `RETRO_DEVICE_ID_JOYPAD_MASK`-shaped bitmask per port,
     * see [com.romm.androidtv.controller.LibretroPadState]); [analogValues]
     * must have length 16 (4 ports * [leftX, leftY, rightX, rightY], already
     * clamped to Libretro's signed 16-bit range). Safe to call from any
     * thread; the native side only ever writes through lock-free atomics
     * that the emulation thread's `input_state` callback reads from
     * independently (see `input_state.h`'s thread-safety contract).
     */
    external fun nativeUpdateInputState(buttonMasks: IntArray, analogValues: IntArray)

    /**
     * Atomically writes the currently loaded core's RETRO_MEMORY_SAVE_RAM
     * region to [savePath] (write-temp/fsync/rename; LIBRETRO_REFACTOR.md
     * section 11.1). Returns false if no session is active, the core
     * exposes no save RAM, or the write fails.
     */
    external fun nativeCheckpointSaveRam(savePath: String): Boolean

    /**
     * Restores RETRO_MEMORY_SAVE_RAM from [savePath] if it exists and is
     * exactly the size the core currently reports. Returns false (leaving
     * SRAM untouched) if the file is missing or its size doesn't match —
     * this is an intentional, honest rejection of any incompatible or
     * unknown-provenance save rather than a partial restore.
     */
    external fun nativeRestoreSaveRam(savePath: String): Boolean

    companion object {
        @Volatile
        private var loaded = false
        private var loadError: Throwable? = null

        /**
         * Loads the native library exactly once. Safe to call repeatedly.
         * Returns true only if the library is loaded and ready for JNI calls.
         */
        @Synchronized
        fun ensureLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("romm_libretro_host")
                loaded = true
                true
            } catch (t: UnsatisfiedLinkError) {
                loadError = t
                false
            } catch (t: Throwable) {
                loadError = t
                false
            }
        }

        /** The error from the last failed [ensureLoaded] call, if any. */
        fun lastLoadError(): Throwable? = loadError

        /**
         * Resolves an absolute, dlopen-able path to the bundled synthetic test
         * core, extracting it from the APK on first use.
         *
         * `libtest_core.so` is packaged as a normal JNI library, but some
         * devices/App Bundle configurations run native libraries directly out
         * of the APK ("run-from-apk") rather than extracting them to disk, so
         * `applicationInfo.nativeLibraryDir` may not contain a real file to
         * pass to `dlopen()`. This mirrors the real production path where an
         * installed core must exist as an actual file before the native host
         * can load it (LIBRETRO_REFACTOR.md section 7.1) — it is loading
         * mechanics, not a runtime download of executable code.
         */
        fun resolveBundledTestCorePath(context: Context): String {
            val destination = File(context.filesDir, "native_test/libtest_core.so")
            if (destination.exists()) return destination.absolutePath
            destination.parentFile?.mkdirs()

            val abi = Build.SUPPORTED_ABIS.first()
            val entryName = "lib/$abi/libtest_core.so"
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: error("$entryName not found in APK (${context.applicationInfo.sourceDir})")
                zip.getInputStream(entry).use { input ->
                    destination.outputStream().use { output -> input.copyTo(output) }
                }
            }
            return destination.absolutePath
        }
    }
}

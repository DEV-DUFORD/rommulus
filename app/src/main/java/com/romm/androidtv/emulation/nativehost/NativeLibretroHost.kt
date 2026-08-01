package com.romm.androidtv.emulation.nativehost

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import android.view.Surface
import java.io.File
import java.io.IOException
import java.util.zip.CRC32
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

    /**
     * Loads a real core from [corePath] (e.g. an absolute path to
     * `libsameboy_core.so`) with real, already staged-and-verified ROM
     * content at [contentPath] (an absolute, app-private path a caller
     * resolved through the Phase 3 download/cache pipeline — this call
     * never touches the network itself). [systemDir]/[saveDir] are
     * app-private directories exposed to the core via the environment
     * callbacks. Returns false on any failure; see [nativeGetLastError].
     */
    external fun nativeLoadCoreWithContent(
        corePath: String,
        systemDir: String,
        saveDir: String,
        contentPath: String,
    ): Boolean

    /** Stops the emulation thread and unloads the core. Safe to call even if nothing is loaded. */
    external fun nativeStopSession()

    external fun nativeIsRunning(): Boolean

    /**
     * Freezes (true) or resumes (false) the emulation thread's `retro_run()`
     * calls in place (LIBRETRO_REFACTOR.md section 13, Phase 6 pause/quit
     * UI). While paused, video freezes on the last presented frame and audio
     * mutes through the existing underrun-fills-silence path in
     * `AudioOutput` — no separate freeze/mute mechanism exists. The loaded
     * core, its SRAM, and controller input routing are all left untouched;
     * this never stops or tears down the session. Safe to call even if no
     * session is active (no-op).
     */
    external fun nativeSetPaused(paused: Boolean)

    /** True if the emulation thread is currently frozen via [nativeSetPaused]. */
    external fun nativeIsPaused(): Boolean

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

    /**
     * Returns the exact byte-size of the currently loaded core's
     * RETRO_MEMORY_SAVE_RAM region, or 0 when no session is active or the
     * core exposes no save RAM. Consistent with existing JNI APIs that
     * return safe zeroed values under those conditions (e.g. diagnostics).
     */
    external fun nativeGetSramSizeBytes(): Long

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
        fun resolveBundledTestCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libtest_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * SameBoy core (LIBRETRO_REFACTOR.md section 13, Phase 4) — the only
         * entry in [com.romm.androidtv.emulation.model.CoreManifest] with
         * `approved == true` at the time of writing. Same run-from-apk
         * extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledSameBoyCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libsameboy_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * Genesis Plus GX core (LIBRETRO_REFACTOR.md section 13, Phase 7) —
         * approved under the owner's recorded Phase 7 licensing-risk
         * decision (see [com.romm.androidtv.emulation.model.CoreLicenseFinding.ownerRiskAcceptedBy]
         * on its `genesis_plus_gx` [com.romm.androidtv.emulation.model.CoreManifest] entry).
         * Same run-from-apk extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledGenesisPlusGxCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libgenesis_plus_gx_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * Snes9x core (LIBRETRO_REFACTOR.md section 13, Phase 7) — approved
         * under the owner's recorded Phase 7 licensing-risk decision (see
         * [com.romm.androidtv.emulation.model.CoreLicenseFinding.ownerRiskAcceptedBy]
         * on its `snes9x` [com.romm.androidtv.emulation.model.CoreManifest] entry).
         * Same run-from-apk extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledSnes9xCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libsnes9x_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * FCEUmm core (NES) — approved under its own GPL-2.0-or-later license
         * (PERMISSIVE_OR_COPYLEFT_OK, no owner risk acceptance needed).
         * Same run-from-apk extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledFceummCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libfceumm_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * mGBA core (GBA) — approved under its own MPL-2.0 license
         * (PERMISSIVE_OR_COPYLEFT_OK, no owner risk acceptance needed).
         * Same run-from-apk extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledMgbaCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libmgba_core.so")

        /**
         * Resolves an absolute, dlopen-able path to the bundled, approved
         * Stella core (Atari 2600) — approved under its own GPL-2.0-only license
         * (PERMISSIVE_OR_COPYLEFT_OK, no owner risk acceptance needed).
         * Same run-from-apk extraction rationale as [resolveBundledTestCorePath].
         */
        fun resolveBundledStellaCorePath(context: Context): String =
            resolveBundledCoreSharedLibrary(context, "libstella_core.so")

        /**
         * Resolves [coreId] (a [com.romm.androidtv.emulation.model.CoreLicenseFinding.coreId])
         * to its bundled shared-library path, or null if [coreId] has no
         * bundled core in this build. Deliberately does not fall back to any
         * default — an unrecognized coreId must fail the launch explicitly,
         * never silently substitute a different core.
         */
        fun resolveBundledCorePathForCoreId(context: Context, coreId: String): String? = when (coreId) {
            "sameboy" -> resolveBundledSameBoyCorePath(context)
            "genesis_plus_gx" -> resolveBundledGenesisPlusGxCorePath(context)
            "snes9x" -> resolveBundledSnes9xCorePath(context)
            "fceumm" -> resolveBundledFceummCorePath(context)
            "mgba" -> resolveBundledMgbaCorePath(context)
            "stella" -> resolveBundledStellaCorePath(context)
            else -> null
        }

        /**
         * Shared extraction logic for [resolveBundledTestCorePath],
         * [resolveBundledSameBoyCorePath], [resolveBundledGenesisPlusGxCorePath],
         * [resolveBundledSnes9xCorePath], [resolveBundledFceummCorePath],
         * [resolveBundledMgbaCorePath], and [resolveBundledStellaCorePath]:
         * every bundled core is a normal JNI
         * library shipped in the APK, extracted to app-private storage under
         * a name derived from [soFileName] so multiple cores never collide.
         * The APK entry CRC is checked on every resolution so an app update
         * cannot keep loading a stale previously extracted core.
         */
        private fun resolveBundledCoreSharedLibrary(context: Context, soFileName: String): String {
            val destination = File(context.filesDir, "native_cores/$soFileName")
            destination.parentFile?.mkdirs()

            val abi = Build.SUPPORTED_ABIS.first()
            val entryName = "lib/$abi/$soFileName"
            ZipFile(context.applicationInfo.sourceDir).use { zip ->
                val entry = zip.getEntry(entryName)
                    ?: error("$entryName not found in APK (${context.applicationInfo.sourceDir})")
                if (destination.isFile && destination.length() == entry.size && crc32(destination) == entry.crc) {
                    return destination.absolutePath
                }

                val atomicDestination = AtomicFile(destination)
                val output = atomicDestination.startWrite()
                try {
                    zip.getInputStream(entry).use { input -> input.copyTo(output) }
                    atomicDestination.finishWrite(output)
                } catch (error: IOException) {
                    atomicDestination.failWrite(output)
                    throw error
                }
            }
            return destination.absolutePath
        }

        private fun crc32(file: File): Long {
            val crc = CRC32()
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    crc.update(buffer, 0, count)
                }
            }
            return crc.value
        }
    }
}

package com.romm.androidtv.emulation.nativehost

/**
 * JNI boundary to the native Libretro host (`app/src/main/cpp/libretro_host`).
 *
 * Phase 2 (initial commit): only proves the NDK/CMake toolchain builds and
 * runs correctly on both ABIs, in particular on the physical 32-bit Google TV
 * Streamer that motivated this native pivot (LIBRETRO_REFACTOR.md section 1).
 * Core loading, environment callbacks, and the emulation thread are added in
 * follow-up commits; nothing in this class touches ROM content, the network,
 * or WebView, and it is never called from the main app UI in a release build
 * path yet.
 */
class NativeLibretroHost {

    /** Calls into native code and returns a fixed diagnostic string. Proves the JNI round trip. */
    external fun nativePing(): String

    /** True once the native library has loaded successfully. */
    external fun nativeIsAvailable(): Boolean

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
    }
}

package com.romm.androidtv.config

/**
 * Selects which emulation frontend plays a given ROM.
 *
 * This is a Phase 0 policy seam only: adding this enum does not enable native
 * playback. [WEBVIEW] remains the only backend actually wired up in the app,
 * and it must remain the default returned by [PlaybackBackendPolicy] until a
 * native host, approved core, and save-safety rules exist (see
 * LIBRETRO_REFACTOR.md, sections 4.3 and 6).
 */
enum class PlaybackBackend {
    /** RomM's browser-hosted EmulatorJS experience, run inside the app's WebView. */
    WEBVIEW,

    /** Native Libretro core execution in the isolated `:emulation` process. Not implemented yet. */
    NATIVE_LIBRETRO,
}

/**
 * Resolves which [PlaybackBackend] should play a launch request.
 *
 * A `PlaybackBackend` value is a policy input, not permission to bypass save
 * safety. [resolve] must never route a launch to [PlaybackBackend.NATIVE_LIBRETRO]
 * on this build: no native host exists yet. Once one does, resolution must also
 * weigh system capability, ROM identity, and save provenance, and it must block
 * playback rather than silently open a ROM with native-modified SRAM in WebView
 * (LIBRETRO_REFACTOR.md section 4.3).
 */
object PlaybackBackendPolicy {

    /** The safe default backend. Do not change this in Phase 0 or Phase 1. */
    val DEFAULT_BACKEND = PlaybackBackend.WEBVIEW

    /**
     * A global kill switch that forces [PlaybackBackend.WEBVIEW] regardless of any other
     * signal. There is no corresponding switch to force native playback: native launches
     * must be individually approved once the native host exists.
     */
    fun resolve(nativeLaunchesEnabled: Boolean = false): PlaybackBackend {
        if (!nativeLaunchesEnabled) {
            return PlaybackBackend.WEBVIEW
        }
        // Native playback has no implementation yet in this build; never resolve to it.
        return PlaybackBackend.WEBVIEW
    }
}

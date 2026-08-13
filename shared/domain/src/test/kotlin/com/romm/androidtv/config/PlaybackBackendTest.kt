package com.romm.androidtv.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PlaybackBackendTest {

    @Test
    fun `default backend is WebView`() {
        assertThat(PlaybackBackendPolicy.DEFAULT_BACKEND).isEqualTo(PlaybackBackend.WEBVIEW)
    }

    @Test
    fun `resolve returns WebView when native launches are disabled`() {
        assertThat(PlaybackBackendPolicy.resolve(nativeLaunchesEnabled = false))
            .isEqualTo(PlaybackBackend.WEBVIEW)
    }

    @Test
    fun `resolve returns WebView even when native launches are requested`() {
        // No native host exists yet in this build; resolution must never grant
        // native playback regardless of the requested flag (LIBRETRO_REFACTOR.md section 4.3).
        assertThat(PlaybackBackendPolicy.resolve(nativeLaunchesEnabled = true))
            .isEqualTo(PlaybackBackend.WEBVIEW)
    }

    @Test
    fun `resolve defaults to WebView with no arguments`() {
        assertThat(PlaybackBackendPolicy.resolve()).isEqualTo(PlaybackBackend.WEBVIEW)
    }
}

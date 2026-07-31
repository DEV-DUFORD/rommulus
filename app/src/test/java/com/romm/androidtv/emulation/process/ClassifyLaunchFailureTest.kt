package com.romm.androidtv.emulation.process

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ClassifyLaunchFailureTest {

    // ---- CORE_LOAD prefixes ----

    @Test
    fun `classifyLaunchFailure maps dlopen failed to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("dlopen failed: libsameboy.so: not found"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps dlopen failed with detail to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("dlopen failed: location /data/app/core-lib.so: permission denied"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps core API version mismatch to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("core API version mismatch: expected 19, got 18"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps CoreLibrary already loaded to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("CoreLibrary already loaded"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps CoreLibrary already loaded with detail to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("CoreLibrary already loaded: cannot load a second core while one is active"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps session already started to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("session already started"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps session already started with detail to CORE_LOAD`() {
        assertThat(classifyLaunchFailure("session already started: cannot begin a new session"))
            .isEqualTo(LaunchFailureCategory.CORE_LOAD)
    }

    // ---- CONTENT_LOAD prefixes ----

    @Test
    fun `classifyLaunchFailure maps failed to read content file to CONTENT_LOAD`() {
        assertThat(classifyLaunchFailure("failed to read content file: /data/roms/game.gb"))
            .isEqualTo(LaunchFailureCategory.CONTENT_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps failed to read content file with detail to CONTENT_LOAD`() {
        assertThat(classifyLaunchFailure("failed to read content file: No such file or directory"))
            .isEqualTo(LaunchFailureCategory.CONTENT_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps retro_load_game failed to CONTENT_LOAD`() {
        assertThat(classifyLaunchFailure("retro_load_game failed"))
            .isEqualTo(LaunchFailureCategory.CONTENT_LOAD)
    }

    @Test
    fun `classifyLaunchFailure maps retro_load_game failed with detail to CONTENT_LOAD`() {
        assertThat(classifyLaunchFailure("retro_load_game failed: unsupported format"))
            .isEqualTo(LaunchFailureCategory.CONTENT_LOAD)
    }

    // ---- UNKNOWN cases ----

    @Test
    fun `classifyLaunchFailure maps empty string to UNKNOWN`() {
        assertThat(classifyLaunchFailure(""))
            .isEqualTo(LaunchFailureCategory.UNKNOWN)
    }

    @Test
    fun `classifyLaunchFailure maps unrecognized error to UNKNOWN`() {
        assertThat(classifyLaunchFailure("some random crash"))
            .isEqualTo(LaunchFailureCategory.UNKNOWN)
    }

    @Test
    fun `classifyLaunchFailure maps partial prefix match that is not exact to UNKNOWN`() {
        // "dlopen" alone without the colon is NOT a CORE_LOAD match
        assertThat(classifyLaunchFailure("dlopen returned null"))
            .isEqualTo(LaunchFailureCategory.UNKNOWN)

        // "retro_load_game" without "failed" is not CONTENT_LOAD
        assertThat(classifyLaunchFailure("retro_load_game succeeded"))
            .isEqualTo(LaunchFailureCategory.UNKNOWN)

        // "CoreLibrary loading..." is not the same as "CoreLibrary already loaded"
        assertThat(classifyLaunchFailure("CoreLibrary loading: sameboy"))
            .isEqualTo(LaunchFailureCategory.UNKNOWN)
    }
}

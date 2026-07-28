package com.romm.androidtv.emulation.nativehost

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the NDK/CMake toolchain actually produces a working native library
 * on-device, not just that it compiles. This is the highest-risk unknown for
 * the native pivot (LIBRETRO_REFACTOR.md section 1): the physical Google TV
 * Streamer is 32-bit userspace (armeabi-v7a), which is exactly the
 * environment where WebAssembly failed.
 */
@RunWith(AndroidJUnit4::class)
class NativeLibretroHostInstrumentedTest {

    @Test
    fun nativeLibraryLoadsAndRespondsOnDevice() {
        val loaded = NativeLibretroHost.ensureLoaded()
        assertTrue("native library failed to load: ${NativeLibretroHost.lastLoadError()}", loaded)

        val host = NativeLibretroHost()
        assertTrue(host.nativeIsAvailable())
        assertEquals("romm_libretro_host: ok", host.nativePing())
    }
}

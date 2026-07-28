package com.romm.androidtv.emulation.nativehost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
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

    private val host = NativeLibretroHost()

    @After
    fun tearDown() {
        if (NativeLibretroHost.ensureLoaded()) {
            host.nativeStopSession()
        }
    }

    @Test
    fun nativeLibraryLoadsAndRespondsOnDevice() {
        val loaded = NativeLibretroHost.ensureLoaded()
        assertTrue("native library failed to load: ${NativeLibretroHost.lastLoadError()}", loaded)

        assertTrue(host.nativeIsAvailable())
        assertEquals("romm_libretro_host: ok", host.nativePing())
    }

    @Test
    fun loadingAndRunningTheSyntheticTestCoreProducesFramesAndAudio() {
        assertTrue(NativeLibretroHost.ensureLoaded())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corePath = NativeLibretroHost.resolveBundledTestCorePath(context)
        val systemDir = context.filesDir.resolve("system").apply { mkdirs() }.absolutePath
        val saveDir = context.filesDir.resolve("save").apply { mkdirs() }.absolutePath

        val started = host.nativeLoadTestCore(corePath, systemDir, saveDir)
        assertTrue("nativeLoadTestCore failed: ${host.nativeGetLastError()}", started)
        assertTrue(host.nativeIsRunning())

        // Let the emulation thread produce several frames of video and audio
        // at the synthetic core's 60fps / 22050Hz.
        Thread.sleep(500)

        val diagnostics = host.nativeGetDiagnostics()
        val frameCount = diagnostics[0]
        val audioFramesProduced = diagnostics[1]
        val lastWidth = diagnostics[2]
        val lastHeight = diagnostics[3]

        assertTrue("expected several frames after 500ms, got $frameCount", frameCount > 5)
        assertTrue("expected audio frames to be produced, got $audioFramesProduced", audioFramesProduced > 0)
        assertEquals(320L, lastWidth)
        assertEquals(240L, lastHeight)

        host.nativeStopSession()
        assertTrue(!host.nativeIsRunning())
    }
}

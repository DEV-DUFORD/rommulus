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

    @Test
    fun checkpointAndRestoreSramRoundTripsAcrossSessions() {
        assertTrue(NativeLibretroHost.ensureLoaded())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corePath = NativeLibretroHost.resolveBundledTestCorePath(context)
        val systemDir = context.filesDir.resolve("system").apply { mkdirs() }.absolutePath
        val saveDir = context.filesDir.resolve("save_roundtrip").apply { mkdirs() }.absolutePath
        val checkpointPath = context.filesDir.resolve("save_roundtrip/autosave.srm").absolutePath

        // Missing-file restore must fail cleanly, not crash, before any session exists.
        assertTrue(!host.nativeRestoreSaveRam(checkpointPath))

        // Session 1: run long enough for the SRAM byte to increment past its
        // frame-0 initial value of 1 (it increments again at frame 60, ~1s at 60fps).
        assertTrue(host.nativeLoadTestCore(corePath, systemDir, saveDir))
        Thread.sleep(1200)
        assertTrue(host.nativeCheckpointSaveRam(checkpointPath))
        val checkpointedByte = readFirstByte(checkpointPath)
        assertTrue("expected SRAM to have incremented past 1, got $checkpointedByte", checkpointedByte >= 2)
        host.nativeStopSession()

        // Session 2: a fresh core instance re-initializes SRAM to 1 (frame-0
        // increment). Restoring immediately must bring back session 1's value.
        assertTrue(host.nativeLoadTestCore(corePath, systemDir, saveDir))
        val restored = host.nativeRestoreSaveRam(checkpointPath)
        assertTrue("nativeRestoreSaveRam failed: ${host.nativeGetLastError()}", restored)

        val secondCheckpointPath = context.filesDir.resolve("save_roundtrip/after_restore.srm").absolutePath
        assertTrue(host.nativeCheckpointSaveRam(secondCheckpointPath))
        val afterRestoreByte = readFirstByte(secondCheckpointPath)

        // Allow for the emulation thread ticking once more in the brief window
        // between restore and re-checkpoint, but it must never have merely
        // defaulted back to the fresh-init value of 1 — that would mean the
        // restore silently did nothing.
        assertTrue(
            "expected restored value ($afterRestoreByte) >= checkpointed value ($checkpointedByte)",
            afterRestoreByte >= checkpointedByte
        )
    }

    @Test
    fun sramSize_noActiveSession_returnsZero() {
        assertTrue(NativeLibretroHost.ensureLoaded())
        // Ensure no session is active (tearDown from prior test should have cleaned up, but be explicit).
        host.nativeStopSession()
        assertEquals(0L, host.nativeGetSramSizeBytes())
    }

    @Test
    fun sramSize_syntheticCore_returnsExpectedSize() {
        assertTrue(NativeLibretroHost.ensureLoaded())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val corePath = NativeLibretroHost.resolveBundledTestCorePath(context)
        val systemDir = context.filesDir.resolve("system").apply { mkdirs() }.absolutePath
        val saveDir = context.filesDir.resolve("save_sramsize").apply { mkdirs() }.absolutePath

        assertTrue(host.nativeLoadTestCore(corePath, systemDir, saveDir))

        // TEST_CORE_SRAM_SIZE is 64 bytes (test_core.c).
        assertEquals(64L, host.nativeGetSramSizeBytes())

        host.nativeStopSession()
    }

    private fun readFirstByte(path: String): Int {
        val bytes = java.io.File(path).readBytes()
        assertTrue("expected a non-empty SRAM file at $path", bytes.isNotEmpty())
        return bytes[0].toInt() and 0xFF
    }
}

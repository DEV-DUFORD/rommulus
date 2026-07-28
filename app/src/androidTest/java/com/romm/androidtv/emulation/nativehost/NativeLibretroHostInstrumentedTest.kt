package com.romm.androidtv.emulation.nativehost

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

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
        val corePath = resolveTestCorePath(context)
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

    /**
     * The synthetic core .so is packaged as a normal JNI library, but this
     * device runs native libraries directly from the APK ("run-from-apk"
     * mode) rather than extracting them to a real file, so
     * `applicationInfo.nativeLibraryDir` doesn't contain an actual file to
     * `dlopen()`. Extract the current ABI's copy to an app-private file once,
     * mirroring the real production path where a downloaded/installed core
     * must exist as a real file before the native host can `dlopen()` it
     * (LIBRETRO_REFACTOR.md section 7.1: "Package cores with the APK; do not
     * fetch executable code" — this is the loading mechanics, not a download).
     */
    private fun resolveTestCorePath(context: android.content.Context): String {
        val destination = File(context.filesDir, "native_test/libtest_core.so")
        if (destination.exists()) return destination.absolutePath
        destination.parentFile?.mkdirs()

        val abi = android.os.Build.SUPPORTED_ABIS.first()
        val entryName = "lib/$abi/libtest_core.so"
        java.util.zip.ZipFile(context.applicationInfo.sourceDir).use { zip ->
            val entry = zip.getEntry(entryName)
                ?: error("$entryName not found in APK (${context.applicationInfo.sourceDir})")
            zip.getInputStream(entry).use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return destination.absolutePath
    }
}

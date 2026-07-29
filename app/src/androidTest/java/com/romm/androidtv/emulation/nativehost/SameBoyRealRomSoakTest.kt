package com.romm.androidtv.emulation.nativehost

import android.content.Context
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.network.RommOkHttpClient
import com.romm.androidtv.romm.RomRepositoryImpl
import com.romm.androidtv.romm.StagingOutcome
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4 exit criteria (LIBRETRO_REFACTOR.md section 13, "First approved
 * real core"): runs the real, approved SameBoy core against a real, staged,
 * user-owned RomM ROM for a sustained duration on the physical device,
 * sampling frame/audio/underrun counters throughout, then verifies real-core
 * SRAM (RETRO_MEMORY_SAVE_RAM) survives a stop/reload/restore cycle. This
 * exercises exactly the native pipeline `EmulationActivity` itself drives,
 * without needing UI automation.
 *
 * This is deliberately a network-touching, real-server, real-duration test —
 * not part of the CI-safe unit suite. It requires this device to already
 * hold a valid RomM session (see [com.romm.androidtv.romm.RealRomArchiveStagingDeviceTest]).
 *
 * Duration defaults to a short smoke value; pass `-e durationMinutes 30` (an
 * instrumentation argument) to run the actual 30-minute soak this exit
 * criterion requires. ROM ID is overridable via `-e romId <id>`. A previously
 * staged raw ROM can be reused with `-e contentPath <app-private-path>` when
 * isolating native-host behavior from the real-server staging prerequisite.
 */
@RunWith(AndroidJUnit4::class)
class SameBoyRealRomSoakTest {

    private val host = NativeLibretroHost()

    @After
    fun tearDown() {
        if (NativeLibretroHost.ensureLoaded()) {
            host.nativeStopSession()
        }
    }

    @Test
    fun realRomRunsAtNominalSpeedForTheConfiguredDurationAndSramSurvivesRestore() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val context = instrumentation.targetContext
        val durationMinutes = args.getString("durationMinutes")?.toDoubleOrNull() ?: 0.5
        val romId = args.getString("romId")?.toLongOrNull() ?: DEFAULT_ROM_ID

        val contentPath = args.getString("contentPath")
            ?.also { check(File(it).isFile) { "contentPath does not exist or is not a file: $it" } }
            ?: stageRealRom(context, romId)
        val corePath = NativeLibretroHost.resolveBundledSameBoyCorePath(context)
        val systemDir = File(context.filesDir, "soak_test/system").apply { mkdirs() }.absolutePath
        val saveDir = File(context.filesDir, "soak_test/save").apply { mkdirs() }.absolutePath
        val checkpointPath = File(context.filesDir, "soak_test/save/autosave.srm").absolutePath

        check(NativeLibretroHost.ensureLoaded()) { "native library failed to load: ${NativeLibretroHost.lastLoadError()}" }

        val started = host.nativeLoadCoreWithContent(corePath, systemDir, saveDir, contentPath)
        check(started) { "nativeLoadCoreWithContent failed: ${host.nativeGetLastError()}" }
        check(host.nativeIsRunning()) { "core reported not running immediately after a successful load" }

        val totalMillis = (durationMinutes * 60_000).toLong()
        val sampleIntervalMillis = 30_000L
        var elapsed = 0L
        var previousFrameCount = 0L
        var minObservedFps = Double.MAX_VALUE
        var maxUnderruns = 0L
        var maxOverruns = 0L

        println("SameBoyRealRomSoakTest: starting ${durationMinutes}-minute soak, romId=$romId, contentPath=$contentPath")

        while (elapsed < totalMillis) {
            val sleepMillis = minOf(sampleIntervalMillis, totalMillis - elapsed)
            Thread.sleep(sleepMillis)
            elapsed += sleepMillis

            val diagnostics = host.nativeGetDiagnostics()
            val frameCount = diagnostics[0]
            val audioFramesProduced = diagnostics[1]
            val coreRequestedShutdown = diagnostics[5]
            val audioUnderrunFrames = diagnostics[6]
            val audioOverrunFrames = diagnostics[7]
            val pssKb = Debug.getPss()

            val framesThisSample = frameCount - previousFrameCount
            val observedFps = framesThisSample.toDouble() / (sleepMillis / 1000.0)
            minObservedFps = minOf(minObservedFps, observedFps)
            maxUnderruns = maxOf(maxUnderruns, audioUnderrunFrames)
            maxOverruns = maxOf(maxOverruns, audioOverrunFrames)
            previousFrameCount = frameCount

            println(
                "SameBoyRealRomSoakTest: t=${elapsed / 1000}s frameCount=$frameCount " +
                    "observedFps=%.1f audioFramesProduced=$audioFramesProduced ".format(observedFps) +
                    "underruns=$audioUnderrunFrames overruns=$audioOverrunFrames pssKb=$pssKb"
            )

            check(host.nativeIsRunning()) { "core stopped running unexpectedly at t=${elapsed / 1000}s" }
            check(coreRequestedShutdown == 0L) { "core requested shutdown unexpectedly at t=${elapsed / 1000}s" }
            check(audioFramesProduced > 0) { "no audio frames produced by t=${elapsed / 1000}s" }
        }

        // Game Boy's real hardware refresh rate is ~59.73fps. Allow a generous band since this
        // instrumentation loop is not itself real-time critical — the point is proving the core
        // sustains roughly nominal speed continuously, not measuring exact frame pacing.
        check(minObservedFps in 40.0..90.0) {
            "expected roughly nominal (~59.7fps) Game Boy speed throughout, worst sample was %.1ffps".format(minObservedFps)
        }
        println(
            "SameBoyRealRomSoakTest: soak complete — minObservedFps=%.1f maxUnderruns=$maxUnderruns maxOverruns=$maxOverruns"
                .format(minObservedFps)
        )

        // Real-core SRAM survives a checkpoint/stop/reload/restore cycle (section 11.1), exactly
        // like NativeLibretroHostInstrumentedTest proves for the synthetic core, but with SameBoy
        // and a real, user-owned ROM's actual battery-backed save RAM.
        check(host.nativeCheckpointSaveRam(checkpointPath)) { "nativeCheckpointSaveRam failed: ${host.nativeGetLastError()}" }
        val checkpointedBytes = File(checkpointPath).readBytes()
        check(checkpointedBytes.isNotEmpty()) { "expected non-empty SRAM checkpoint for a real Game Boy cartridge ROM" }
        host.nativeStopSession()

        check(host.nativeLoadCoreWithContent(corePath, systemDir, saveDir, contentPath)) {
            "nativeLoadCoreWithContent (reload) failed: ${host.nativeGetLastError()}"
        }
        check(host.nativeRestoreSaveRam(checkpointPath)) { "nativeRestoreSaveRam failed: ${host.nativeGetLastError()}" }

        val afterRestorePath = File(context.filesDir, "soak_test/save/after_restore.srm").absolutePath
        check(host.nativeCheckpointSaveRam(afterRestorePath)) { "post-restore nativeCheckpointSaveRam failed: ${host.nativeGetLastError()}" }
        val afterRestoreBytes = File(afterRestorePath).readBytes()

        check(afterRestoreBytes.contentEquals(checkpointedBytes)) {
            "SRAM after restore (${afterRestoreBytes.size} bytes) did not match the checkpoint taken before " +
                "stop (${checkpointedBytes.size} bytes) — restore did not faithfully round-trip real SRAM"
        }
        println("SameBoyRealRomSoakTest: SRAM round-trip verified (${checkpointedBytes.size} bytes)")
    }

    /** Stages a real, user-owned RomM ROM through the actual Phase 3 pipeline, reusing this device's existing session. */
    private fun stageRealRom(context: Context, romId: Long): String {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val sessionStore = SessionStore(context.getSharedPreferences(SessionStore.PREFS_NAME, Context.MODE_PRIVATE))
        val session = checkNotNull(sessionStore.current()) {
            "No RomM session on record on this device — log in via the app's Login screen first, then re-run this test."
        }
        instrumentation.runOnMainSync {
            RommOkHttpClient.cookieSyncJar.importFromWebView(session.origin)
        }
        val client = RommOkHttpClient.build()
        val cacheRoot = File(context.filesDir, "soak_test_cache").apply { mkdirs() }
        val cache = ContentCache(cacheRoot, CacheDatabase(File(cacheRoot, "index.json")))
        val repo = RomRepositoryImpl(client, sessionStore, cache)

        val outcome = runBlocking { repo.stageForLaunch(romId) }
        val success = outcome as? StagingOutcome.Success
            ?: error("expected StagingOutcome.Success staging ROM $romId, got $outcome")
        return checkNotNull(success.launchSpec.contentPath) { "Success outcome had a null contentPath" }
    }

    private companion object {
        const val DEFAULT_ROM_ID = 20742L
    }
}

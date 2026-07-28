package com.romm.androidtv.emulation.nativehost

import android.content.Context
import android.net.ConnectivityManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.network.RommOkHttpClient
import com.romm.androidtv.romm.RomRepositoryImpl
import com.romm.androidtv.romm.StagingOutcome
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4 deferred exit check (LIBRETRO_REFACTOR.md section 13, "First
 * approved real core"): proves real SameBoy SRAM survives a checkpoint,
 * clean quit, and a **full app restart with no network access** — not just
 * the in-process reload [SameBoyRealRomSoakTest] already proves.
 *
 * A single instrumentation run cannot demonstrate a genuine process restart
 * (the test's own process would have to survive the thing it's testing).
 * Instead this class has two independent `@Test` methods, meant to be run as
 * two *separate* `adb shell am instrument` invocations — each such invocation
 * is its own fresh Android process, so running them back to back with
 * airplane mode enabled for the second is a real "force-quit, disable
 * network, relaunch" cycle, not a simulation:
 *
 * ```
 * # Phase 1 — online: stage a real ROM once, play briefly, checkpoint SRAM,
 * # then cleanly stop the session. Records the resolved content path for
 * # phase 2's exclusive use.
 * adb shell am instrument -w -e romId 20742 \
 *   -e class com.romm.androidtv.emulation.nativehost.SameBoyOfflineRestartSramTest#onlinePhase1_stageCheckpointAndQuit \
 *   com.romm.androidtv.test/androidx.test.runner.AndroidJUnitRunner
 *
 * # Disable network before phase 2 (airplane mode; adjust for this device):
 * adb shell settings put global airplane_mode_on 1
 * adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true
 *
 * # Phase 2 — offline: fresh process, no network, reload the same core/save
 * # paths, restore SRAM, and verify it byte-matches what phase 1 checkpointed.
 * adb shell am instrument -w \
 *   -e class com.romm.androidtv.emulation.nativehost.SameBoyOfflineRestartSramTest#offlinePhase2_restartRestoresSram \
 *   com.romm.androidtv.test/androidx.test.runner.AndroidJUnitRunner
 *
 * # Restore network afterward:
 * adb shell settings put global airplane_mode_on 0
 * adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false
 * ```
 *
 * Both phases share fixed, well-known app-private paths under
 * `filesDir/offline_restart_test/` so phase 2 needs no information from
 * phase 1 beyond what's already on disk (mirroring how a real app restart
 * would only ever have its own persisted state to work from, never anything
 * held in a since-dead process's memory).
 */
@RunWith(AndroidJUnit4::class)
class SameBoyOfflineRestartSramTest {

    private val host = NativeLibretroHost()

    @Test
    fun onlinePhase1_stageCheckpointAndQuit() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val romId = InstrumentationRegistry.getArguments().getString("romId")?.toLongOrNull() ?: DEFAULT_ROM_ID

        val contentPath = stageRealRom(context, romId)
        contentPathMarkerFile(context).apply { parentFile?.mkdirs() }.writeText(contentPath)

        check(NativeLibretroHost.ensureLoaded()) { "native library failed to load: ${NativeLibretroHost.lastLoadError()}" }
        val corePath = NativeLibretroHost.resolveBundledSameBoyCorePath(context)
        val systemDir = systemDir(context).apply { mkdirs() }.absolutePath
        val saveDir = saveDir(context).apply { mkdirs() }.absolutePath

        val started = host.nativeLoadCoreWithContent(corePath, systemDir, saveDir, contentPath)
        check(started) { "nativeLoadCoreWithContent failed: ${host.nativeGetLastError()}" }
        check(host.nativeIsRunning()) { "core reported not running immediately after a successful load" }

        // Run briefly so real Game Boy SRAM has plausible content, then checkpoint it — this
        // mirrors EmulationActivity.onPause()'s real checkpoint-on-pause behavior, followed by a
        // clean quit (EmulationActivity.onDestroy()'s checkpoint-then-nativeStopSession).
        Thread.sleep(5_000)
        check(host.nativeIsRunning()) { "core stopped running unexpectedly before the checkpoint" }

        val checkpointPath = checkpointPath(context)
        checkpointPath.parentFile?.mkdirs()
        check(host.nativeCheckpointSaveRam(checkpointPath.absolutePath)) {
            "nativeCheckpointSaveRam failed: ${host.nativeGetLastError()}"
        }
        val checkpointedBytes = checkpointPath.readBytes()
        check(checkpointedBytes.isNotEmpty()) { "expected non-empty SRAM checkpoint for a real Game Boy cartridge ROM" }

        host.nativeStopSession()
        println(
            "SameBoyOfflineRestartSramTest: phase 1 complete — checkpointed ${checkpointedBytes.size} bytes " +
                "at ${checkpointPath.absolutePath}, contentPath=$contentPath. Now disable network and run " +
                "offlinePhase2_restartRestoresSram in a fresh instrumentation invocation."
        )
    }

    @Test
    fun offlinePhase2_restartRestoresSram() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext

        assertDeviceHasNoNetworkAccess(context)

        val contentPath = contentPathMarkerFile(context).let {
            check(it.isFile) {
                "no recorded contentPath from phase 1 — run onlinePhase1_stageCheckpointAndQuit first"
            }
            it.readText().trim()
        }
        check(File(contentPath).isFile) { "phase 1's recorded contentPath no longer exists on disk: $contentPath" }

        val checkpointPath = checkpointPath(context)
        check(checkpointPath.isFile) { "no phase 1 checkpoint found at ${checkpointPath.absolutePath}" }
        val checkpointedBytes = checkpointPath.readBytes()
        check(checkpointedBytes.isNotEmpty()) { "phase 1's checkpoint file was unexpectedly empty" }

        // A brand-new NativeLibretroHost/JNI session in a brand-new process — this process has
        // never touched the network, exactly like a real cold app restart with airplane mode on.
        check(NativeLibretroHost.ensureLoaded()) { "native library failed to load: ${NativeLibretroHost.lastLoadError()}" }
        val corePath = NativeLibretroHost.resolveBundledSameBoyCorePath(context)
        val systemDir = systemDir(context).absolutePath
        val saveDir = saveDir(context).absolutePath

        val started = host.nativeLoadCoreWithContent(corePath, systemDir, saveDir, contentPath)
        check(started) { "nativeLoadCoreWithContent (restart) failed: ${host.nativeGetLastError()}" }
        check(host.nativeIsRunning()) { "core reported not running immediately after restart" }

        // Restore-on-launch, exactly like EmulationActivity.onCreate() does for every real launch.
        check(host.nativeRestoreSaveRam(checkpointPath.absolutePath)) {
            "nativeRestoreSaveRam failed after restart: ${host.nativeGetLastError()}"
        }

        Thread.sleep(2_000)
        check(host.nativeIsRunning()) { "core stopped running unexpectedly after restore" }

        val afterRestorePath = File(context.filesDir, "offline_restart_test/after_restore.srm")
        check(host.nativeCheckpointSaveRam(afterRestorePath.absolutePath)) {
            "post-restore nativeCheckpointSaveRam failed: ${host.nativeGetLastError()}"
        }
        val afterRestoreBytes = afterRestorePath.readBytes()

        check(afterRestoreBytes.contentEquals(checkpointedBytes)) {
            "SRAM after offline restart (${afterRestoreBytes.size} bytes) did not match phase 1's pre-quit " +
                "checkpoint (${checkpointedBytes.size} bytes) — offline restart did not faithfully restore real SRAM"
        }

        host.nativeStopSession()
        println(
            "SameBoyOfflineRestartSramTest: phase 2 complete — offline restart restored " +
                "${afterRestoreBytes.size} bytes of SRAM byte-exactly, with no network access this process."
        )
    }

    /** Fails loudly (rather than silently passing) if this device actually has network access right now. */
    private fun assertDeviceHasNoNetworkAccess(context: Context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork
        val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
        val hasInternet = capabilities?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        check(!hasInternet) {
            "this device currently reports validated network access — enable airplane mode " +
                "(adb shell settings put global airplane_mode_on 1 && " +
                "adb shell am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true) " +
                "before running offlinePhase2_restartRestoresSram, otherwise this check proves nothing."
        }
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
        val cacheRoot = File(context.filesDir, "offline_restart_test_cache").apply { mkdirs() }
        val cache = ContentCache(cacheRoot, CacheDatabase(File(cacheRoot, "index.json")))
        val repo = RomRepositoryImpl(client, sessionStore, cache)

        val outcome = runBlocking { repo.stageForLaunch(romId) }
        val success = outcome as? StagingOutcome.Success
            ?: error("expected StagingOutcome.Success staging ROM $romId, got $outcome")
        return checkNotNull(success.launchSpec.contentPath) { "Success outcome had a null contentPath" }
    }

    private companion object {
        const val DEFAULT_ROM_ID = 20742L

        fun systemDir(context: Context) = File(context.filesDir, "offline_restart_test/system")
        fun saveDir(context: Context) = File(context.filesDir, "offline_restart_test/save")
        fun checkpointPath(context: Context) = File(context.filesDir, "offline_restart_test/save/autosave.srm")
        fun contentPathMarkerFile(context: Context) = File(context.filesDir, "offline_restart_test/content_path.txt")
    }
}

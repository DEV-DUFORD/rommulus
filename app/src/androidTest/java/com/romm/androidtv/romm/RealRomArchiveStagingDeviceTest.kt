package com.romm.androidtv.romm

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.network.RommOkHttpClient
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Phase 4 device verification (LIBRETRO_REFACTOR.md section 13): confirms,
 * against the REAL RomM server this device is already authenticated against,
 * that the archive-extraction fix (see HANDOFF.md "Phase 4 progress and next
 * steps") actually lands raw ROM bytes for an archived (.zip/.7z) single-file
 * ROM, not just a downloaded-and-hash-verified container.
 *
 * This is deliberately a network-touching, real-server test — not part of
 * the CI-safe unit suite. It requires this device to already hold a valid
 * RomM session (Android's WebView CookieManager already has session cookies
 * from a prior native login via the app's Login screen) and fails fast with
 * a clear message if that precondition isn't met, rather than silently
 * skipping. The ROM ID under test is overridable via the instrumentation
 * argument `romId` (`-e romId <id>`) so a second archived ROM can be tried
 * without editing/recompiling this file.
 */
@RunWith(AndroidJUnit4::class)
class RealRomArchiveStagingDeviceTest {

    @Test
    fun stagingAKnownArchivedRomExtractsRawRomBytes() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val sessionStore = SessionStore(context.getSharedPreferences(SessionStore.PREFS_NAME, Context.MODE_PRIVATE))

        val session = checkNotNull(sessionStore.current()) {
            "No RomM session on record on this device — log in via the app's Login screen first, then re-run this test."
        }

        // Pull this device's real session cookies from Android's WebView CookieManager (where the
        // native login flow syncs them) into OkHttp's in-memory cookie jar for this client.
        // CookieManager access must happen on the main thread.
        instrumentation.runOnMainSync {
            RommOkHttpClient.cookieSyncJar.importFromWebView(session.origin)
        }
        val client = RommOkHttpClient.build()

        val cacheRoot = File(context.filesDir, "device-test-cache").apply { mkdirs() }
        val cache = ContentCache(cacheRoot, CacheDatabase(File(cacheRoot, "index.json")))
        val repo = RomRepositoryImpl(client, sessionStore, cache)

        val romId = InstrumentationRegistry.getArguments().getString("romId")?.toLongOrNull()
            ?: DEFAULT_ARCHIVED_ROM_ID

        val metadata = runBlocking { repo.fetchRomMetadata(romId) }
        println("RealRomArchiveStagingDeviceTest: metadata for ROM $romId = $metadata")

        val outcome = runBlocking { repo.stageForLaunch(romId) }
        println("RealRomArchiveStagingDeviceTest: stageForLaunch($romId) = $outcome")

        when (outcome) {
            is StagingOutcome.Success -> {
                val file = File(outcome.launchSpec.contentPath ?: error("Success outcome had a null contentPath"))
                check(file.isFile) { "expected extracted content file to exist at ${file.absolutePath}" }
                check(file.length() > 0) { "expected non-empty extracted content at ${file.absolutePath}, was empty" }
                println(
                    "RealRomArchiveStagingDeviceTest: SUCCESS — extracted raw content at " +
                        "${file.absolutePath} (${file.length()} bytes), romHash=${outcome.launchSpec.romHash}"
                )
            }
            else -> error(
                "expected StagingOutcome.Success for ROM $romId, got $outcome — " +
                    "if this is a CorruptedDownload SHA-1 mismatch, see HANDOFF.md's note on ROM 20742's " +
                    "separate, pre-existing upstream metadata problem; try a different archived ROM ID via " +
                    "'-e romId <id>' instead of assuming this extractor regressed."
            )
        }
    }

    private companion object {
        const val DEFAULT_ARCHIVED_ROM_ID = 20742L
    }
}

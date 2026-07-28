package com.romm.androidtv.emulation.process

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.romm.androidtv.emulation.nativehost.NativeLibretroHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs native Libretro emulation in the dedicated `:emulation` process
 * declared in AndroidManifest.xml (LIBRETRO_REFACTOR.md section 6).
 *
 * Phase 2: only loads the app-owned synthetic test core, reachable solely
 * from a debug-only diagnostics entry point in `MainActivity`. There is no
 * real ROM-launch flow yet — [PlaybackBackend] still always resolves to
 * `WEBVIEW`, and this activity never fetches, downloads, or receives ROM
 * bytes. This activity performs no network access and never touches
 * WebView, satisfying the Phase 2 exit criterion that the emulation process
 * is isolated from both.
 *
 * Process-level launch guard: [isSessionActive] is a simple in-process
 * atomic flag (this activity's process only ever hosts one activity
 * instance, and `singleTask` routes repeat launches to [onNewIntent]).
 * Combined with the native [NativeLibretroHost]'s own atomic
 * compare-and-set process-slot guard, a second concurrent session is
 * rejected at two independent layers.
 */
class EmulationActivity : ComponentActivity() {

    private val host = NativeLibretroHost()
    private var sessionStarted = false
    private var savePath: String? = null

    companion object {
        private const val TAG = "EmulationActivity"
        private val isSessionActive = AtomicBoolean(false)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!isSessionActive.compareAndSet(false, true)) {
            Log.w(TAG, "onCreate: a session is already active in this process — refusing to start a second one")
            reportPlayerBusyAndFinish()
            return
        }

        if (!NativeLibretroHost.ensureLoaded()) {
            Log.e(TAG, "Native library failed to load: ${NativeLibretroHost.lastLoadError()}")
            isSessionActive.set(false)
            finish()
            return
        }

        val corePath = NativeLibretroHost.resolveBundledTestCorePath(applicationContext)
        val systemDir = filesDir.resolve("system").apply { mkdirs() }.absolutePath
        val saveDir = filesDir.resolve("save").apply { mkdirs() }.absolutePath
        val savePath = filesDir.resolve("save/test_core_autosave.srm").absolutePath

        sessionStarted = host.nativeLoadTestCore(corePath, systemDir, saveDir)
        if (!sessionStarted) {
            Log.e(TAG, "nativeLoadTestCore failed: ${host.nativeGetLastError()}")
        } else {
            // Restore-on-launch (LIBRETRO_REFACTOR.md section 11.1). A missing file
            // (first launch) or a size mismatch both return false; either way SRAM
            // stays at whatever retro_load_game() initialized it to, which is the
            // correct and safe default.
            val restored = host.nativeRestoreSaveRam(savePath)
            Log.d(TAG, "restore-on-launch: restored=$restored path=$savePath")
        }

        this.savePath = savePath

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EmulationDiagnosticsScreen(
                        host = host,
                        sessionStarted = sessionStarted,
                        lastError = host.nativeGetLastError(),
                        onStop = { finish() }
                    )
                }
            }
        }
    }

    /**
     * A repeated launch intent while this activity is already running
     * (singleTask) must not replace the live session
     * (LIBRETRO_REFACTOR.md section 6: "onNewIntent reports 'player busy';
     * it must not replace a live launch.").
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.w(TAG, "onNewIntent: player busy, ignoring repeated launch request")
    }

    private fun reportPlayerBusyAndFinish() {
        // No caller-facing result channel exists yet in Phase 2 (no real
        // launch flow); this is a placeholder for the "player busy" report
        // LIBRETRO_REFACTOR.md section 6 requires once ROM launches exist.
        finish()
    }

    private fun checkpointIfRunning() {
        if (!sessionStarted) return
        val path = savePath ?: return
        val checkpointed = host.nativeCheckpointSaveRam(path)
        Log.d(TAG, "checkpoint: success=$checkpointed path=$path")
    }

    override fun onPause() {
        // Checkpoint on pause, not just on destroy: LIBRETRO_REFACTOR.md section
        // 11.1 requires checkpointing "on pause or quit", so a task switch or
        // screen-off doesn't lose progress if the process is later killed outright
        // (onDestroy is not guaranteed to run in that case).
        checkpointIfRunning()
        super.onPause()
    }

    override fun onDestroy() {
        checkpointIfRunning()
        if (sessionStarted) {
            host.nativeStopSession()
            sessionStarted = false
        }
        isSessionActive.set(false)
        super.onDestroy()
    }
}

@Composable
private fun EmulationDiagnosticsScreen(
    host: NativeLibretroHost,
    sessionStarted: Boolean,
    lastError: String,
    onStop: () -> Unit
) {
    var diagnostics by remember { mutableStateOf(longArrayOf(0, 0, 0, 0, -1, 0)) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionStarted) {
        if (!sessionStarted) return@LaunchedEffect
        while (true) {
            diagnostics = host.nativeGetDiagnostics()
            delay(200)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Native Emulation Diagnostics (Phase 2 debug)",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        if (!sessionStarted) {
            Text("Session failed to start: $lastError", color = Color(0xFFf44336))
        } else {
            Text("Frame count: ${diagnostics[0]}", color = Color.White)
            Text("Audio frames produced: ${diagnostics[1]}", color = Color.White)
            Text("Last geometry: ${diagnostics[2]}x${diagnostics[3]}", color = Color.White)
            Text("Pixel format: ${diagnostics[4]}", color = Color.White)
            Text(
                text = if (diagnostics[5] != 0L) "Core requested shutdown" else "Running",
                color = if (diagnostics[5] != 0L) Color(0xFFff9800) else Color(0xFF4caf50)
            )
        }

        Button(onClick = onStop) {
            Text("Stop and return")
        }
    }

    LaunchedEffect(diagnostics[5]) {
        if (sessionStarted && diagnostics[5] != 0L) {
            scope.launch { onStop() }
        }
    }
}


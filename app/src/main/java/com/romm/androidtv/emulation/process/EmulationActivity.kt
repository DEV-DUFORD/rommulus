package com.romm.androidtv.emulation.process

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import com.romm.androidtv.controller.LibretroInputAdapter
import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.router.ControllerEventRouter
import com.romm.androidtv.emulation.model.AdoptionResult
import com.romm.androidtv.emulation.model.CandidateAdoptionHelper
import com.romm.androidtv.emulation.model.CandidateExtras
import com.romm.androidtv.emulation.model.CandidateSaveMetadata
import com.romm.androidtv.emulation.model.DescriptorState
import com.romm.androidtv.emulation.model.EmulationResult
import com.romm.androidtv.emulation.model.FilesystemCandidateAdoptionHelper
import com.romm.androidtv.emulation.model.LaunchSessionJournal
import com.romm.androidtv.emulation.model.SaveBackupStore
import com.romm.androidtv.emulation.model.SessionDescriptorPatch
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.emulation.nativehost.NativeLibretroHost
import com.romm.androidtv.emulation.video.EmulationSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
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
    private var sessionIdForJournal: String? = null
    private var candidateMetadata: CandidateSaveMetadata? = null
    private var stageRomId: Long = -1L
    private var stageRomHash: String = ""
    /** SHA-256 hex of the last checkpointed SRAM, computed immediately after checkpointing. */
    @Volatile
    private var checkpointedHash: String? = null

    // This activity runs in its own process (:emulation), so it cannot share
    // MainActivity's ControllerEventRouter instance — each owns its own,
    // exactly mirroring MainActivity's own registration/dispatch pattern
    // (LIBRETRO_REFACTOR.md section 9: "Reuse ControllerEventRouter and
    // GamepadSnapshot; do not route native play through JavaScript"). This
    // class only calls the router's existing public API; none of its
    // internal button/axis/slot logic is modified.
    private val controllerRouter: ControllerEventRouter by lazy { ControllerEventRouter() }

    // Translates the router's four-slot snapshots into Libretro RetroPad
    // input and pushes them to the native input_state callback.
    private val inputAdapter: LibretroInputAdapter by lazy {
        LibretroInputAdapter(controllerRouter) { ports ->
            val buttonMasks = IntArray(ControllerSlot.SLOT_COUNT)
            val analogValues = IntArray(ControllerSlot.SLOT_COUNT * 4)
            ports.forEachIndexed { port, state ->
                buttonMasks[port] = state.buttonsMask
                analogValues[port * 4 + 0] = state.leftX
                analogValues[port * 4 + 1] = state.leftY
                analogValues[port * 4 + 2] = state.rightX
                analogValues[port * 4 + 3] = state.rightY
            }
            host.nativeUpdateInputState(buttonMasks, analogValues)
        }
    }

    companion object {
        private const val TAG = "EmulationActivity"
        private val isSessionActive = AtomicBoolean(false)

        /**
         * Intent extras for a real-content launch (LIBRETRO_REFACTOR.md
         * section 6, step 7: "starts EmulationActivity with a small parcel
         * containing manifest and session IDs, not ROM bytes"). The caller
         * (`MainActivity`, in the main process) resolves every one of these
         * through the Phase 3 staging pipeline and [SavePathPolicy] *before*
         * starting this activity — this activity only ever receives already-
         * validated, app-private paths and IDs, never ROM bytes or a raw
         * server URL. Omitting these extras entirely falls back to the
         * existing Phase 2/3 synthetic-test-core debug flow.
         */
        const val EXTRA_CORE_ID = "com.romm.androidtv.emulation.EXTRA_CORE_ID"
        const val EXTRA_CONTENT_PATH = "com.romm.androidtv.emulation.EXTRA_CONTENT_PATH"
        const val EXTRA_SAVE_PATH = "com.romm.androidtv.emulation.EXTRA_SAVE_PATH"
        const val EXTRA_ROM_ID = "com.romm.androidtv.emulation.EXTRA_ROM_ID"
        /** Authoritative app launch session ID (UUID string from LaunchSpec.sessionId). Required for journal/result correlation. */
        const val EXTRA_APP_SESSION_ID = "com.romm.androidtv.emulation.EXTRA_APP_SESSION_ID"
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

        val corePath: String
        val contentPath: String?
        val systemDir = filesDir.resolve("system").apply { mkdirs() }.absolutePath
        val saveDir = filesDir.resolve("save").apply { mkdirs() }.absolutePath
        val savePath: String
        val romId = intent.getLongExtra(EXTRA_ROM_ID, -1L)
        val coreId = intent.getStringExtra(EXTRA_CORE_ID)
        val requestedContentPath = intent.getStringExtra(EXTRA_CONTENT_PATH)
        val requestedSavePath = intent.getStringExtra(EXTRA_SAVE_PATH)

        // Phase B: extract optional candidate metadata from AwaitingCoreValidation.
        this.candidateMetadata = CandidateExtras.extractFromIntent(intent)
        if (this.candidateMetadata != null) {
            Log.i(TAG, "onCreate: candidate save detected, will validate post-core-load")
        }

        // Phase B: Use the authoritative app launch session ID from the intent extra.
        // This is the UUID from LaunchSpec.sessionId — NOT the RomM sync session ID.
        val appSessionId = intent.getStringExtra(EXTRA_APP_SESSION_ID)
            ?: run {
                Log.w(TAG, "onCreate: missing EXTRA_APP_SESSION_ID, falling back to timestamp")
                System.currentTimeMillis().toString()
            }
        this.sessionIdForJournal = appSessionId
        val journalDir = filesDir.resolve("launch_sessions").apply { mkdirs() }
        val journal = LaunchSessionJournal(journalDir)
        try {
            // Persist authoritative ROM/core identity in the journal for post-death recovery.
            val candidateMeta = this.candidateMetadata
            if (candidateMeta != null && romId > 0) {
                this.stageRomId = candidateMeta.romId
                this.stageRomHash = candidateMeta.romHash
                journal.createOrGet(appSessionId)
                // Patch identity immediately so process-death recovery has exact values.
                journal.patchIdentity(appSessionId, SessionDescriptorPatch(
                    rommSessionId = candidateMeta.rommSessionId,
                    romId = candidateMeta.romId,
                    romHash = candidateMeta.romHash,
                    coreId = candidateMeta.coreId,
                    coreBuildRevision = candidateMeta.coreBuildRevision,
                    serverContentHash = candidateMeta.serverContentHash,
                ))
            } else {
                this.stageRomId = if (romId > 0) romId else -1L
                this.stageRomHash = ""
                journal.createOrGet(appSessionId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "onCreate: failed to create journal entry", e)
        }

        if (coreId != null && requestedContentPath != null && requestedSavePath != null) {
            val resolvedCorePath = NativeLibretroHost.resolveBundledCorePathForCoreId(applicationContext, coreId)
            if (resolvedCorePath == null) {
                Log.e(TAG, "onCreate: no bundled core for coreId=$coreId")
                isSessionActive.set(false)
                finish()
                return
            }
            corePath = resolvedCorePath
            contentPath = requestedContentPath
            savePath = requestedSavePath
            File(savePath).parentFile?.mkdirs()
            Log.i(TAG, "onCreate: real-content launch coreId=$coreId romId=$romId content=$contentPath")

            // Phase B: validate candidate path is app-private before proceeding.
            this.candidateMetadata?.let { meta ->
                val validation = meta.validateAppPrivate(filesDir)
                if (validation.isFailure) {
                    Log.e(TAG, "onCreate: candidate path validation failed: ${validation.exceptionOrNull()}")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(errorDetail = "candidate path escaped app-private dir")) } catch (_: Exception) {}
                    isSessionActive.set(false)
                    finish()
                    return
                }
            }
        } else {
            corePath = NativeLibretroHost.resolveBundledTestCorePath(applicationContext)
            contentPath = null
            savePath = filesDir.resolve("save/test_core_autosave.srm").absolutePath
        }

        sessionStarted = if (contentPath != null) {
            host.nativeLoadCoreWithContent(corePath, systemDir, saveDir, contentPath)
        } else {
            host.nativeLoadTestCore(corePath, systemDir, saveDir)
        }
        if (!sessionStarted) {
            Log.e(TAG, "core load failed: ${host.nativeGetLastError()}")
            try { journal.advance(this.sessionIdForJournal!!, DescriptorState.CRASHED, SessionDescriptorPatch(errorDetail = host.nativeGetLastError())) } catch (_: Exception) {}
        } else {
            // Phase B: advance journal to CORE_LOADED with canonical path and candidate size.
            try {
                val nativeSramSize = host.nativeGetSramSizeBytes()
                if (nativeSramSize > 0L) {
                    Log.d(TAG, "onCreate: JNI reported expectedSramSizeBytes=$nativeSramSize")
                }
                journal.advance(this.sessionIdForJournal!!, DescriptorState.CORE_LOADED,
                    SessionDescriptorPatch(
                        canonicalSavePath = savePath,
                        rommSaveId = this.candidateMetadata?.rommSaveId,
                        candidateDownloadedSizeBytes = this.candidateMetadata?.downloadedSizeBytes,
                        expectedSramSizeBytes = if (nativeSramSize > 0L) nativeSramSize else null,
                    ))
            } catch (e: Exception) {
                Log.w(TAG, "onCreate: journal advance to CORE_LOADED failed", e)
            }

            // Phase B: candidate validation — delegates to interface-driven CandidateAdoptionHelper
            // for backup-before-restore ordering (LIBRETRO_REFACTOR.md Phase B).
            val adoptionResult = this.candidateMetadata?.let { meta ->
                FilesystemCandidateAdoptionHelper().adoptCandidate(
                    candidateMetadata = meta,
                    canonicalSavePath = savePath,
                    nativeSramSizeBytes = host.nativeGetSramSizeBytes(),
                    backupStore = EmulationSaveBackupStore(filesDir),
                    nativeRestore = { path -> host.nativeRestoreSaveRam(path) },
                    nativeCheckpoint = { path -> host.nativeCheckpointSaveRam(path) },
                )
            }

            val candidateAdopted = when (adoptionResult) {
                is AdoptionResult.Adopted -> {
                    Log.i(TAG, "candidate-validation: adopted → canonical, hash=${adoptionResult.checkpointedHash} size=${adoptionResult.checkpointedSizeBytes}")
                    if (adoptionResult.backupPath != null) {
                        Log.i(TAG, "candidate-validation: canonical backed up to ${adoptionResult.backupPath}")
                    } else {
                        Log.d(TAG, "candidate-validation: no canonical local copy to back up")
                    }
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.ADOPTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        rommSaveId = this.candidateMetadata!!.rommSaveId,
                        checkpointedHash = adoptionResult.checkpointedHash,
                        expectedSramSizeBytes = adoptionResult.checkpointedSizeBytes.takeIf { it > 0L },
                    )) } catch (_: Exception) {}
                    true
                }
                is AdoptionResult.RejectedSizeMismatch -> {
                    Log.w(TAG, "candidate-validation: size mismatch — native=${adoptionResult.nativeSramSizeBytes} downloaded=${adoptionResult.downloadedSizeBytes}, rejecting candidate")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        errorDetail = "size-mismatch: native=${adoptionResult.nativeSramSizeBytes} downloaded=${adoptionResult.downloadedSizeBytes}",
                    )) } catch (_: Exception) {}
                    false
                }
                is AdoptionResult.BackupFailed -> {
                    Log.e(TAG, "candidate-validation: canonical backup failed — aborting adoption, preserving candidate: ${adoptionResult.error}")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        errorDetail = "canonical backup failed: ${adoptionResult.error}",
                    )) } catch (_: Exception) {}
                    false
                }
                is AdoptionResult.RestoreFailed -> {
                    Log.e(TAG, "candidate-validation: native restore failed — preserving candidate and canonical prior copy: ${adoptionResult.error}")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        errorDetail = "candidate restore failed",
                    )) } catch (_: Exception) {}
                    false
                }
                is AdoptionResult.CheckpointFailed -> {
                    Log.e(TAG, "candidate-validation: checkpoint failed — preserving candidate and canonical prior copy: ${adoptionResult.error}")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        errorDetail = "canonical checkpoint failed",
                    )) } catch (_: Exception) {}
                    false
                }
                is AdoptionResult.NoSram -> {
                    Log.w(TAG, "candidate-validation: core exposes no SRAM (size=${adoptionResult.nativeSramSizeBytes}), rejecting candidate")
                    false
                }
                is AdoptionResult.UnexpectedError -> {
                    Log.e(TAG, "candidate-validation: unexpected error: ${adoptionResult.error}")
                    try { journal.advance(this.sessionIdForJournal!!, DescriptorState.REJECTED, SessionDescriptorPatch(
                        candidatePath = this.candidateMetadata!!.candidatePath,
                        errorDetail = "validation exception: ${adoptionResult.error}",
                    )) } catch (_: Exception) {}
                    false
                }
                null -> true // No candidate → treated as successfully launched.
            }

            if (candidateAdopted) {
                Log.d(TAG, "candidate adopted, skipping normal restore-on-launch")
            } else if (this.candidateMetadata != null) {
                Log.i(TAG, "candidate rejected, falling through to normal restore-on-launch")
                val restored = host.nativeRestoreSaveRam(savePath)
                Log.d(TAG, "restore-on-launch (post-rejection): restored=$restored path=$savePath")
            } else {
                // Normal no-candidate launch: existing restore-on-launch behavior unchanged.
                val restored = host.nativeRestoreSaveRam(savePath)
                Log.d(TAG, "restore-on-launch: restored=$restored path=$savePath")
            }

            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
            inputManager.registerInputDeviceListener(controllerRouter, null)
            controllerRouter.attachLifecycle(this)
            controllerRouter.enumerateExistingDevices(inputManager)
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    controllerRouter.enumerateExistingDevices(inputManager)
                }
            })
            inputAdapter.start(lifecycleScope)
        }

        this.savePath = savePath

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EmulationScreen(
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
        // Phase B: write rejected result and finish with EmulationResult.
        val sid = sessionIdForJournal ?: return finish()
        try {
            val journalDir = filesDir.resolve("launch_sessions")
            val journal = LaunchSessionJournal(journalDir)
            journal.advance(sid, DescriptorState.REJECTED, SessionDescriptorPatch(errorDetail = "player busy"))
        } catch (_: Exception) {}
        setResult(android.app.Activity.RESULT_CANCELED, Intent("com.romm.androidtv.emulation.RESULT").apply {
            putExtra("session_id", sid)
            putExtra("rejected_reason", "another session is already active")
        })
        finish()
    }

    private fun checkpointIfRunning(): Boolean {
        if (!sessionStarted) return false
        val path = savePath ?: return false
        val checkpointed = host.nativeCheckpointSaveRam(path)
        Log.d(TAG, "checkpoint: success=$checkpointed path=$path")

        // Compute hash immediately after successful checkpoint — bounded work (small SRAM file),
        // avoids synchronous readBytes/hash later in onDestroy. Hash is persisted via @Volatile
        // so setResult can read it safely from any thread.
        if (checkpointed) {
            try {
                val bytes = File(path).readBytes()
                checkpointedHash = sha256Hex(bytes)
            } catch (e: Exception) {
                Log.w(TAG, "checkpointIfRunning: failed to compute hash", e)
                checkpointedHash = null
            }
        } else {
            checkpointedHash = null
        }

        return checkpointed
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
        val checkpointed = checkpointIfRunning()
        inputAdapter.stop()
        try {
            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(controllerRouter)
        } catch (_: Exception) {
            // Mirrors MainActivity's own defensive onDestroy unregistration —
            // the listener may already be gone if the session never
            // fully started.
        }
        if (sessionStarted) {
            host.nativeStopSession()
            sessionStarted = false
        }

        // Phase B: compute and deliver EmulationResult via setResult.
        val sid = sessionIdForJournal ?: return isSessionActive.also { it.set(false) }.let { super.onDestroy(); return }
        try {
            val journalDir = filesDir.resolve("launch_sessions")
            val journal = LaunchSessionJournal(journalDir)
            val descriptor = journal.read(sid)

            when (descriptor?.state) {
                DescriptorState.ADOPTED -> {
                    // Candidate was adopted during onCreate; checkpointed hash is in the descriptor.
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = savePath,
                            checkpointedSaveHash = descriptor.checkpointedHash,
                        )
                    ))
                }
                DescriptorState.REJECTED -> {
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = if (checkpointed) savePath else null,
                            checkpointedSaveHash = checkpointedHash.takeIf { checkpointed },
                        )
                    ))
                }
                else -> {
                    // Normal completion (no candidate or crashed state).
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = if (checkpointed) savePath else null,
                            checkpointedSaveHash = checkpointedHash.takeIf { checkpointed && savePath != null },
                        )
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "onDestroy: failed to write result intent", e)
        }

        isSessionActive.set(false)
        super.onDestroy()
    }

    /**
     * Builds an Intent carrying [EmulationResult.Completed] as extras for setResult.
     * The main process reads these via ActivityResultLauncher.
     * Includes romId/romHash so the caller can validate launch identity matches result identity.
     */
    private fun buildResultIntent(result: EmulationResult.Completed): Intent {
        return Intent("com.romm.androidtv.emulation.RESULT").apply {
            putExtra("session_id", result.sessionId)
            result.checkpointedSavePath?.let { putExtra("checkpointed_save_path", it) }
            result.checkpointedSaveHash?.let { putExtra("checkpointed_save_hash", it) }
            if (stageRomId > 0L) putExtra("rom_id", stageRomId)
            if (stageRomHash.isNotBlank()) putExtra("rom_hash", stageRomHash)
        }
    }

    /**
     * Controller input routing while this activity is foregrounded
     * (LIBRETRO_REFACTOR.md section 9): Android Back stays reserved for
     * this activity's own handling (never consumed by the controller
     * router), and game-controller events are routed to the four-slot
     * router — which [inputAdapter] then feeds to the native input_state
     * callback — exactly mirroring MainActivity's own dispatch policy.
     */
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (sessionStarted) {
            val consumed = controllerRouter.onKeyEvent(event)
            if (consumed) return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == 0
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (sessionStarted) {
            val consumed = controllerRouter.onMotionEvent(event)
            if (consumed) return true
        }
        return super.dispatchGenericMotionEvent(event)
    }
}

@Composable
private fun EmulationScreen(
    host: NativeLibretroHost,
    sessionStarted: Boolean,
    lastError: String,
    onStop: () -> Unit
) {
    var diagnostics by remember { mutableStateOf(LongArray(20).also { it[4] = -1 }) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(sessionStarted) {
        if (!sessionStarted) return@LaunchedEffect
        while (true) {
            diagnostics = host.nativeGetDiagnostics()
            delay(200)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sessionStarted) {
            // The video surface is the primary visible content
            // (LIBRETRO_REFACTOR.md section 8.1); diagnostics render as a
            // semi-transparent overlay on top of it, not in place of it.
            EmulationSurface(
                host = host,
                coreWidth = diagnostics[2].toInt(),
                coreHeight = diagnostics[3].toInt(),
                modifier = Modifier.fillMaxSize()
            )
        }

        EmulationDiagnosticsOverlay(
            sessionStarted = sessionStarted,
            lastError = lastError,
            diagnostics = diagnostics,
            onStop = onStop
        )
    }

    LaunchedEffect(diagnostics[5]) {
        if (sessionStarted && diagnostics[5] != 0L) {
            scope.launch { onStop() }
        }
    }
}

@Composable
private fun EmulationDiagnosticsOverlay(
    sessionStarted: Boolean,
    lastError: String,
    diagnostics: LongArray,
    onStop: () -> Unit
) {
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
            Text("Audio underrun frames: ${diagnostics[6]}", color = Color.White)
            Text("Audio overrun frames: ${diagnostics[7]}", color = Color.White)
            // Live per-port RetroPad button masks and left-stick analog
            // (LIBRETRO_REFACTOR.md section 9): the synthetic test core
            // only ever reads port 0, but a physical controller may be
            // assigned any of the four ports depending on what else the OS
            // enumerates as a controller-like input source, so all four
            // are shown to make the controller feed verifiable regardless
            // of port.
            Text(
                text = "Ports (button mask hex): " +
                    "P0=0x${diagnostics[8].toString(16)} " +
                    "P1=0x${diagnostics[9].toString(16)} " +
                    "P2=0x${diagnostics[10].toString(16)} " +
                    "P3=0x${diagnostics[11].toString(16)}",
                color = Color.White
            )
            Text(
                text = "Ports (left stick X,Y): " +
                    "P0=(${diagnostics[12]},${diagnostics[13]}) " +
                    "P1=(${diagnostics[14]},${diagnostics[15]}) " +
                    "P2=(${diagnostics[16]},${diagnostics[17]}) " +
                    "P3=(${diagnostics[18]},${diagnostics[19]})",
                color = Color.White
            )
            Text(
                text = if (diagnostics[5] != 0L) "Core requested shutdown" else "Running",
                color = if (diagnostics[5] != 0L) Color(0xFFff9800) else Color(0xFF4caf50)
            )
        }

        Button(onClick = onStop) {
            Text("Stop and return")
        }
    }
}

/**
 * Filesystem-only [SaveBackupStore] for use in the `:emulation` process.
 * No Room, no network — only local filesystem operations via [com.romm.androidtv.emulation.model.SavePathPolicy].
 * Delegates to the same backup logic as [FileSaveContentStore] but scoped to the
 * emulation process's available dependencies.
 */
private class EmulationSaveBackupStore(private val filesDir: java.io.File) : SaveBackupStore {

    private fun autosavePath(serverKey: String, userKey: String, romId: Long, romHash: String): String =
        com.romm.androidtv.emulation.model.SavePathPolicy.autosaveSramPath(filesDir, serverKey, userKey, romId, romHash)

    override fun readCanonical(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? {
        val file = java.io.File(autosavePath(serverKey, userKey, romId, romHash))
        return if (file.isFile) file.readBytes() else null
    }

    override fun backupCanonical(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        candidateIdentifier: Long,
        nowEpochMs: Long,
    ): String {
        val canonicalFile = java.io.File(autosavePath(serverKey, userKey, romId, romHash))
        if (!canonicalFile.isFile) throw IOException("No canonical file to back up at ${canonicalFile.absolutePath}")

        val backupDir = java.io.File(canonicalFile.parentFile?.parentFile, "candidate-backups")
        backupDir.mkdirs()

        // Idempotent: check for existing backup for this candidate identifier.
        val existingBackup = backupDir.listFiles { f ->
            f.name.startsWith("pre-adoption-${candidateIdentifier}-") && f.extension == "srm"
        }?.firstOrNull()

        if (existingBackup != null && existingBackup.isFile) {
            return existingBackup.absolutePath
        }

        val backupFile = java.io.File(backupDir, "pre-adoption-${candidateIdentifier}-${nowEpochMs}.srm")
        canonicalFile.copyTo(backupFile, overwrite = false)

        // Verify durability.
        if (!backupFile.readBytes().contentEquals(canonicalFile.readBytes())) {
            backupFile.delete()
            throw IOException("Backup verification failed: content mismatch")
        }
        return backupFile.absolutePath
    }

    override fun readBackup(backupPath: String): ByteArray? {
        val file = java.io.File(backupPath)
        return if (file.isFile) file.readBytes() else null
    }

    override fun writeCanonicalAtomically(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
    ) {
        val target = java.io.File(autosavePath(serverKey, userKey, romId, romHash))
        target.parentFile?.mkdirs()
        val temp = java.io.File(target.parentFile, "${target.name}.tmp")
        java.io.FileOutputStream(temp).use { out ->
            out.write(bytes)
            out.flush()
            out.fd.sync()
        }
        if (!temp.renameTo(target)) {
            target.parentFile?.mkdirs()
            java.io.RandomAccessFile(target, "rw").use { raf ->
                raf.setLength(0)
                raf.write(temp.readBytes())
                raf.fd.sync()
            }
            temp.delete()
        }
    }
}


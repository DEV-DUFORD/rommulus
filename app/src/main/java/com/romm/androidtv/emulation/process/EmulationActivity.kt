package com.romm.androidtv.emulation.process

import android.content.Intent
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
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
 * atomic flag checked in [onCreate], rejecting a second concurrent launch
 * with [reportPlayerBusyAndFinish]. This activity intentionally uses the
 * default ("standard") launchMode rather than singleTask/singleInstance:
 * MainActivity launches it via an ActivityResultLauncher to receive the
 * EmulationResult, and the platform never delivers activity results to a
 * singleTask/singleInstance target, so the guard here — not launchMode —
 * is what prevents a second concurrent session. Combined with the native
 * [NativeLibretroHost]'s own atomic compare-and-set process-slot guard, a
 * second concurrent session is rejected at two independent layers.
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
    /**
     * Wall-clock start of this play session (captured once in [onCreate]), passed back to the
     * caller in the result [Intent] so it can report a completed play session to
     * `POST /api/play-sessions` — the only mechanism that advances the server's
     * `rom_user.last_played`, which drives the RomM Home screen's "Continue Playing" row.
     */
    private var sessionStartEpochMs: Long = -1L

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

    /**
     * Emits on every remote/controller key press so [EmulationScreen] can transiently reveal the
     * hold-to-exit back-hint icon (never persistent "debug info" — it fades out on its own).
     */
    private val keyActivityEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /**
     * Tracks whether the remote's Back key is currently physically held down. The fill/exit
     * animation itself is driven from inside [EmulationScreen] (a `LaunchedEffect` there has
     * access to Compose's MonotonicFrameClock); driving an `Animatable` from `lifecycleScope`
     * instead throws `IllegalStateException: A MonotonicFrameClock is not available` because that
     * scope is never part of a composition frame — that crash was the earlier "back exits
     * instantly with no animation" bug (the whole `:emulation` process crashed on Back press).
     */
    private val backKeyHeld = MutableStateFlow(false)

    /** Elapsed-realtime timestamp of the most recent Back ACTION_DOWN, used to distinguish a quick tap from a hold. */
    private var backKeyDownAtMs: Long = 0L

    /**
     * Emits on a Back press that is released *before* [BACK_HOLD_DURATION_MS] elapses — a
     * "quick tap" — which opens/closes the pause menu (LIBRETRO_REFACTOR.md section 13, Phase
     * 6). This is entirely separate from [backKeyHeld]/[BACK_HOLD_DURATION_MS]'s hold-to-exit
     * gesture, which remains the sole direct-quit path and is unchanged by this feature.
     */
    private val quickBackTapEvents = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )

    /** Whether the native pause-menu overlay is currently shown; drives [NativeLibretroHost.nativeSetPaused]. */
    private val pauseMenuVisible = MutableStateFlow(false)

    /**
     * Set when [finishAndDeliverResult] is called on an active session but [checkpointIfRunning]
     * fails — blocks the quit until the user explicitly retries or chooses to quit without
     * saving, rather than silently discarding the failed checkpoint (previously silently
     * swallowed). See [finishAndDeliverResult].
     */
    private val saveFailureVisible = MutableStateFlow(false)

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

        sessionStartEpochMs = System.currentTimeMillis()

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
            Log.i(TAG, "telemetry: native session failed coreId=${coreId ?: "test_core"} romId=$stageRomId sessionId=$appSessionId category=${classifyLaunchFailure(host.nativeGetLastError())}")
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

            // Telemetry (LIBRETRO_REFACTOR.md section 13, Phase 6 exit criterion: "telemetry
            // identifies which native core/system ran"). No PII/credentials involved — just the
            // resolved core/rom identity, correlated with the launch session ID.
            Log.i(TAG, "telemetry: native session started coreId=${coreId ?: "test_core"} romId=$stageRomId sessionId=$appSessionId")

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
                // No candidate at all (the common case: negotiate returned no_op/upload, not
                // download) is NOT "adopted" — it must still fall through to the normal
                // restore-on-launch branch below, which is what actually loads the existing
                // local autosave into the core's SRAM. Returning `true` here was the bug: it
                // made onCreate() silently skip host.nativeRestoreSaveRam(savePath) on every
                // ordinary relaunch, so a correctly-uploaded/checkpointed local save file was
                // never loaded back into the core and the game always started fresh.
                null -> false
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
                        failureCategory = if (!sessionStarted) classifyLaunchFailure(host.nativeGetLastError()) else LaunchFailureCategory.NONE,
                        keyActivityEvents = keyActivityEvents,
                        backKeyHeld = backKeyHeld,
                        quickBackTapEvents = quickBackTapEvents,
                        pauseMenuVisible = pauseMenuVisible,
                        saveFailureVisible = saveFailureVisible,
                        onStop = { finishAndDeliverResult() },
                        onQuitAnywayAfterSaveFailure = { finishAndDeliverResult(forceQuitOnSaveFailure = true) },
                        onSetNativePaused = { paused -> host.nativeSetPaused(paused) },
                        onOpenPauseMenuCheckpoint = { checkpointForPauseMenu() },
                    )
                }
            }
        }
    }

    /**
     * Defensive no-op: with the default ("standard") launchMode this activity
     * uses, the platform always creates a fresh instance per launch, so
     * onNewIntent() should never actually be invoked here. Kept only as a
     * safety net in case a future caller ever adds FLAG_ACTIVITY_SINGLE_TOP
     * or similar — the real double-launch guard is [isSessionActive] in
     * onCreate().
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

    /**
     * Silent local-only checkpoint taken when the pause menu opens — not shown to the user (no
     * "save status" UI) and, importantly, not uploaded to the RomM server: server sync only
     * happens after this activity finishes (see [finishAndDeliverResult]/[deliverResult]), which
     * is deliberate — the server's save-slot autoclean only keeps 5 prior slots, and those slots
     * are reserved for meaningful on-exit saves rather than being churned by every pause. This is
     * purely a local safety net (e.g. against a later improper app kill).
     */
    private fun checkpointForPauseMenu() {
        checkpointIfRunning()
    }

    override fun onPause() {
        // Checkpoint on pause, not just on destroy: LIBRETRO_REFACTOR.md section
        // 11.1 requires checkpointing "on pause or quit", so a task switch or
        // screen-off doesn't lose progress if the process is later killed outright
        // (onDestroy is not guaranteed to run in that case).
        checkpointIfRunning()
        super.onPause()
    }

    /**
     * Checkpoints, reads the journal descriptor, calls setResult() with the final
     * EmulationResult, and only THEN calls finish(). Order matters: the Android
     * platform locks in whatever result was set via setResult() at the moment
     * finish() is invoked — calling setResult() reactively from onPause()/onStop()/
     * onDestroy() (all of which only run as a consequence of finish() having already
     * been called) is too late and silently delivers the stale default result
     * (RESULT_CANCELED, no data) to the caller's ActivityResultLauncher callback.
     * This was empirically confirmed on-device: setResult() completing before the
     * caller's callback fired was NOT sufficient — finish() must be called after
     * setResult(), from the same call site.
     */
    private fun finishAndDeliverResult(forceQuitOnSaveFailure: Boolean = false) {
        val checkpointed = checkpointIfRunning()
        if (!checkpointed && sessionStarted && !forceQuitOnSaveFailure) {
            // Do not silently discard a failed checkpoint at quit time — block with a native
            // "save failed" screen so the user can retry or make an informed choice to quit
            // anyway (LIBRETRO_REFACTOR.md section 13, Phase 6 error screens).
            Log.w(TAG, "finishAndDeliverResult: checkpoint failed at quit, blocking for user decision")
            saveFailureVisible.value = true
            return
        }
        saveFailureVisible.value = false
        deliverResult(checkpointed)
        finish()
    }

    /**
     * Reads the journal descriptor and calls setResult() with the final EmulationResult.
     * Must be called before finish() (see [finishAndDeliverResult]) — never call this
     * from a lifecycle callback alone, as by the time onPause()/onDestroy() run in
     * response to finish(), the result has already been locked in.
     */
    private fun deliverResult(checkpointed: Boolean) {
        Log.i(TAG, "deliverResult: sessionIdForJournal=${sessionIdForJournal != null} checkpointed=$checkpointed")
        val sid = sessionIdForJournal ?: return
        try {
            val journalDir = filesDir.resolve("launch_sessions")
            val journal = LaunchSessionJournal(journalDir)
            val descriptor = journal.read(sid)
            Log.i(TAG, "deliverResult: descriptor.state=${descriptor?.state}")

            when (descriptor?.state) {
                DescriptorState.ADOPTED -> {
                    // Candidate was adopted during onCreate; checkpointed hash is in the descriptor.
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = savePath,
                            checkpointedSaveHash = descriptor.checkpointedHash,
                            startEpochMs = sessionStartEpochMs,
                            endEpochMs = System.currentTimeMillis(),
                        )
                    ))
                }
                DescriptorState.REJECTED -> {
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = if (checkpointed) savePath else null,
                            checkpointedSaveHash = checkpointedHash.takeIf { checkpointed },
                            startEpochMs = sessionStartEpochMs,
                            endEpochMs = System.currentTimeMillis(),
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
                            startEpochMs = sessionStartEpochMs,
                            endEpochMs = System.currentTimeMillis(),
                        )
                    ))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "deliverResult: failed to write result intent", e)
        }
        Log.i(TAG, "deliverResult: finished")
    }

    override fun onDestroy() {
        checkpointIfRunning()
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
            if (result.startEpochMs > 0L) putExtra("play_session_start_epoch_ms", result.startEpochMs)
            if (result.endEpochMs > 0L) putExtra("play_session_end_epoch_ms", result.endEpochMs)
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
        // The Xbox/game controller must never trigger the back-hint icon or the hold-to-exit
        // gesture — only the TV remote should. IMPORTANT: this Android TV box (Google TV
        // Streamer) routes the physical remote's Back/Home/Select buttons through a
        // "virtual-remote" input device whose source bitmask is KEYBOARD | DPAD | GAMEPAD —
        // it legitimately carries the GAMEPAD bit (confirmed via `adb shell dumpsys input`).
        // Excluding on GAMEPAD (as a first attempt did) wrongly filtered out the real remote too.
        //
        // JOYSTICK looked like the right differentiator (the Xbox controller device reports
        // KEYBOARD | GAMEPAD | STYLUS | JOYSTICK; the remote never does) but `event.source` on
        // an individual button-press KeyEvent only reflects the source *class of that specific
        // event* — a gamepad face-button press reports source = KEYBOARD | GAMEPAD without the
        // JOYSTICK bit, even though the originating device supports it (JOYSTICK is only set on
        // continuous analog-stick MotionEvents, e.g. the D-pad-as-hat-axis case, which explains
        // why D-pad presses were correctly excluded but face buttons weren't). The reliable
        // signal is the full originating InputDevice's capability bitmask (confirmed via
        // `adb shell dumpsys input`), not the per-event source.
        val deviceSources = event.device?.sources ?: event.source
        val isRemoteSource = (deviceSources and InputDevice.SOURCE_JOYSTICK) == 0

        if (isRemoteSource) {
            keyActivityEvents.tryEmit(Unit)

            if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                // Exiting gameplay is a deliberate hold-to-confirm gesture, never a single tap, so
                // an errant Back press from the TV remote can never discard an in-progress
                // session. The paired visual countdown lives in EmulationScreen's back-hint icon.
                // A quick tap (released before BACK_HOLD_DURATION_MS) instead toggles the native
                // pause menu (LIBRETRO_REFACTOR.md section 13, Phase 6) — the hold gesture below
                // remains the sole direct-quit path, unchanged.
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> if (event.repeatCount == 0) {
                        backKeyHeld.value = true
                        backKeyDownAtMs = SystemClock.elapsedRealtime()
                    }
                    KeyEvent.ACTION_UP -> {
                        backKeyHeld.value = false
                        val heldMs = SystemClock.elapsedRealtime() - backKeyDownAtMs
                        if (heldMs < BACK_HOLD_DURATION_MS && sessionStarted) {
                            quickBackTapEvents.tryEmit(Unit)
                        }
                    }
                }
                return true
            }
        }
        if (sessionStarted && !pauseMenuVisible.value) {
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
        if (sessionStarted && !pauseMenuVisible.value) {
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
    failureCategory: LaunchFailureCategory,
    keyActivityEvents: Flow<Unit>,
    backKeyHeld: Flow<Boolean>,
    quickBackTapEvents: Flow<Unit>,
    pauseMenuVisible: MutableStateFlow<Boolean>,
    saveFailureVisible: Flow<Boolean>,
    onStop: () -> Unit,
    onQuitAnywayAfterSaveFailure: () -> Unit,
    onSetNativePaused: (Boolean) -> Unit,
    onOpenPauseMenuCheckpoint: () -> Unit,
) {
    // Diagnostics are polled silently to drive the core-requested-shutdown auto-stop below; none
    // of it is rendered on screen — gameplay should show only the game itself.
    var diagnostics by remember { mutableStateOf(LongArray(20).also { it[4] = -1 }) }
    var showBackHint by remember { mutableStateOf(false) }
    // Owned entirely inside this composition: a LaunchedEffect's coroutine has access to
    // Compose's MonotonicFrameClock, which Animatable.animateTo requires. Driving this same
    // Animatable from the Activity's lifecycleScope instead throws
    // "IllegalStateException: A MonotonicFrameClock is not available" and crashes the whole
    // :emulation process — that was the earlier "back exits instantly, no animation" bug.
    val backHoldProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val pauseMenuOpen by pauseMenuVisible.collectAsState()
    val saveFailureShown by saveFailureVisible.collectAsState(initial = false)

    LaunchedEffect(sessionStarted) {
        if (!sessionStarted) return@LaunchedEffect
        while (true) {
            diagnostics = host.nativeGetDiagnostics()
            delay(200)
        }
    }

    // Reveal the hold-to-exit back-hint icon on any remote/controller key press; collectLatest
    // restarts the idle-hide delay on every fresh press so the icon stays up while interacting.
    LaunchedEffect(Unit) {
        keyActivityEvents.collectLatest {
            showBackHint = true
            delay(BACK_HINT_IDLE_HIDE_MS)
            showBackHint = false
        }
    }

    // Drives the fill/unfill of the hold-to-exit ring. collectLatest cancels the in-flight
    // animateTo the instant the held state flips, so releasing Back early reverses the fill
    // instead of completing it; only a fill that reaches 1f (the full hold duration) exits.
    LaunchedEffect(Unit) {
        backKeyHeld.collectLatest { held ->
            if (held) {
                backHoldProgress.animateTo(1f, tween(durationMillis = BACK_HOLD_DURATION_MS, easing = LinearEasing))
                onStop()
            } else {
                backHoldProgress.animateTo(0f, tween(durationMillis = 150))
            }
        }
    }

    // A quick Back tap (released before the hold-to-exit threshold) toggles the pause menu.
    LaunchedEffect(Unit) {
        quickBackTapEvents.collectLatest {
            pauseMenuVisible.value = !pauseMenuVisible.value
        }
    }

    // Freeze/unfreeze the native session whenever the pause menu opens/closes, and take a silent
    // local-only checkpoint on open as a safety net (not shown to the user, not uploaded to the
    // server until this session actually ends — see [EmulationActivity.checkpointForPauseMenu]).
    LaunchedEffect(pauseMenuOpen) {
        onSetNativePaused(pauseMenuOpen)
        if (pauseMenuOpen) {
            onOpenPauseMenuCheckpoint()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sessionStarted) {
            // The video surface is the only visible content during normal play.
            EmulationSurface(
                host = host,
                coreWidth = diagnostics[2].toInt(),
                coreHeight = diagnostics[3].toInt(),
                modifier = Modifier.fillMaxSize()
            )
        } else {
            NativeErrorScreen(category = failureCategory, lastError = lastError, onBackToLibrary = onStop)
        }

        if (showBackHint || backHoldProgress.value > 0f) {
            BackHintIcon(
                progress = backHoldProgress.value,
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp)
            )
        }

        if (pauseMenuOpen && sessionStarted) {
            PauseMenuOverlay(
                onResume = { pauseMenuVisible.value = false },
                onQuit = onStop,
            )
        }

        if (saveFailureShown) {
            SaveFailureOverlay(
                onRetry = onStop,
                onQuitAnyway = onQuitAnywayAfterSaveFailure,
            )
        }
    }

    LaunchedEffect(diagnostics[5]) {
        if (sessionStarted && diagnostics[5] != 0L) {
            scope.launch { onStop() }
        }
    }
}

/** How long the back-hint icon stays visible after the most recent key press with no hold in progress. */
private const val BACK_HINT_IDLE_HIDE_MS = 2500L

/** How long the remote's Back key must be held to confirm exiting gameplay. */
private const val BACK_HOLD_DURATION_MS = 1200

/** Coarse category used to select which native error screen to show. */
enum class LaunchFailureCategory { NONE, CORE_LOAD, CONTENT_LOAD, UNKNOWN }

/**
 * Classifies [NativeLibretroHost.nativeGetLastError]'s message into a [LaunchFailureCategory] so
 * distinct native error screens (LIBRETRO_REFACTOR.md section 13, Phase 6) can be shown for a
 * core-load failure (bad/missing core binary, ABI mismatch) versus a content-load failure (core
 * loaded fine but rejected this specific ROM file). Coupled to the exact prefixes emulation_session.cpp
 * and core_library.cpp assign to `lastError_` — if those native strings change, update this too.
 */
private fun classifyLaunchFailure(lastError: String): LaunchFailureCategory = when {
    lastError.startsWith("dlopen failed:") ||
        lastError.startsWith("core API version mismatch:") ||
        lastError.startsWith("CoreLibrary already loaded") ||
        lastError.startsWith("session already started") -> LaunchFailureCategory.CORE_LOAD
    lastError.startsWith("failed to read content file:") ||
        lastError.startsWith("retro_load_game failed") -> LaunchFailureCategory.CONTENT_LOAD
    else -> LaunchFailureCategory.UNKNOWN
}

/**
 * Full-screen native error state for an in-session launch failure — always offers a way back to
 * the library, never a WebView hand-off (LIBRETRO_REFACTOR.md section 1 amendment: WebView is
 * deprecated, not a maintained fallback).
 */
@Composable
private fun NativeErrorScreen(category: LaunchFailureCategory, lastError: String, onBackToLibrary: () -> Unit) {
    val (title, message) = when (category) {
        LaunchFailureCategory.CORE_LOAD -> "Emulator core failed to load" to
            "The native emulator core for this system could not be started. This usually means the app build is missing or has a broken core binary."
        LaunchFailureCategory.CONTENT_LOAD -> "This game could not be loaded" to
            "The emulator core started, but rejected this specific game file. It may be corrupt or in an unsupported format for this core."
        LaunchFailureCategory.UNKNOWN, LaunchFailureCategory.NONE -> "Something went wrong starting this game" to
            "An unexpected error prevented this session from starting."
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, color = Color.White, style = MaterialTheme.typography.headlineSmall)
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = message, color = Color(0xFFbdbdbd), style = MaterialTheme.typography.bodyMedium)
        if (lastError.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = lastError, color = Color(0xFFf44336), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onBackToLibrary) { Text("Back to Library") }
    }
}

/**
 * Blocking screen shown when a checkpoint fails at quit time, so a failed save is never silently
 * discarded (LIBRETRO_REFACTOR.md section 13, Phase 6 error screens).
 */
@Composable
private fun SaveFailureOverlay(onRetry: () -> Unit, onQuitAnyway: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(text = "Save failed", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "The game's save data could not be checkpointed. Quitting now may lose recent progress. " +
                    "You can retry, or quit anyway and keep the last successful save.",
                color = Color(0xFFbdbdbd),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                Button(onClick = onRetry) { Text("Retry") }
                Spacer(modifier = Modifier.width(16.dp))
                OutlinedButton(onClick = onQuitAnyway) { Text("Quit anyway") }
            }
        }
    }
}

/**
 * Native pause-menu overlay (LIBRETRO_REFACTOR.md section 13, Phase 6): Resume and Quit (with
 * confirm) — the sole in-session menu, opened by a quick Back tap. Emulation is frozen (native
 * `paused_` flag) for the whole time this is visible; see [EmulationScreen]'s
 * `LaunchedEffect(pauseMenuOpen)`, which also takes a silent local-only save checkpoint on open
 * (not surfaced here — see [EmulationActivity.checkpointForPauseMenu]).
 */
@Composable
private fun PauseMenuOverlay(
    onResume: () -> Unit,
    onQuit: () -> Unit,
) {
    var showQuitConfirm by remember { mutableStateOf(false) }
    val resumeFocusRequester = remember { FocusRequester() }

    // Grab initial D-pad focus on the Resume button — with the emulation core's controller
    // routing suppressed while paused (see EmulationActivity.dispatchKeyEvent), Compose's own
    // focus system needs a starting focused node to move D-pad events between the menu buttons.
    LaunchedEffect(Unit) {
        resumeFocusRequester.requestFocus()
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(32.dp).width(420.dp)) {
            Text(text = "Paused", color = Color.White, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onResume,
                modifier = Modifier.fillMaxWidth().focusRequester(resumeFocusRequester),
            ) { Text("Resume") }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { showQuitConfirm = true }, modifier = Modifier.fillMaxWidth()) { Text("Quit") }
        }
    }

    if (showQuitConfirm) {
        AlertDialog(
            onDismissRequest = { showQuitConfirm = false },
            title = { Text("Quit game?") },
            text = { Text("Your save will be checkpointed before quitting.") },
            confirmButton = {
                TextButton(onClick = { showQuitConfirm = false; onQuit() }) { Text("Yes") }
            },
            dismissButton = {
                TextButton(onClick = { showQuitConfirm = false }) { Text("No") }
            },
        )
    }
}

/**
 * Hold-to-exit affordance: a back arrow ringed by a determinate progress indicator that fills as
 * the user holds the remote's Back button, giving clear visual feedback before gameplay exits.
 */
@Composable
private fun BackHintIcon(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier = modifier.size(56.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxSize(),
            color = Color.White,
            trackColor = Color.White.copy(alpha = 0.25f),
            strokeWidth = 3.dp,
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "Hold Back to exit",
            tint = Color.White,
        )
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


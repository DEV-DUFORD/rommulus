package com.romm.androidtv.emulation.process

import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.input.InputManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.romm.androidtv.BuildConfig
import com.romm.androidtv.R
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.controller.LibretroInputAdapter
import com.romm.androidtv.controller.capture.ControllerBindingCaptureCoordinator
import com.romm.androidtv.controller.config.ControllerConfigDatabase
import com.romm.androidtv.controller.config.ControllerConfigRepository
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.RoomControllerConfigRepository
import com.romm.androidtv.controller.config.toRouterMappings
import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.ui.ControllerConfigScreen
import com.romm.androidtv.controller.ui.ControllerSettingsViewModel
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
import com.romm.androidtv.emulation.touch.TouchControllerOverlay
import com.romm.androidtv.emulation.touch.TouchInputCoordinator
import com.romm.androidtv.emulation.video.EmulationSurface
import com.romm.androidtv.emulation.video.VideoOptionsDialog
import com.romm.androidtv.library.ui.tvButtonFocus
import com.romm.androidtv.library.ui.TvButton
import com.romm.androidtv.library.ui.TvOutlinedButton
import com.romm.androidtv.library.ui.RommTvColors
import com.romm.androidtv.library.ui.RommTvTheme
import com.romm.androidtv.library.RommTheme
import com.romm.androidtv.library.ui.applyTheme
import com.romm.androidtv.platform.rememberDeviceProfile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.collect
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
    // This activity runs in its own process (:emulation), so it cannot share
    // MainActivity's ControllerEventRouter instance — each owns its own,
    // exactly mirroring MainActivity's own registration/dispatch pattern
    // (LIBRETRO_REFACTOR.md section 9: "Reuse ControllerEventRouter and
    // GamepadSnapshot; do not route native play through JavaScript"). This
    // class only calls the router's existing public API; none of its
    // internal button/axis/slot logic is modified.
    private val controllerRouter: ControllerEventRouter by lazy { ControllerEventRouter() }

    /**
     * Phase 4 capture coordinator. Intercepts raw input (before gameplay/UI
     * routing) only while a capture is active; returns null when [ControllerBindingCaptureState.Idle]
     * so normal routing is untouched. Wired as an extra InputDeviceListener so
     * physical disconnects cancel an in-progress capture.
     */
    private val captureCoordinator: ControllerBindingCaptureCoordinator by lazy {
        ControllerBindingCaptureCoordinator(lifecycleScope)
    }

    // Reads the persisted per-core controller config overrides (Phase 3). Instantiated in the
    // shared ControllerConfigDatabase (multi-instance invalidation enabled) so this :emulation
    // process observes the same rows the main process writes.
    private val controllerConfigRepository: ControllerConfigRepository by lazy {
        RoomControllerConfigRepository.create(ControllerConfigDatabase.database(applicationContext))
    }

    /** Active core ID (Phase 7): used to resolve the controller profile for the in-pause-menu settings subpage. */
    private var coreIdForMapping: String? = null

    // Translates the router's four-slot snapshots into Libretro RetroPad
    // input and pushes them to the native input_state callback.
    /**
     * Phase 6B touch coordinator. Sits between the physical [onPortUpdated] path
     * (below) and [host.nativeUpdateInputState] so the two input producers (physical
     * controller + on-screen touch overlay) merge instead of overwriting each other.
     * Touch is merged into port 0 only; ports 1-3 pass through unchanged.
     */
    private val touchCoordinator: TouchInputCoordinator by lazy {
        TouchInputCoordinator { buttonMasks, analogValues ->
            host.nativeUpdateInputState(buttonMasks, analogValues)
        }
    }

    private val inputAdapter: LibretroInputAdapter by lazy {
        LibretroInputAdapter(controllerRouter) { ports ->
            touchCoordinator.onPhysicalPorts(ports)
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

    /** Suppresses the duplicate KeyEvent/BackDispatcher delivery some gesture implementations emit. */
    private var lastQuickBackNavigationAtMs: Long = 0L

    /** The current pause-menu overlay state; drives [NativeLibretroHost.nativeSetPaused] whenever not [PauseOverlay.CLOSED]. */
    private val pauseOverlay = MutableStateFlow(PauseOverlay.CLOSED)

    /** Activity-owned settings repository, lazily created from the shared prefs (scanlines persistence). */
    private val settingsRepository by lazy {
        SettingsRepository(
            getSharedPreferences(SettingsRepository.PREFS_NAME, MODE_PRIVATE),
            BuildConfig.ROMM_ORIGIN,
        )
    }

    /** Global on-screen-controls preference, observed live by the in-session touch overlay. */
    private val onScreenControlsEnabled by lazy {
        MutableStateFlow(settingsRepository.onScreenGameControlsEnabled())
    }

    private fun setOnScreenControlsEnabled(enabled: Boolean) {
        settingsRepository.setOnScreenGameControlsEnabled(enabled)
        onScreenControlsEnabled.value = enabled
    }

    private val touchLayoutRepository by lazy {
        com.romm.androidtv.emulation.touch.TouchLayoutRepository(
            getSharedPreferences(SettingsRepository.PREFS_NAME, MODE_PRIVATE),
        )
    }

    /** Scanlines toggle state, initialized from persistence; Compose observes this flow. */
    private val scanlinesEnabled by lazy { MutableStateFlow(settingsRepository.scanlinesEnabled()) }

    /**
     * Mutation boundary: the activity owns persistence, Compose only emits intent. Persists the
     * requested value and, on a successful synchronous commit, updates [scanlinesEnabled] so the
     * UI reflects the committed state. Returns the commit success so callers can surface errors.
     */
    private fun setScanlinesEnabled(requested: Boolean): Boolean {
        val ok = settingsRepository.setScanlinesEnabled(requested)
        if (ok) scanlinesEnabled.value = requested
        return ok
    }

    /** Integer scaling toggle state, initialized from persistence; Compose observes this flow. */
    private val integerScalingEnabled by lazy { MutableStateFlow(settingsRepository.integerScalingEnabled()) }

    /**
     * Mutation boundary for the integer scaling toggle. Mirrors [setScanlinesEnabled] exactly.
     */
    private fun setIntegerScalingEnabled(requested: Boolean): Boolean {
        val ok = settingsRepository.setIntegerScalingEnabled(requested)
        if (ok) integerScalingEnabled.value = requested
        return ok
    }

    /** Sharp-filter toggle state, initialized from persistence; Compose observes this flow. */
    private val sharpFilterEnabled by lazy { MutableStateFlow(settingsRepository.sharpFilterEnabled()) }

    private fun setSharpFilterEnabled(requested: Boolean): Boolean {
        val ok = settingsRepository.setSharpFilterEnabled(requested)
        if (ok) sharpFilterEnabled.value = requested
        return ok
    }

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
        private const val SRAM_CHECKPOINT_INTERVAL_SECONDS = 30L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Gesture navigation is delivered through OnBackPressedDispatcher rather than
        // dispatchKeyEvent on Samsung devices. Route it through the same quick-back path as
        // a remote Back tap, and deduplicate devices that deliver both callbacks.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (sessionStarted) {
                    requestQuickBackNavigation()
                } else {
                    finish()
                }
            }
        })
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

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
            host.nativeConfigureAutosave(savePath, SRAM_CHECKPOINT_INTERVAL_SECONDS)

            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
            inputManager.registerInputDeviceListener(controllerRouter, null)
            // Phase 4: also listen for disconnects so an in-progress capture
            // cancels when the assigned controller is physically removed.
            inputManager.registerInputDeviceListener(captureCoordinator, null)
            controllerRouter.attachLifecycle(this)
            controllerRouter.enumerateExistingDevices(inputManager)
            lifecycle.addObserver(LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_START) {
                    controllerRouter.enumerateExistingDevices(inputManager)
                }
            })

            // Phase 3: apply the core's saved controller config before gameplay input flows to
            // native. loadCore is suspend, so this runs in lifecycleScope; inputAdapter.start is
            // deferred into the same coroutine so mappings are installed first. On any failure we
            // log and let the router keep its default mapping — never crash or block startup.
            coreIdForMapping = coreId
            lifecycleScope.launch {
                try {
                    val config = controllerConfigRepository.loadCore(coreIdForMapping ?: "")
                    val profile = CoreControllerProfiles.byCoreId(coreIdForMapping ?: "")
                    if (profile != null) {
                        controllerRouter.applyMappings(config.toRouterMappings(profile))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onCreate: failed to apply controller mappings, keeping defaults", e)
                }
                inputAdapter.start(lifecycleScope)
            }

            // Phase 3: live updates. observeCore emits the current merged config immediately, then
            // re-emits on any change, so in-game config edits take effect right away. Empty configs
            // produce an empty map and are a no-op on the router.
            lifecycleScope.launch {
                try {
                    controllerConfigRepository.observeCore(coreIdForMapping ?: "")
                        .collect { config ->
                            val profile = CoreControllerProfiles.byCoreId(coreIdForMapping ?: "")
                            if (profile != null) {
                                controllerRouter.applyMappings(config.toRouterMappings(profile))
                            }
                        }
                } catch (e: Exception) {
                    Log.w(TAG, "onCreate: controller config observation stopped", e)
                }
            }

            // A per-core controller profile can reserve any two physical inputs as
            // the pause shortcut. The router emits only on the unpressed -> pressed
            // edge, so holding the pair cannot repeatedly open/close the overlay.
            lifecycleScope.launch {
                controllerRouter.pauseMenuRequests.collect {
                    if (sessionStarted &&
                        pauseOverlay.value == PauseOverlay.CLOSED &&
                        !saveFailureVisible.value
                    ) {
                        controllerRouter.releaseAllInputs()
                        inputAdapter.pushCurrentState()
                        pauseOverlay.value = PauseOverlay.MENU
                    }
                }
            }
        }

        this.savePath = savePath

        // The hardware capability is authoritative. The persisted global preference is collected
        // in Compose so toggling it from the pause menu takes effect immediately.
        val hasTouchscreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)

        // EmulationActivity runs in :emulation, where the process-local theme state starts at
        // its default. Restore the shared preference before composing the pause menu.
        applyTheme(RommTheme.fromStorage(settingsRepository.theme()))

        setContent {
            RommTvTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    EmulationScreen(
                            host = host,
                            sessionStarted = sessionStarted,
                            lastError = host.nativeGetLastError(),
                            failureCategory = if (!sessionStarted) classifyLaunchFailure(host.nativeGetLastError()) else LaunchFailureCategory.NONE,
                            keyActivityEvents = keyActivityEvents,
                            backKeyHeld = backKeyHeld,
                            quickBackTapEvents = quickBackTapEvents,
                            pauseOverlay = pauseOverlay,
                            coreIdForMapping = coreIdForMapping,
                            controllerConfigRepository = controllerConfigRepository,
                            captureCoordinator = captureCoordinator,
                            controllerRouter = controllerRouter,
                            saveFailureVisible = saveFailureVisible,
                            scanlinesEnabled = scanlinesEnabled,
                            integerScalingEnabled = integerScalingEnabled,
                            sharpFilterEnabled = sharpFilterEnabled,
                            hasTouchscreen = hasTouchscreen,
                            onScreenControlsEnabled = onScreenControlsEnabled,
                            touchCoordinator = touchCoordinator,
                            touchLayoutOverride = coreIdForMapping?.let(touchLayoutRepository::load),
                            touchLayoutRepository = touchLayoutRepository,
                            onSetScanlinesEnabled = ::setScanlinesEnabled,
                            onSetIntegerScalingEnabled = ::setIntegerScalingEnabled,
                            onSetSharpFilterEnabled = ::setSharpFilterEnabled,
                            onSetOnScreenControlsEnabled = ::setOnScreenControlsEnabled,
                            onStop = { finishAndDeliverResult() },
                            onQuitAnywayAfterSaveFailure = { finishAndDeliverResult(forceQuitOnSaveFailure = true) },
                            onSetNativePaused = { paused -> host.nativeSetPaused(paused) },
                            onOpenPauseMenuCheckpoint = { checkpointForPauseMenu() },
                            // Phase 4: opening the pause menu must cancel any in-progress capture
                            // ("Entering controller settings from a paused game must never resume
                            // gameplay" — capture must not survive the pause menu).
                            onCaptureCancel = { captureCoordinator.cancel() },
                    )

                    // Phase 7: hide system bars during active gameplay (sessionStarted,
                    // pauseOverlay == CLOSED, no save-failure UI) so the emulation is
                    // fully immersive on phone/tablet. Restored to normal (bars shown)
                    // whenever gameplay is no longer "active" (overlay open, session ends,
                    // or save-failure dialog visible). Phase 6B: the same gameplay-active
                    // policy gates touch input routing — paused/configuration overlays must
                    // never leak touch into the core, so routing is disabled (and any held
                    // touch state released) the moment gameplay is no longer active.
                    val overlayState by pauseOverlay.collectAsState()
                    val saveFailureShown by saveFailureVisible.collectAsState(initial = false)
                    LaunchedEffect(sessionStarted, overlayState, saveFailureShown) {
                        val routingActive = shouldRouteGameplayInput(
                            sessionStarted = sessionStarted,
                            pauseOverlay = overlayState,
                            saveFailureVisible = saveFailureShown,
                        )
                        applyImmersiveMode(
                            routingActive || overlayState == PauseOverlay.TOUCH_CONTROLLER_SETTINGS,
                        )
                        touchCoordinator.setRoutingEnabled(routingActive)
                        if (!routingActive) touchCoordinator.resetTouch()
                    }
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

    private fun checkpointIfRunning(): CheckpointOutcome {
        if (!sessionStarted) return CheckpointOutcome.FAILED
        val path = savePath ?: return CheckpointOutcome.FAILED
        val saveMemorySizeBytes = host.nativeGetSramSizeBytes()
        if (saveMemorySizeBytes <= 0L) {
            checkpointedHash = null
            Log.d(TAG, "checkpoint: skipped because the game exposes no save memory")
            return CheckpointOutcome.NO_SAVE_MEMORY
        }
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

        return classifyCheckpointOutcome(saveMemorySizeBytes, checkpointed)
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

    private fun requestQuickBackNavigation() {
        if (!sessionStarted || saveFailureVisible.value) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastQuickBackNavigationAtMs < BACK_NAVIGATION_DEDUPLICATION_MS) return
        lastQuickBackNavigationAtMs = now
        quickBackTapEvents.tryEmit(Unit)
    }

    override fun onPause() {
        if (sessionStarted && !isFinishing) {
            // Home, screen-off, and task switches must freeze the core immediately. Keeping the
            // overlay open also ensures that restoring the retained TV task returns to the pause
            // menu rather than silently resuming gameplay.
            host.nativeSetPaused(true)
            pauseOverlay.value = pauseOverlayOnBackground(pauseOverlay.value)
        }
        // Checkpoint on pause, not just on destroy: LIBRETRO_REFACTOR.md section
        // 11.1 requires checkpointing "on pause or quit", so a task switch or
        // screen-off doesn't lose progress if the process is later killed outright
        // (onDestroy is not guaranteed to run in that case).
        checkpointIfRunning()
        // Phase 4: capture must not survive activity stop (spec rule 8).
        captureCoordinator.cancel()
        // Phase 6B: release any held touch buttons when the activity loses foreground, so a
        // stuck touch input can't leak into the core on resume.
        touchCoordinator.resetTouch()
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
        val checkpointOutcome = checkpointIfRunning()
        if (checkpointOutcome == CheckpointOutcome.FAILED &&
            sessionStarted &&
            !forceQuitOnSaveFailure
        ) {
            // Do not silently discard a failed checkpoint at quit time — block with a native
            // "save failed" screen so the user can retry or make an informed choice to quit
            // anyway (LIBRETRO_REFACTOR.md section 13, Phase 6 error screens).
            Log.w(TAG, "finishAndDeliverResult: checkpoint failed at quit, blocking for user decision")
            saveFailureVisible.value = true
            return
        }
        saveFailureVisible.value = false
        deliverResult(checkpointOutcome == CheckpointOutcome.SAVED)
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
                    // Candidate was adopted during onCreate, but the SRAM has almost certainly
                    // changed since then (that's the whole point of playing). Use the just-taken
                    // checkpoint hash from checkpointIfRunning() (this activity's live
                    // `checkpointedHash` field) — NOT `descriptor.checkpointedHash`, which is the
                    // stale, pre-gameplay adoption-time hash. Reporting the stale hash here made
                    // EmulationResultHandler's post-play sync compare the fresh checkpoint against
                    // itself (finalizeAdoption() writes localHash = this same stale value, then
                    // syncPostPlay() sees it "match" and treats real gameplay progress as
                    // unchanged) — silently skipping the upload for exactly the "cloud save
                    // exists, no local replica yet" first-adoption scenario. Only fall back to the
                    // stale descriptor hash if this session never got as far as producing its own
                    // checkpoint (e.g. checkpoint failed and the caller forced a quit anyway).
                    setResult(android.app.Activity.RESULT_OK, buildResultIntent(
                        EmulationResult.Completed(
                            sessionId = sid,
                            checkpointedSavePath = savePath,
                            checkpointedSaveHash = (checkpointedHash.takeIf { checkpointed }) ?: descriptor.checkpointedHash,
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
            Log.w(TAG, "deliverResult: failed to write result intent", e)
        }
        Log.i(TAG, "deliverResult: finished")
    }

    override fun onDestroy() {
        checkpointIfRunning()
        inputAdapter.stop()
        // Phase 4: capture must not survive activity destroy (spec rule 8).
        captureCoordinator.cancel()
        try {
            val inputManager = getSystemService(INPUT_SERVICE) as InputManager
            inputManager.unregisterInputDeviceListener(controllerRouter)
            inputManager.unregisterInputDeviceListener(captureCoordinator)
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
            if (stageRomId > 0L) putExtra("rom_id", stageRomId)
            if (stageRomHash.isNotBlank()) putExtra("rom_hash", stageRomHash)
        }
    }

    /**
     * Phase 7: applies or restores edge-to-edge immersive mode using
     * [WindowInsetsControllerCompat]. Called from the Compose [LaunchedEffect]
     * whenever the gameplay-active state flips, and from [onWindowFocusChanged]
     * to re-apply after IME/diaglog/task-switch reveals the bars.
     */
    private fun applyImmersiveMode(active: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (active) {
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    override fun onStop() {
        // When gameplay leaves the foreground (returned to launcher, or finishing
        // the activity), restore normal system-bar visibility so the user sees
        // status/navigation chrome again on the next screen. The Compose
        // LaunchedEffect above also handles the case where the pause overlay
        // opens or the session ends, but onStop covers activity-level lifecycle
        // transitions (home, task switch).
        applyImmersiveMode(active = false)
        // Phase 6B: release any held touch state on activity stop.
        touchCoordinator.resetTouch()
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && sessionStarted) {
            // Re-apply immersive after IME, system dialog, or task switch that may
            // have revealed the status/navigation bars. The 2-state check mirrors
            // the Compose-side hook, so the bars stay hidden as long as gameplay
            // is the active foreground experience.
            applyImmersiveMode(
                shouldRouteGameplayInput(
                    sessionStarted = sessionStarted,
                    pauseOverlay = pauseOverlay.value,
                    saveFailureVisible = saveFailureVisible.value,
                ),
            )
        } else {
            // Phase 6B: releasing focus (IME, system dialog, task switch) must release any held
            // touch state so it never lingers in the core.
            touchCoordinator.resetTouch()
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
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            controllerRouter.recordPhysicalInputActivity(event.deviceId)
        }
        // Phase 4: capture raw input before all normal routing. Returns non-null
        // (consume) only while a capture is active; null when Idle lets the
        // existing gameplay/UI routing below run completely unchanged.
        captureCoordinator.onKeyEvent(event)?.let { return it }

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
                        if (heldMs < BACK_HOLD_DURATION_MS && sessionStarted &&
                            !saveFailureVisible.value
                        ) {
                            requestQuickBackNavigation()
                        }
                    }
                }
                return true
            }
        }
        // Non-remote Back while a pause overlay is open: the gamepad B (delivered as
        // KEYCODE_BACK on this device) must dismiss the current overlay and return to gameplay —
        // never fall through to the platform default Back, which would finish() the whole game.
        // The remote's Back is fully handled (quick-tap navigation + hold-to-exit) in the
        // isRemoteSource block above, so it never reaches here. The gamepad has no hold-to-exit;
        // it simply emits a quick click that advances the overlay (CLOSED never used from here).
        if (event.keyCode == KeyEvent.KEYCODE_BACK &&
            pauseOverlay.value != PauseOverlay.CLOSED
        ) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                requestQuickBackNavigation()
            }
            return true
        }
        if (shouldRouteGameplayInput(
                sessionStarted = sessionStarted,
                pauseOverlay = pauseOverlay.value,
                saveFailureVisible = saveFailureVisible.value,
            )
        ) {
            val consumed = controllerRouter.onKeyEvent(event)
            if (consumed) {
                inputAdapter.pushCurrentState()
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Phase 4: capture raw input before all normal routing (see dispatchKeyEvent).
        captureCoordinator.onMotionEvent(event)?.let { return it }

        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == 0
        ) {
            return super.dispatchGenericMotionEvent(event)
        }
        if (shouldRouteGameplayInput(
                sessionStarted = sessionStarted,
                pauseOverlay = pauseOverlay.value,
                saveFailureVisible = saveFailureVisible.value,
            )
        ) {
            val consumed = controllerRouter.onMotionEvent(event)
            if (consumed) {
                inputAdapter.pushCurrentState()
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }
}

internal fun shouldRouteGameplayInput(
    sessionStarted: Boolean,
    pauseOverlay: PauseOverlay,
    saveFailureVisible: Boolean,
): Boolean = sessionStarted && pauseOverlay == PauseOverlay.CLOSED && !saveFailureVisible

internal fun pauseOverlayOnBackground(current: PauseOverlay): PauseOverlay = when (current) {
    PauseOverlay.CLOSED -> PauseOverlay.MENU
    PauseOverlay.MENU,
    PauseOverlay.CONTROLLER_MENU,
    PauseOverlay.VIDEO_OPTIONS,
    PauseOverlay.CONTROLLER_SETTINGS,
    PauseOverlay.TOUCH_CONTROLLER_SETTINGS -> current
}

internal enum class CheckpointOutcome {
    SAVED,
    NO_SAVE_MEMORY,
    FAILED,
}

internal fun classifyCheckpointOutcome(
    saveMemorySizeBytes: Long,
    checkpointSucceeded: Boolean,
): CheckpointOutcome = when {
    saveMemorySizeBytes <= 0L -> CheckpointOutcome.NO_SAVE_MEMORY
    checkpointSucceeded -> CheckpointOutcome.SAVED
    else -> CheckpointOutcome.FAILED
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun EmulationScreen(
    host: NativeLibretroHost,
    sessionStarted: Boolean,
    lastError: String,
    failureCategory: LaunchFailureCategory,
    keyActivityEvents: Flow<Unit>,
    backKeyHeld: Flow<Boolean>,
    quickBackTapEvents: Flow<Unit>,
    pauseOverlay: MutableStateFlow<PauseOverlay>,
    coreIdForMapping: String?,
    controllerConfigRepository: ControllerConfigRepository,
    captureCoordinator: ControllerBindingCaptureCoordinator,
    controllerRouter: ControllerEventRouter,
    saveFailureVisible: Flow<Boolean>,
    scanlinesEnabled: StateFlow<Boolean>,
    integerScalingEnabled: StateFlow<Boolean>,
    sharpFilterEnabled: StateFlow<Boolean>,
    hasTouchscreen: Boolean,
    onScreenControlsEnabled: StateFlow<Boolean>,
    touchCoordinator: TouchInputCoordinator,
    touchLayoutOverride: com.romm.androidtv.emulation.touch.TouchLayoutOverrideDocument?,
    touchLayoutRepository: com.romm.androidtv.emulation.touch.TouchLayoutRepository,
    onSetScanlinesEnabled: (Boolean) -> Boolean,
    onSetIntegerScalingEnabled: (Boolean) -> Boolean,
    onSetSharpFilterEnabled: (Boolean) -> Boolean,
    onSetOnScreenControlsEnabled: (Boolean) -> Unit,
    onStop: () -> Unit,
    onQuitAnywayAfterSaveFailure: () -> Unit,
    onSetNativePaused: (Boolean) -> Unit,
    onOpenPauseMenuCheckpoint: () -> Unit,
    onCaptureCancel: () -> Unit,
) {
    // Diagnostics are polled silently to drive the core-requested-shutdown auto-stop below; none
    // of it is rendered on screen — gameplay should show only the game itself.
    var diagnostics by remember { mutableStateOf(LongArray(21).also { it[4] = -1 }) }
    var showBackHint by remember { mutableStateOf(false) }
    // Owned entirely inside this composition: a LaunchedEffect's coroutine has access to
    // Compose's MonotonicFrameClock, which Animatable.animateTo requires. Driving this same
    // Animatable from the Activity's lifecycleScope instead throws
    // "IllegalStateException: A MonotonicFrameClock is not available" and crashes the whole
    // :emulation process — that was the earlier "back exits instantly, no animation" bug.
    val backHoldProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val overlayState by pauseOverlay.collectAsState()
    val saveFailureShown by saveFailureVisible.collectAsState(initial = false)
    val scanlinesOn by scanlinesEnabled.collectAsState()
    val integerScalingOn by integerScalingEnabled.collectAsState()
    val sharpFilterOn by sharpFilterEnabled.collectAsState()
    val onScreenControlsOn by onScreenControlsEnabled.collectAsState()
    val touchControlsEnabled = hasTouchscreen && onScreenControlsOn
    var persistenceError by remember { mutableStateOf(false) }
    var pauseMenuFocusTarget by remember { mutableStateOf(PauseMenuFocusTarget.RESUME) }
    var activeTouchLayoutOverride by remember(coreIdForMapping) {
        mutableStateOf(touchLayoutOverride)
    }

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
                // If the save-failure overlay is already showing, the user has already been
                // warned once (checkpoint failed) and is holding Back again to leave anyway.
                // Routing this back through plain onStop() would just retry the same failing
                // checkpoint and re-show the identical blocking overlay — a dead-end loop with
                // no way out via the remote. Honor a second hold-to-exit as an explicit
                // "quit anyway" instead, matching the overlay's own button.
                if (saveFailureShown) {
                    onQuitAnywayAfterSaveFailure()
                } else {
                    onStop()
                }
            } else {
                backHoldProgress.animateTo(0f, tween(durationMillis = 150))
            }
        }
    }

    // A quick Back tap (released before the hold-to-exit threshold) navigates the overlay: CLOSED
    // opens the pause menu; MENU closes it; VIDEO_OPTIONS/CONTROLLER_SETTINGS return to MENU (never
    // resumes gameplay — the core stays paused while on a subpage, see the LaunchedEffect below).
    // A fresh CLOSED -> MENU transition resets focus to RESUME.
    LaunchedEffect(Unit) {
        quickBackTapEvents.collectLatest {
            val current = pauseOverlay.value
            val next = quickBackTransition(current)
            pauseOverlay.value = next
            if (current == PauseOverlay.CLOSED && next == PauseOverlay.MENU) {
                pauseMenuFocusTarget = PauseMenuFocusTarget.RESUME
            }
        }
    }

    // Freeze the native session whenever the overlay is not CLOSED (MENU or CONTROLLER_SETTINGS) and
    // unfreeze on CLOSED. The silent local-only checkpoint runs ONLY on the CLOSED -> MENU transition
    // (not on MENU <-> CONTROLLER_SETTINGS subpage transitions), so it never double-fires. Any
    // in-progress capture is cancelled on overlay entry.
    var previousOverlay by remember { mutableStateOf(PauseOverlay.CLOSED) }
    LaunchedEffect(overlayState) {
        val prev = previousOverlay
        previousOverlay = overlayState
        onSetNativePaused(overlayState != PauseOverlay.CLOSED)
        if (prev == PauseOverlay.CLOSED && overlayState != PauseOverlay.CLOSED) {
            if (overlayState == PauseOverlay.MENU) {
                onOpenPauseMenuCheckpoint()
            }
            onCaptureCancel()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (sessionStarted) {
            // The video surface is the only visible content during normal play.
            EmulationSurface(
                host = host,
                coreWidth = diagnostics[2].toInt(),
                coreHeight = diagnostics[3].toInt(),
                displayAspectRatio = diagnostics.getOrElse(21) { 0L } / 1_000_000f,
                scanlinesEnabled = scanlinesOn,
                integerScalingEnabled = integerScalingOn,
                sharpFilterEnabled = sharpFilterOn,
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

        // Phase 6B: on-screen touch controller for touchscreen devices, rendered above the
        // video surface and below the pause/error dialogs. Shown only when touch controls are
        // enabled (touchscreen present + persisted setting) AND gameplay is active — pausing or
        // opening a configuration overlay hides the overlay so touch input never leaks into the
        // core (the coordinator's routing gate is toggled in the activity's LaunchedEffect).
        if (touchControlsEnabled &&
            shouldRouteGameplayInput(sessionStarted, overlayState, saveFailureShown)
        ) {
            CoreControllerProfiles.byCoreId(coreIdForMapping ?: "")?.let { profile ->
                TouchControllerOverlay(
                    profile = profile,
                    onButtonChange = touchCoordinator::onTouchButton,
                    onAxisChange = touchCoordinator::onTouchAxis,
                    onPause = { pauseOverlay.value = PauseOverlay.MENU },
                    layoutOverride = activeTouchLayoutOverride,
                )
            }
        }

        if (sessionStarted) {
            if (overlayState == PauseOverlay.MENU || overlayState == PauseOverlay.VIDEO_OPTIONS) {
                PauseMenuOverlay(
                    enabled = overlayState == PauseOverlay.MENU,
                    focusTarget = pauseMenuFocusTarget,
                    onResume = { pauseOverlay.value = PauseOverlay.CLOSED },
                    onOpenVideoOptions = {
                        pauseMenuFocusTarget = PauseMenuFocusTarget.VIDEO_OPTIONS
                        pauseOverlay.value = PauseOverlay.VIDEO_OPTIONS
                    },
                    onOpenControllerSettings = {
                        pauseMenuFocusTarget = PauseMenuFocusTarget.CONTROLLER_SETTINGS
                        pauseOverlay.value = controllerSettingsTransition(hasTouchscreen)
                    },
                    onQuit = onStop,
                )
            }

            if (overlayState == PauseOverlay.VIDEO_OPTIONS) {
                VideoOptionsDialog(
                    scanlinesEnabled = scanlinesOn,
                    integerScalingEnabled = integerScalingOn,
                    sharpFilterEnabled = sharpFilterOn,
                    persistenceError = persistenceError,
                    onScanlinesChanged = { requested ->
                        val ok = onSetScanlinesEnabled(requested)
                        persistenceError = !ok
                        ok
                    },
                    onIntegerScalingChanged = { requested ->
                        val ok = onSetIntegerScalingEnabled(requested)
                        persistenceError = !ok
                        ok
                    },
                    onSharpFilterChanged = { requested ->
                        val ok = onSetSharpFilterEnabled(requested)
                        persistenceError = !ok
                        ok
                    },
                    onDismiss = {
                        persistenceError = false
                        pauseOverlay.value = PauseOverlay.MENU
                    },
                )
            }

            if (overlayState == PauseOverlay.CONTROLLER_SETTINGS) {
                ControllerSettingsSubpage(
                    coreId = coreIdForMapping,
                    repository = controllerConfigRepository,
                    captureCoordinator = captureCoordinator,
                    controllerRouter = controllerRouter,
                    onBack = { pauseOverlay.value = PauseOverlay.MENU },
                )
            }

            if (overlayState == PauseOverlay.CONTROLLER_MENU) {
                ControllerSettingsMenu(
                    onScreenControlsEnabled = onScreenControlsOn,
                    onSetOnScreenControlsEnabled = onSetOnScreenControlsEnabled,
                    onOpenPhysicalControllerSettings = {
                        pauseOverlay.value = PauseOverlay.CONTROLLER_SETTINGS
                    },
                    onOpenOnScreenControllerSettings = {
                        pauseOverlay.value = PauseOverlay.TOUCH_CONTROLLER_SETTINGS
                    },
                    onBack = { pauseOverlay.value = PauseOverlay.MENU },
                )
            }

            if (overlayState == PauseOverlay.TOUCH_CONTROLLER_SETTINGS) {
                CoreControllerProfiles.byCoreId(coreIdForMapping ?: "")?.let { profile ->
                    com.romm.androidtv.emulation.touch.TouchLayoutEditorScreen(
                        profile = profile,
                        repository = touchLayoutRepository,
                        onBack = { pauseOverlay.value = PauseOverlay.MENU },
                        onLayoutChanged = { activeTouchLayoutOverride = it },
                    )
                }
            }
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

/** Ignore duplicate back deliveries from one navigation gesture, without swallowing normal taps. */
private const val BACK_NAVIGATION_DEDUPLICATION_MS = 250L

/** Coarse category used to select which native error screen to show. */
enum class LaunchFailureCategory { NONE, CORE_LOAD, CONTENT_LOAD, UNKNOWN }

/** Explicit state machine for the in-session pause overlay (CONTROLLER_SETTINGS.md Phase 7). */
enum class PauseOverlay {
    /** No overlay visible; native gameplay input is routed normally. */
    CLOSED,
    /** The pause menu (Resume / Video Options / Controller Settings / Quit) is visible. */
    MENU,
    /** Touchscreen-only controller-settings menu; the core stays paused. */
    CONTROLLER_MENU,
    /** The shared video-options dialog is visible; the core stays paused. */
    VIDEO_OPTIONS,
    /** The shared controller-settings subpage is visible; the core stays paused. */
    CONTROLLER_SETTINGS,
    /** The active core's on-screen controller layout editor is visible; the core stays paused. */
    TOUCH_CONTROLLER_SETTINGS,
}

/**
 * Which pause-menu item should receive D-pad focus on entry. The modal/subpage composables take
 * focus while open; on Back to [PauseOverlay.MENU] the menu maps this target to a [FocusRequester]
 * so focus is restored to the item the user launched from.
 */
enum class PauseMenuFocusTarget {
    RESUME,
    VIDEO_OPTIONS,
    CONTROLLER_SETTINGS,
    QUIT,
}

/** Opens the touchscreen controller-settings submenu, or physical mappings directly on TV. */
internal fun controllerSettingsTransition(hasTouchscreen: Boolean): PauseOverlay =
    if (hasTouchscreen) PauseOverlay.CONTROLLER_MENU else PauseOverlay.CONTROLLER_SETTINGS

/** Pure transition for a quick Back tap: subpages return to MENU, never CLOSED. */
internal fun quickBackTransition(current: PauseOverlay): PauseOverlay = when (current) {
    PauseOverlay.CLOSED -> PauseOverlay.MENU
    PauseOverlay.MENU -> PauseOverlay.CLOSED
    PauseOverlay.CONTROLLER_MENU -> PauseOverlay.MENU
    PauseOverlay.VIDEO_OPTIONS -> PauseOverlay.MENU
    PauseOverlay.CONTROLLER_SETTINGS -> PauseOverlay.MENU
    PauseOverlay.TOUCH_CONTROLLER_SETTINGS -> PauseOverlay.MENU
}

/**
 * Classifies [NativeLibretroHost.nativeGetLastError]'s message into a [LaunchFailureCategory] so
 * distinct native error screens (LIBRETRO_REFACTOR.md section 13, Phase 6) can be shown for a
 * core-load failure (bad/missing core binary, ABI mismatch) versus a content-load failure (core
 * loaded fine but rejected this specific ROM file). Coupled to the exact prefixes emulation_session.cpp
 * and core_library.cpp assign to `lastError_` — if those native strings change, update this too.
 */
internal fun classifyLaunchFailure(lastError: String): LaunchFailureCategory = when {
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
    val backFocusRequester = remember { FocusRequester() }
    val (title, message) = when (category) {
        LaunchFailureCategory.CORE_LOAD -> "Emulator core failed to load" to
            "The native emulator core for this system could not be started. This usually means the app build is missing or has a broken core binary."
        LaunchFailureCategory.CONTENT_LOAD -> "This game could not be loaded" to
            "The emulator core started, but rejected this specific game file. It may be corrupt or in an unsupported format for this core."
        LaunchFailureCategory.UNKNOWN, LaunchFailureCategory.NONE -> "Something went wrong starting this game" to
            "An unexpected error prevented this session from starting."
    }
    LaunchedEffect(Unit) {
        backFocusRequester.requestFocus()
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
        TvButton(
            onClick = onBackToLibrary,
            modifier = Modifier.focusRequester(backFocusRequester),
        ) { Text("Back to Library") }
    }
}

/**
 * Blocking screen shown when a checkpoint fails at quit time, so a failed save is never silently
 * discarded (LIBRETRO_REFACTOR.md section 13, Phase 6 error screens).
 */
@Composable
private fun SaveFailureOverlay(onRetry: () -> Unit, onQuitAnyway: () -> Unit) {
    val retryFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        retryFocusRequester.requestFocus()
    }
    Box(modifier = Modifier.fillMaxSize().background(RommTvColors.NightHi.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
        Column(modifier = Modifier.padding(32.dp)) {
            Text(text = "Save failed", color = RommTvColors.TextPrimary, style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "The game's save data could not be checkpointed. Quitting now may lose recent progress. " +
                    "You can retry, or quit anyway and keep the last successful save.",
                color = RommTvColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Row {
                TvButton(
                    onClick = onRetry,
                    modifier = Modifier.focusRequester(retryFocusRequester),
                ) { Text("Retry") }
                Spacer(modifier = Modifier.width(16.dp))
                TvOutlinedButton(onClick = onQuitAnyway) {
                    Text("Quit anyway")
                }
            }
        }
    }
}

/**
 * Native pause-menu overlay (LIBRETRO_REFACTOR.md section 13, Phase 6): Resume, Video Options,
 * Controller Settings, and Quit (with confirm) — the sole in-session menu, opened by a quick
 * Back tap. Emulation is frozen (native `paused_` flag) for the whole time this is visible; see
 * [EmulationScreen]'s overlay LaunchedEffect, which also takes a silent local-only save
 * checkpoint on the CLOSED -> MENU transition (not surfaced here — see
 * [EmulationActivity.checkpointForPauseMenu]).
 *
 * When [enabled] is false (a modal like Video Options is open on top), the menu is removed from
 * focus traversal and its activation is blocked via [Modifier.enabled]; visual dimming alone is
 * insufficient because the menu must never intercept D-pad/Back input while the modal is active.
 * Focus is restored to the item recorded in [focusTarget] whenever [enabled] flips true, so
 * returning from a modal/subpage returns to the item the user launched from.
 */
@Composable
private fun PauseMenuOverlay(
    enabled: Boolean,
    focusTarget: PauseMenuFocusTarget,
    onResume: () -> Unit,
    onOpenVideoOptions: () -> Unit,
    onOpenControllerSettings: () -> Unit,
    onQuit: () -> Unit,
) {
    var showQuitConfirm by remember { mutableStateOf(false) }
    val deviceProfile = rememberDeviceProfile()
    val pauseMenuScrollState = rememberScrollState()
    val shouldScroll = deviceProfile.hasTouchscreen && deviceProfile.isCompactHeight
    val resumeFocusRequester = remember { FocusRequester() }
    val videoOptionsFocusRequester = remember { FocusRequester() }
    val controllerSettingsFocusRequester = remember { FocusRequester() }
    val quitFocusRequester = remember { FocusRequester() }

    // Explicit focus-target mapping: when the menu is enabled, request focus on the item the
    // parent recorded (RESUME on a fresh CLOSED -> MENU, VIDEO_OPTIONS/CONTROLLER_SETTINGS on
    // return from their modal/subpage). Re-running on [focusTarget]/[enabled] change restores
    // focus to the launched-from item after Back.
    LaunchedEffect(focusTarget, enabled) {
        if (!enabled) return@LaunchedEffect
        when (focusTarget) {
            PauseMenuFocusTarget.RESUME -> resumeFocusRequester.requestFocus()
            PauseMenuFocusTarget.VIDEO_OPTIONS -> videoOptionsFocusRequester.requestFocus()
            PauseMenuFocusTarget.CONTROLLER_SETTINGS -> controllerSettingsFocusRequester.requestFocus()
            PauseMenuFocusTarget.QUIT -> quitFocusRequester.requestFocus()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .padding(32.dp)
                .width(420.dp)
                .then(
                    if (shouldScroll) {
                        Modifier.verticalScroll(pauseMenuScrollState)
                    } else {
                        Modifier
                    },
                ),
        ) {
            Text(
                text = stringResource(R.string.pause_menu_paused),
                color = RommTvColors.TextPrimary,
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(modifier = Modifier.height(20.dp))

            // Pass `enabled` through to each button: a disabled material Button is removed from
            // focus traversal AND blocks activation (the equivalent of Modifier.enabled(enabled),
            // which this Compose version does not expose). When a modal like Video Options is
            // open on top, the menu stays dimmed but cannot receive focus or clicks.
            TvButton(
                onClick = onResume,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().focusRequester(resumeFocusRequester),
            ) { Text(stringResource(R.string.pause_menu_resume)) }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = onOpenVideoOptions,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().focusRequester(videoOptionsFocusRequester),
            ) { Text(stringResource(R.string.pause_menu_video_options)) }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = onOpenControllerSettings,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().focusRequester(controllerSettingsFocusRequester),
            ) { Text(stringResource(R.string.pause_menu_controller_settings)) }
            Spacer(modifier = Modifier.height(8.dp))
            TvOutlinedButton(
                onClick = { showQuitConfirm = true },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth().focusRequester(quitFocusRequester),
            ) { Text(stringResource(R.string.pause_menu_quit)) }
        }
    }

    if (showQuitConfirm) {
        AlertDialog(
            onDismissRequest = { showQuitConfirm = false },
            title = { Text("Quit game?") },
            text = { Text("Are you sure you want to quit?") },
            confirmButton = {
                TextButton(
                    onClick = { showQuitConfirm = false; onQuit() },
                    modifier = Modifier.tvButtonFocus(),
                ) { Text("Yes") }
            },
            dismissButton = {
                TextButton(
                    onClick = { showQuitConfirm = false },
                    modifier = Modifier.tvButtonFocus(),
                ) { Text("No") }
            },
        )
    }
}

/**
 * Touchscreen-only pause submenu. Keeping its global visibility toggle beside the touch-layout
 * editor lets a player re-enable controls without leaving an active game.
 */
@Composable
private fun ControllerSettingsMenu(
    onScreenControlsEnabled: Boolean,
    onSetOnScreenControlsEnabled: (Boolean) -> Unit,
    onOpenPhysicalControllerSettings: () -> Unit,
    onOpenOnScreenControllerSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val physicalSettingsFocusRequester = remember { FocusRequester() }
    val onScreenControlsInteractionSource = remember { MutableInteractionSource() }
    val onScreenControlsFocused by onScreenControlsInteractionSource.collectIsFocusedAsState()
    val scrollState = rememberScrollState()
    val maxHeight = LocalConfiguration.current.screenHeightDp.dp * 0.7f
    LaunchedEffect(Unit) {
        physicalSettingsFocusRequester.requestFocus()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(540.dp)
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(16.dp))
                .background(RommTvColors.NightLo)
                .border(
                    BorderStroke(1.dp, RommTvColors.TextSecondary.copy(alpha = 0.25f)),
                    RoundedCornerShape(16.dp),
                )
                .padding(28.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
            ) {
                Text(
                    text = stringResource(R.string.pause_menu_controller_settings),
                    color = RommTvColors.TextPrimary,
                    style = MaterialTheme.typography.headlineSmall,
                )
                Spacer(modifier = Modifier.height(20.dp))
                TvOutlinedButton(
                    onClick = onOpenPhysicalControllerSettings,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(physicalSettingsFocusRequester),
                ) {
                    Text(stringResource(R.string.pause_menu_physical_controller_settings))
                }
                Spacer(modifier = Modifier.height(8.dp))
                TvOutlinedButton(
                    onClick = onOpenOnScreenControllerSettings,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.pause_menu_on_screen_controller_settings))
                }
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = onScreenControlsEnabled,
                            interactionSource = onScreenControlsInteractionSource,
                            indication = null,
                            role = Role.Switch,
                            onValueChange = onSetOnScreenControlsEnabled,
                        )
                        .border(
                            BorderStroke(
                                3.dp,
                                if (onScreenControlsFocused) RommTvColors.Romm300 else Color.Transparent,
                            ),
                            RoundedCornerShape(8.dp),
                        )
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = stringResource(R.string.pause_menu_on_screen_controls),
                        color = RommTvColors.TextPrimary,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Switch(
                        checked = onScreenControlsEnabled,
                        onCheckedChange = null,
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                TvOutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.video_options_return))
                }
            }
        }
    }
}

/**
 * The shared controller-settings subpage shown while [PauseOverlay.CONTROLLER_SETTINGS].
 * Reuses the same [ControllerSettingsViewModel.Factory] pattern as MainActivity's Phase 6 work;
 * the running core stays paused (see [EmulationScreen]'s overlay LaunchedEffect). If the active
 * core has no controller profile, logs a warning and falls back to [PauseOverlay.MENU].
 */
@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
private fun ControllerSettingsSubpage(
    coreId: String?,
    repository: ControllerConfigRepository,
    captureCoordinator: ControllerBindingCaptureCoordinator,
    controllerRouter: ControllerEventRouter,
    onBack: () -> Unit,
) {
    val profile = remember(coreId) { CoreControllerProfiles.byCoreId(coreId ?: "") }
    if (profile == null) {
        Log.w("EmulationActivity", "Controller settings: no profile for core '${coreId}', returning to pause menu")
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val factory = ControllerSettingsViewModel.Factory(
        coreId = coreId ?: "",
        profile = profile,
        repository = repository,
        captureCoordinator = captureCoordinator,
        connectedDevicesProvider = {
            controllerRouter.connectedPhysicalDeviceIds().map { deviceId ->
                val device = InputDevice.getDevice(deviceId)
                com.romm.androidtv.controller.ui.ConnectedControllerInfo(
                    deviceId = deviceId,
                    name = device?.name,
                )
            }
        },
    )
    val viewModel: ControllerSettingsViewModel = viewModel(
        key = "pause-menu-controller-settings",
        factory = factory,
    )
    val controllerSlots by controllerRouter.slotsFlow.collectAsState()
    LaunchedEffect(controllerSlots) {
        viewModel.refreshConnectedDevices()
    }
    LaunchedEffect(viewModel) {
        controllerRouter.physicalInputActivity.collect {
            viewModel.onControllerActivity(it)
        }
    }
    val uiState by viewModel.uiState.collectAsState()
    ControllerConfigScreen(
        state = uiState,
        onBack = onBack,
        onSelectTab = viewModel::selectTab,
        onRowFocused = viewModel::onRowFocused,
        onRowSelected = viewModel::onRowSelected,
        onCaptureDialogDismiss = viewModel::dismissCaptureDialog,
        onCaptureClear = viewModel::clearPendingBinding,
        onConflictResolution = viewModel::resolveConflict,
        onResetPlayer = viewModel::resetPlayer,
        onClearMappingsConfirm = viewModel::confirmClearMappings,
        onClearMappingsRequest = viewModel::requestClearMappings,
        onClearMappingsCancel = viewModel::cancelClearMappings,
        onResetAllConfirm = viewModel::confirmResetAll,
        onResetAllRequest = viewModel::requestResetAll,
        onResetAllCancel = viewModel::cancelResetAll,
    )
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

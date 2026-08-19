package com.romm.desktop

import com.romm.androidtv.auth.ClientTokenStorage
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.emulation.model.CoreLicenseFinding
import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.library.BiosConfigurationPresenter
import com.romm.androidtv.library.BiosConfigurationProvider
import com.romm.androidtv.library.HomePresenter
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.library.RomDetailPresenter
import com.romm.androidtv.library.RomGridPresenter
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.RommTheme
import com.romm.androidtv.library.SearchPresenter
import com.romm.androidtv.library.SectionState
import com.romm.androidtv.library.SettingsPresenter
import com.romm.androidtv.network.AuthError
import com.romm.androidtv.network.AuthFlowResult
import com.romm.androidtv.network.LogSink
import com.romm.androidtv.network.RommLog
import com.romm.androidtv.onboarding.BeginQrLogin
import com.romm.androidtv.onboarding.EstablishKioskSession
import com.romm.androidtv.onboarding.LoginToRomm
import com.romm.androidtv.onboarding.OnboardingPresenter
import com.romm.androidtv.onboarding.OnboardingRoutingDecision
import com.romm.androidtv.onboarding.OnboardingRoutingDecision.AppMode
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.PersistValidatedOrigin
import com.romm.androidtv.onboarding.PollQrLogin
import com.romm.androidtv.onboarding.RemoveOldestClientToken
import com.romm.androidtv.onboarding.ValidateRommServer
import com.romm.androidtv.romm.DeviceRegistrationResult
import com.romm.androidtv.romm.FirmwareStagingOutcome
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.databaseDir
import com.romm.androidtv.storage.firmwareDir
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.androidtv.storage.romCacheDir
import com.romm.androidtv.storage.settingsFile
import com.romm.desktop.library.DesktopBiosConfigurationProvider
import com.romm.desktop.network.DesktopNetworkModule
import com.romm.desktop.player.AdoptionSummary
import com.romm.desktop.player.LINUX_X86_64_ABI
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.LaunchOutcome
import com.romm.desktop.player.LaunchRecoveryDiagnostic
import com.romm.desktop.player.OkHttpRomContentStager
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.player.PlayerLaunchParams
import com.romm.desktop.player.PrepareLaunchResult
import com.romm.desktop.player.RomContentStagingException
import com.romm.desktop.player.RomContentStagingFailure
import com.romm.desktop.player.RomContentStager
import com.romm.desktop.player.StagedContent
import com.romm.desktop.player.VideoSettings
import com.romm.desktop.player.coreLibraryFileNames
import com.romm.desktop.player.resolveCoreLibraryPath
import com.romm.desktop.settings.DesktopSettingsAdapter
import com.romm.desktop.storage.DesktopClientTokenStorage
import com.romm.desktop.storage.DesktopSessionStorage
import com.romm.desktop.storage.FileLockAppInstanceLock
import com.romm.desktop.storage.NoopSessionCookieSync
import com.romm.desktop.storage.secret.SecretBackend
import com.romm.desktop.storage.secret.SecretServiceClientTokenStore
import com.romm.desktop.storage.settings.JsonSettingsStore
import com.romm.desktop.storage.sqlite.SchedulerStateStore
import com.romm.desktop.storage.sqlite.SqliteControllerBindingStore
import com.romm.desktop.storage.sqlite.SqliteDatabase
import com.romm.desktop.storage.sqlite.SqliteDeviceIdentityStorage
import com.romm.desktop.storage.sqlite.SqliteSaveStateStore
import com.romm.desktop.storage.sqlite.SqliteSchedulerStateStore
import com.romm.desktop.storage.sqlite.SqliteSessionRecordStore
import com.romm.desktop.sync.BackgroundSyncSchedulerImpl
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.RommSyncApiGateway
import com.romm.desktop.sync.SaveSyncDeviceIdentityLoader
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import com.romm.desktop.sync.SaveSyncSessionReader
import com.romm.desktop.ui.image.DesktopImageLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import java.util.logging.Level
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The desktop app coordinator — the keystone of the Phase 6 Compose Desktop
 * browser-only product (plans/PHASE6.md, plans/LINUX_X64.md).
 *
 * Owns the full dependency graph from the Phase 5/6 desktop infrastructure:
 *
 *  - storage: [JsonSettingsStore] (settings JSON), [SqliteDatabase] + its SQLite stores
 *    (session records, save state, controller bindings, device identity, scheduler state),
 *    the [SecretBackend]-backed [SecretServiceClientTokenStore];
 *  - network seams: [DesktopSessionStorage], [DesktopClientTokenStorage],
 *    [NoopSessionCookieSync], and the [DesktopNetworkModule] client stack;
 *  - adapters/presenters: [DesktopSettingsAdapter] and lazy per-screen presenters;
 *  - infra: [DesktopBiosConfigurationProvider], [DesktopImageLoader],
 *    [SaveSyncDrainExecutor] + [BackgroundSyncSchedulerImpl] (Phase 9 save-sync drain over the
 *    durable SQLite queue), [FileLockAppInstanceLock].
 *
 * It also owns the top-level [AppMode] gate (mirroring Android's
 * `OnboardingRoutingPolicy` via the shared [OnboardingRoutingDecision]) and the explicit
 * single-[Screen] navigation state with parent-based back (NOT an activity stack), per
 * plans/LINUX_X64.md §8.1.
 *
 * @param paths            XDG path policy (or a test [AppPaths]).
 * @param secretBackend    Injectable [SecretBackend] so tests can use a fake keyring.
 * @param appVersion       Version string surfaced in Settings.
 * @param buildDefaultOrigin Compiled-in default origin fallback (like Android BuildConfig).
 * @param scope            CoroutineScope for presenter + session-verification work.
 * @param playerSupervisorOverride Test seam: inject a supervisor backed by a fake launcher;
 *                                 production uses the real `ProcessBuilder` launcher.
 * @param romDetailLookup  Test seam: ROM detail without a network fetch; production resolves
 *                         it from [romDetailPresenter]'s current UI state.
 * @param romContentStagerOverride Test seam: inject a fake stager so launch tests never touch the
 *                                 network; production downloads via the authenticated OkHttp client.
 * @param saveStateStoreOverride   Test seam: replace the SQLite [SaveStateStore] (save replicas +
 *                                 pending operations) with an in-memory store; production uses
 *                                 [SqliteSaveStateStore].
 * @param saveSyncDrainExecutorOverride Test seam: inject a [SaveSyncDrainExecutor] backed by fake
 *                                 sync seams so drain tests never touch the network; production
 *                                 wires [FileSaveContentGateway] + [RommSyncApiGateway].
 */

/** Outcome of [DesktopAppCoordinator.launchPlayer]. */
sealed interface PlayerLaunchResult {
    /** The player was spawned and its session is supervised; reconciliation happens on exit. */
    data class Started(val sessionId: String) : PlayerLaunchResult

    /** The launch could not be prepared or the spawn failed. No player is running. */
    data class Failed(val reason: String) : PlayerLaunchResult
}

/** A player session event surfaced to the UI once a supervised process exits. */
sealed interface PlayerSessionEvent {
    /** The player process exited and its launch journal was reconciled ([report]). */
    data class Ended(val sessionId: String, val report: PlayerExitReport) : PlayerSessionEvent
}

/**
 * Maps a failed [FirmwareStagingOutcome] from launch-time BIOS staging to a focused,
 * user-facing reason for [PlayerLaunchResult.Failed] (plans/LINUX_X64.md Phase 11, work
 * item 6). The three required states are kept distinct and actionable:
 *
 *  - BIOS missing / not configured ([FirmwareStagingOutcome.Missing]) — names the files the
 *    core needs and points the user at System Settings;
 *  - BIOS corrupt or wrong ([FirmwareStagingOutcome.CorruptedDownload], e.g. a SHA-1 mismatch);
 *  - BIOS download failed ([FirmwareStagingOutcome.NetworkError]).
 *
 * [platformSlug] selects the console display name ("segacd" → "SEGA CD", "psx" →
 * "PlayStation") so the message names the system the user is launching for.
 */
internal fun firmwareLaunchFailureReason(
    outcome: FirmwareStagingOutcome,
    platformSlug: String,
): String {
    val systemName = when (platformSlug) {
        "segacd" -> "SEGA CD"
        "psx" -> "PlayStation"
        else -> platformSlug.replaceFirstChar { it.uppercase() }
    }
    return when (outcome) {
        is FirmwareStagingOutcome.Success -> "" // unreachable: callers pass failures only
        is FirmwareStagingOutcome.Missing ->
            "${systemName} requires a BIOS (${outcome.fileNames.joinToString(", ")}). Configure it in System Settings."
        is FirmwareStagingOutcome.CorruptedDownload ->
            "The configured BIOS failed verification (${outcome.reason})."
        is FirmwareStagingOutcome.NetworkError -> "Could not download the BIOS: ${outcome.message}."
        FirmwareStagingOutcome.AuthExpired ->
            "Session expired; log in again to configure the $systemName BIOS."
        is FirmwareStagingOutcome.InsufficientSpace ->
            "Not enough storage to download the $systemName BIOS."
    }
}

/**
 * Maps a [RomContentStagingException] from launch-time ROM staging to a focused, user-facing
 * reason for [PlayerLaunchResult.Failed] (plans/LINUX_X64.md Phase 11, work item 6) — the
 * content-side counterpart of [firmwareLaunchFailureReason]. The distinct staging failures are
 * kept separate and actionable:
 *
 *  - malformed CHD ([RomContentStagingFailure.InvalidChdSignature]: a `.chd` file without the
 *    MComprHD signature);
 *  - empty or size-mismatched download ([RomContentStagingFailure.SizeMismatch] — an incomplete
 *    or corrupt transfer; re-downloading may heal it);
 *  - corrupt or unusable content ([RomContentStagingFailure.CorruptContent]: malformed archive,
 *    truncated entry, payload the core cannot load);
 *  - security rejection ([RomContentStagingFailure.UnsafeContent]: path-escape archive entries,
 *    extraction-limit trips);
 *  - download / write / configuration failures (infrastructure problems, surfaced with detail).
 */
internal fun romContentLaunchFailureReason(
    e: RomContentStagingException,
    fileName: String,
): String = when (e.failure) {
    RomContentStagingFailure.InvalidChdSignature ->
        "Content verification failed: '$fileName' is not a valid CHD file (missing MComprHD signature). Re-upload this ROM in your RomM library."
    RomContentStagingFailure.SizeMismatch ->
        "Content verification failed: '$fileName' is incomplete or does not match its expected size. The download may be corrupt — try launching again, or re-upload this ROM in your RomM library."
    RomContentStagingFailure.CorruptContent ->
        "Content verification failed: '$fileName' is corrupt or unreadable. Re-upload this ROM in your RomM library, or try a different file version."
    RomContentStagingFailure.UnsafeContent ->
        "Content verification failed: '$fileName' contains unsafe content and was rejected. Re-upload this ROM in your RomM library."
    RomContentStagingFailure.DownloadFailed -> "Could not download the ROM content: ${e.message}."
    RomContentStagingFailure.WriteFailed -> "Could not save the ROM content to disk: ${e.message}."
    RomContentStagingFailure.Misconfigured -> "The ROM content for this game is not available: ${e.message}."
}

class DesktopAppCoordinator(
    val paths: AppPaths,
    val secretBackend: SecretBackend,
    val appVersion: String,
    val buildDefaultOrigin: String,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    playerSupervisorOverride: LaunchJournalSupervisor? = null,
    romDetailLookup: ((Long) -> RomDetail?)? = null,
    romContentStagerOverride: RomContentStager? = null,
    saveStateStoreOverride: SaveStateStore? = null,
    saveSyncDrainExecutorOverride: SaveSyncDrainExecutor? = null,
) {

    // ------------------------------------------------------------------ storage

    val settingsStore: JsonSettingsStore = JsonSettingsStore(paths.settingsFile())

    val database: SqliteDatabase = SqliteDatabase.open(paths.databaseDir().resolve(DB_FILE_NAME))
        .getOrElse { throw IllegalStateException("Failed to open desktop database at ${paths.databaseDir()}", it) }

    private val sessionRecordStore = SqliteSessionRecordStore(database)

    /**
     * Durable save-replica + pending-operation queue (the source of truth for save sync).
     * `internal` so coordinator-level tests can assert on enqueued operations and feed the same
     * instance to an injected [saveSyncDrainExecutorOverride].
     */
    internal val saveStateStore: SaveStateStore = saveStateStoreOverride ?: SqliteSaveStateStore(database)
    private val controllerBindingStore = SqliteControllerBindingStore(database)
    private val deviceIdentityStorage = SqliteDeviceIdentityStorage(database)
    private val schedulerStateStore: SchedulerStateStore = SqliteSchedulerStateStore(database)

    private val tokenStore = SecretServiceClientTokenStore(secretBackend)

    val sessionStorage: SessionStorage = DesktopSessionStorage(sessionRecordStore)
    val clientTokenStorage: ClientTokenStorage = DesktopClientTokenStorage(tokenStore)
    private val sessionCookieSync = NoopSessionCookieSync()

    val settingsAdapter: DesktopSettingsAdapter by lazy {
        DesktopSettingsAdapter(settingsStore, sessionStorage, buildDefaultOrigin)
    }

    val network: DesktopNetworkModule by lazy {
        DesktopNetworkModule(
            sessionStorage = sessionStorage,
            clientTokenStorage = clientTokenStorage,
            deviceIdentityStorage = deviceIdentityStorage,
            sessionCookieSync = sessionCookieSync,
            originProvider = { settingsAdapter.currentProfile().origin },
            usernameProvider = {
                sessionStorage.coherentRecord(settingsAdapter.currentProfile().origin)?.username
            },
            deviceName = defaultDeviceName(),
            clientVersion = appVersion,
        )
    }

    val imageLoader: DesktopImageLoader by lazy { DesktopImageLoader(network.okHttpClient) }

    /**
     * ROM content stager for real-content launches (tests inject a fake via
     * [romContentStagerOverride]). Production downloads through the authenticated OkHttp client
     * into the XDG cache `roms/` root ([AppPaths.romCacheDir] — the "roms" subdirectory Android
     * maps for ROM content).
     */
    val romContentStager: RomContentStager by lazy {
        romContentStagerOverride ?: OkHttpRomContentStager(
            client = network.okHttpClient,
            originProvider = { settingsAdapter.currentProfile().origin },
            romCacheDir = paths.romCacheDir(),
        )
    }

    // ------------------------------------------------------------------ app mode / navigation

    /** Top-level launch mode: ONBOARDING renders the first-run flow; MAIN renders the library. */
    var appMode by mutableStateOf<AppMode>(AppMode.ONBOARDING)

    /** The single active main-mode screen (plans/LINUX_X64.md §8.1 — explicit, not a stack). */
    var currentScreen by mutableStateOf(Screen.HOME)

    /** Set true only by an explicit flow that requests application shutdown. */
    var exitRequested by mutableStateOf(false)

    // Selection state for the detail screens (mirrors MainActivity).
    var selectedPlatformId by mutableStateOf<Long?>(null)
    var selectedCollectionId by mutableStateOf<Long?>(null)
    var selectedRomId by mutableStateOf<Long?>(null)

    /** Remembers which screen opened GAME_DETAIL so Back returns to the grid that opened it. */
    var gameDetailParent by mutableStateOf(Screen.HOME)

    /** Remembers whether a browse detail was opened from Home or its top-level browse screen. */
    var platformDetailParent by mutableStateOf(Screen.HOME)
    var collectionDetailParent by mutableStateOf(Screen.HOME)

    /** Which BIOS-required console's configuration screen is active. */
    enum class BiosSystem { SEGA_CD, PLAYSTATION }
    var selectedBiosSystem by mutableStateOf(BiosSystem.SEGA_CD)

    /**
     * Memoized [RomDetailPresenter] instances keyed by ROM id so the detail screen's
     * `remember(romId) { coordinator.romDetailPresenter(romId) }` and [launchPlayer]'s
     * [detailLookup] share ONE presenter per ROM. Without this, [launchPlayer] built a fresh
     * presenter whose state is always [SectionState.Loading], so the synchronous read in
     * [detailLookup] returned null and Play always failed with "detail not loaded". Cleared when
      * navigation leaves GAME_DETAIL so visited-ROM state does not accumulate.
      *
      * Synchronized: it is touched from the Compose main thread (`remember(romId)`,
      * [onBack]'s clear) AND from Dispatchers.Default ([launchPlayer] → [detailLookup] →
      * `getOrPut`), so a plain map would be a data race.
      */
    private val detailPresenters: MutableMap<Long, RomDetailPresenter> =
        Collections.synchronizedMap(mutableMapOf())

    /** Single-instance lock (plans/LINUX_X64.md §10.4). Constructed here; acquired by Main. */
    val appInstanceLock: FileLockAppInstanceLock = FileLockAppInstanceLock(null, paths.stateDir)

    /**
     * Phase 9 save-sync drain executor: the ported Android state machine over the durable queue
     * ([saveStateStore]), with production seams — [FileSaveContentGateway] (autosave bytes under
     * the data root), [RommSyncApiGateway] (the authenticated OkHttp client), a session reader
     * backed by settings + session storage, and a device identity loader backed by
     * [DesktopNetworkModule.deviceRepository]. Tests inject a fake-backed executor via
     * [saveSyncDrainExecutorOverride].
     */
    val saveSyncDrainExecutor: SaveSyncDrainExecutor by lazy {
        saveSyncDrainExecutorOverride ?: SaveSyncDrainExecutor(
            pendingOperations = saveStateStore,
            saveReplicas = saveStateStore,
            content = FileSaveContentGateway(paths.dataDir.toFile()),
            sessionReader = SaveSyncSessionReader {
                val origin = settingsAdapter.currentProfile().origin
                if (origin.isBlank()) return@SaveSyncSessionReader null
                // coherentRecord is null without a non-blank username — kiosk sessions therefore
                // drain as AUTH_REQUIRED, matching Android's "no active session" classification.
                val record = sessionStorage.coherentRecord(origin) ?: return@SaveSyncSessionReader null
                SaveSyncSession(record.origin, record.username)
            },
            deviceIdentityLoader = SaveSyncDeviceIdentityLoader { origin, username ->
                // Runs on the scheduler's drain thread (never the UI thread), so blocking is fine.
                when (val result = runBlocking { network.deviceRepository.ensureRegistered(origin, username) }) {
                    is DeviceRegistrationResult.Success -> result.identity
                    is DeviceRegistrationResult.Failure -> null
                }
            },
            sync = RommSyncApiGateway(network.okHttpClient),
            shouldAutoclean = { settingsAdapter.autocleanSavesOnUpload() },
        )
    }

    /**
     * Background save-sync scheduler (Phase 9): the injected [drain] runs the real
     * [saveSyncDrainExecutor] on the scheduler's worker thread — a blocking call by design.
     * [SaveSyncDrainExecutor.DrainResult.Complete] → [BackgroundSyncSchedulerImpl.markDrained];
     * [SaveSyncDrainExecutor.DrainResult.Retry] → scheduleRetryAfter(highest remaining attempt
     * count) so the durable backoff reflects real retry pressure.
     */
    val scheduler: BackgroundSyncSchedulerImpl by lazy {
        BackgroundSyncSchedulerImpl(
            drain = {
                // Runs on the scheduler's worker thread (submitDrain always enqueues async), so by
                // the time this executes [scheduler] is fully initialized — the property reference
                // below resolves to the completed lazy value, never re-entrantly.
                when (val result = saveSyncDrainExecutor.drainBatch()) {
                    is SaveSyncDrainExecutor.DrainResult.Complete -> scheduler.markDrained()
                    is SaveSyncDrainExecutor.DrainResult.Retry ->
                        scheduler.scheduleRetryAfter(result.maxAttemptCount, "retryable save-sync operations remain")
                }
            },
            stateStore = schedulerStateStore,
        )
    }

    // ------------------------------------------------------------------ player supervision (Phase 8 Wave 2)

    /**
      * Launch journal supervisor for the `rommulus_player` process (plans/LINUX_X64.md §12.5).
     *
     * Integration points (the player binary itself lands in Phase 8 Wave 3+):
     * - [scanPlayerJournals] — startup crash-recovery scan; called once by [Main] before the
     *   first composition.
     * - [onPlayerProcessExited] — post-exit reconciliation hook; call it with the exit code
     *   when a spawned player process terminates.
     * - [playerSupervisor].prepareLaunch — called by [launchPlayer] to commit request + journal
     *   atomically and spawn the player.
     */
    val playerSupervisor: LaunchJournalSupervisor by lazy { playerSupervisorOverride ?: LaunchJournalSupervisor.forPaths(paths) }

    /** ROM detail without a network fetch (test seam); production reads the presenter's current UI state. */
    private val detailLookup: (Long) -> RomDetail? = romDetailLookup ?: { id ->
        (romDetailPresenter(id).uiState.value.detail as? SectionState.Loaded)?.data
    }

    /** Startup scan over incomplete launch journals (§12.5). Idempotent; safe to call more than once. */
    fun scanPlayerJournals(): List<LaunchRecoveryDiagnostic> = playerSupervisor.scanIncompleteJournals()

    /**
     * The most recent player session event for the UI: [PlayerSessionEvent.Ended] is published
     * when a supervised player process exits and its journal has been reconciled; reset to null
     * at the start of each new launch. A StateFlow (not Compose snapshot state) because it is
     * written from the daemon exit-watcher thread. The detail screen collects it while composed
     * and clears its "Launching player…" status on [PlayerSessionEvent.Ended].
     */
    val playerSessionEvents = MutableStateFlow<PlayerSessionEvent?>(null)

    /**
     * The currently running external player session, if any. The desktop shell uses this to
     * suspend its own controller navigation while SDL owns game input.
     */
    val activePlayerSessionId = MutableStateFlow<String?>(null)

    /** Post-exit reconciliation hook for a spawned player process (pass the process exit code). */
    fun onPlayerProcessExited(sessionId: String, exitCode: Int): PlayerExitReport {
        val report = playerSupervisor.onPlayerExitBySessionId(sessionId, exitCode)
        activePlayerSessionId.compareAndSet(sessionId, null)
        // Surface the reconciled outcome to the UI so the detail screen can clear its status.
        // Carry [sessionId] so the UI can ignore a stale Ended from an earlier session that is
        // still exiting when the user has already launched a new one.
        playerSessionEvents.value = PlayerSessionEvent.Ended(sessionId, report)
        // Post-play save sync (Phase 9): a session whose checkpoint was ADOPTED durably queues a
        // NEGOTIATE_AND_SYNC operation and kicks the background scheduler. Runs on this thread
        // (the exit-watcher daemon or a test thread): only local file I/O + SQLite writes, no
        // network — the actual sync happens later on the scheduler's drain thread. A failure here
        // must never break reconciliation reporting; the durable journal/queue state is untouched.
        when (report) {
            is PlayerExitReport.Reconciled -> playerLaunchContexts.remove(sessionId)?.let { context ->
                runCatching { enqueuePostPlaySync(context, report.adoption) }
                    .onFailure { e -> log.warning("post-play save-sync enqueue failed for session $sessionId: $e") }
            }
            // Journal deleted (reconciliation committed elsewhere) or never existed — the context
            // has served its purpose (or there was no launch to begin with).
            is PlayerExitReport.JournalMissing -> playerLaunchContexts.remove(sessionId)
            // CrashInterrupted / ReconcileFailed PRESERVE the journal for a later replay (a result
            // file may still appear), so keep the context: a subsequent successful reconciliation
            // of the same session must still be able to enqueue. Bounded in practice — one small
            // entry per crashed session of this process's lifetime.
            is PlayerExitReport.CrashInterrupted -> Unit
            is PlayerExitReport.ReconcileFailed -> Unit
        }
        return report
    }

    /**
     * Per-session launch metadata captured by [launchPlayer] for the post-play enqueue hook:
     * the ROM/core identity that is NOT recoverable from disk after reconciliation deletes the
     * session artifacts (the request file carries coreId/revision but no ROM file name, and both
     * are gone once the journal is cleaned). In-memory by design: it only needs to outlive the
     * process's own launch → exit window.
     */
    private data class PlayerLaunchContext(
        val romId: Long,
        val fileName: String,
        val coreId: String,
        val coreBuildRevision: String,
    )

    /** Synchronized: written on [launchPlayer]'s caller thread, read from the exit-watcher daemon. */
    private val playerLaunchContexts: MutableMap<String, PlayerLaunchContext> =
        Collections.synchronizedMap(mutableMapOf())

    /**
     * Post-play enqueue (Phase 9, mirrors Android `SaveSyncCoordinatorImpl.syncPostPlay`): when a
     * player session ends with an ADOPTED checkpoint, persist the new local generation into
     * [saveStateStore] and durably queue a [PendingOperationType.NEGOTIATE_AND_SYNC] operation,
     * then kick the scheduler — the actual negotiate/upload/download happens on the drain thread.
     *
     * Skips (no enqueue): no adopted save (no-content cores like `test_core` reconcile with
     * `adoption = null`; rejected candidates and crash exits never produce an adopted summary),
     * missing/unparseable save path, kiosk or absent sessions (Android drops those checkpoints),
     * and an unchanged checkpoint that is already SYNCED or blocked on explicit user action.
     */
    private fun enqueuePostPlaySync(context: PlayerLaunchContext, adoption: AdoptionSummary?) {
        // Only a checkpoint actually moved into place by THIS session's reconciliation enqueues.
        val targetSavePath = adoption?.takeIf { it.adopted }?.targetSavePath ?: return

        // The scope keys must match the durable save location exactly (the drain reads local bytes
        // through them), so parse them from the adopted path itself — the SavePathPolicy layout:
        // `<data root>/saves/<serverKey>/<userKey>/<romId>/<romHash>/autosave/srm.srm`.
        val scope = parseSaveScope(targetSavePath)
            ?: run { log.warning("post-play enqueue: cannot derive save scope from $targetSavePath"); return }

        if (!Files.isRegularFile(targetSavePath)) {
            log.warning("post-play enqueue: adopted save missing at $targetSavePath")
            return
        }
        val bytes = Files.readAllBytes(targetSavePath)
        val localHash = sha256Hex(bytes)

        // Session scope mirrors launchPlayer's derivation. Kiosk (anonymous) sessions have no
        // coherent record and never sync saves — Android drops those checkpoints too.
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return
        val record = sessionStorage.coherentRecord(origin) ?: return
        if (record.kioskMode || record.username.isNullOrBlank()) return

        val existing = saveStateStore.findByScope(scope)
        val checkpointChanged = existing?.localHash != localHash

        // A matching hash is only fully settled when the last server round trip succeeded;
        // otherwise re-kick an existing operation (which may have lost its drain to a crash) or
        // recreate the operation for this generation. (!checkpointChanged implies existing != null.)
        if (!checkpointChanged && existing?.syncStatus == SaveSyncStatus.SYNCED) return

        val now = System.currentTimeMillis()
        val generation = if (checkpointChanged) now else (existing?.localWrittenAtEpochMs ?: now)

        if (!checkpointChanged) {
            val active = saveStateStore.findActiveByScope(scope, PendingOperationType.NEGOTIATE_AND_SYNC)
            if (active.any { it.localGenerationEpochMs == generation }) {
                scheduler.requestDrain("post-play") // re-kick the already-queued operation
                return
            }
            // Conflict and quarantine states require explicit user action; an unchanged checkpoint
            // must not silently restart negotiation around that decision.
            if (existing?.syncStatus in BLOCKED_REPLAY_STATUSES) return
        }

        val updatedReplica = if (checkpointChanged || existing?.localWrittenAtEpochMs == null) {
            (existing ?: SaveReplicaRecord(
                serverKey = scope.serverKey,
                userKey = scope.userKey,
                romId = scope.romId,
                romHash = scope.romHash,
                slot = scope.slot,
                coreId = context.coreId,
                coreBuildRevision = context.coreBuildRevision,
            )).copy(
                // Refresh the core identity with this launch's: the drain validates the operation's
                // negotiateCoreBuildRevision against the replica, so a core change between plays
                // must not strand the new generation in PERMANENT_FAILURE.
                coreId = context.coreId,
                coreBuildRevision = context.coreBuildRevision,
                localHash = localHash,
                localSizeBytes = bytes.size.toLong(),
                localWrittenAtEpochMs = generation,
                syncStatus = SaveSyncStatus.UNSYNCED,
                lastError = null,
            )
        } else {
            // Unchanged checkpoint: keep the recorded generation + hash; refresh core identity so
            // a re-queued operation's negotiateCoreBuildRevision matches at drain time.
            // (!checkpointChanged implies an existing replica — a changed one was handled above.)
            checkNotNull(existing).copy(
                coreId = context.coreId,
                coreBuildRevision = context.coreBuildRevision,
            )
        }
        saveStateStore.upsert(updatedReplica).getOrThrow()

        // Idempotent dedupe (Android section 11.4): drop operations for OLDER generations of this
        // scope, then reuse an active operation for the current generation instead of inserting a
        // duplicate.
        saveStateStore.deleteStaleForScope(
            scope,
            PendingOperationType.NEGOTIATE_AND_SYNC,
            olderThanLocalGenerationEpochMs = generation,
        )
        val active = saveStateStore.findActiveByScope(scope, PendingOperationType.NEGOTIATE_AND_SYNC)
        if (active.none { it.localGenerationEpochMs == generation }) {
            saveStateStore.enqueue(
                PendingOperationRecord(
                    serverKey = scope.serverKey,
                    userKey = scope.userKey,
                    romId = scope.romId,
                    romHash = scope.romHash,
                    slot = scope.slot,
                    operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
                    localGenerationEpochMs = generation,
                    status = PendingOperationStatus.PENDING,
                    origin = null, // Resolved at drain time from the session store.
                    negotiateFileName = context.fileName,
                    negotiateCoreId = context.coreId,
                    negotiateCoreBuildRevision = context.coreBuildRevision,
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now,
                ),
            ).getOrThrow()
        }

        // Kick the scheduler: the drain runs on its own worker thread (blocking calls are fine
        // there); this path only did local file I/O + SQLite writes, so the player-exit path is
        // never blocked by network work.
        scheduler.requestDrain("post-play")
    }

    /**
     * Parses the [SaveReplicaScope] out of a confirmed autosave path (the inverse of
     * [SavePathPolicy.autosaveSramPath]'s layout). Returns null when the path does not follow the
     * expected `.../saves/<server>/<user>/<romId>/<romHash>/autosave/srm.srm` shape.
     */
    private fun parseSaveScope(targetSavePath: Path): SaveReplicaScope? {
        val slotDir = targetSavePath.parent ?: return null
        val romHashDir = slotDir.parent ?: return null
        val romIdDir = romHashDir.parent ?: return null
        val userDir = romIdDir.parent ?: return null
        val serverDir = userDir.parent ?: return null
        val savesDir = serverDir.parent ?: return null
        if (slotDir.fileName.toString() != SavePathPolicy.AUTOSAVE_SLOT) return null
        if (savesDir.fileName.toString() != "saves") return null
        val romId = romIdDir.fileName.toString().toLongOrNull() ?: return null
        return SaveReplicaScope(
            serverKey = serverDir.fileName.toString(),
            userKey = userDir.fileName.toString(),
            romId = romId,
            romHash = romHashDir.fileName.toString(),
            slot = SavePathPolicy.AUTOSAVE_SLOT,
        )
    }

    /**
     * Launches the desktop player for a ROM (Phase 8): resolves the ROM detail and an approved
     * core, stages real ROM content, commits request + journal atomically via [playerSupervisor], spawns
     * `rommulus_player`, and starts watching the process so reconciliation happens when it exits
     * (§12.3/§12.5).
     *
     * The ROM is staged from the server first ([romContentStager]) and BOTH
     * [PlayerLaunchParams.contentPath] and [PlayerLaunchParams.contentHash] are pinned on the
     * request. Staging failure returns [PlayerLaunchResult.Failed] — a core is NEVER launched
     * without content (fail-closed).
     *
     * Save identity: [PlayerLaunchParams.savePath] follows the shared [SavePathPolicy] layout under
     * the data root — `saves/<server>/<user>/<romId>/<content-hash>/autosave/srm.srm` — STABLE
     * across launches of the same ROM so the player's restore-on-launch finds the previous SRAM.
     * The [sessionId] still scopes the journal/session directory (unchanged).
     *
     * Threading: this function BLOCKS (content download + file I/O + process spawn) and must be
     * called off the UI thread — [GameDetailScreen] wraps it in `withContext(Dispatchers.Default)`.
     */
    fun launchPlayer(romId: Long): PlayerLaunchResult {
        // A new session supersedes any earlier exit event (the UI clears its status on Ended).
        playerSessionEvents.value = null
        val detail = detailLookup(romId) ?: return PlayerLaunchResult.Failed("detail not loaded")

        val core = resolveLaunchCore(detail.platformSlug)
            ?: return PlayerLaunchResult.Failed("console is not supported on desktop")

        val staged: StagedContent = try {
            romContentStager.stage(
                romId,
                detail.fileName,
                detail.fileSizeBytes,
                core.supportedExtensions.toSet(),
            )
        } catch (e: RomContentStagingException) {
            // Focused, user-facing states for malformed / undownloadable ROM content
            // (Phase 11 work item 6) — mirrors [firmwareLaunchFailureReason] for BIOS.
            return PlayerLaunchResult.Failed(romContentLaunchFailureReason(e, detail.fileName))
        } catch (e: Exception) {
            return PlayerLaunchResult.Failed("failed to stage ROM content: ${e.message}")
        }

        if (detail.platformSlug in BIOS_PLATFORM_SLUGS) {
            val firmware = runBlocking {
                biosConfigurationProvider(detail.platformSlug).prepareForLaunch(paths.firmwareDir())
            }
            if (firmware !is FirmwareStagingOutcome.Success) {
                // Focused, user-facing states for missing / corrupted / undownloadable BIOS
                // (Phase 11 work item 6) — never the raw outcome's toString().
                return PlayerLaunchResult.Failed(firmwareLaunchFailureReason(firmware, detail.platformSlug))
            }
        }

        val sessionId = UUID.randomUUID().toString()

        // Stable per-ROM save identity via the shared [SavePathPolicy] (mirrors Android's
        // files/saves layout under the desktop data root — always under the data root, which the
        // player validates).
        val origin = settingsAdapter.currentProfile().origin
        val username = sessionStorage.coherentRecord(origin)?.username.orEmpty()
        val savePath = Path.of(
            SavePathPolicy.autosaveSramPath(
                filesDir = paths.dataDir.toFile(),
                serverKey = origin.ifBlank { NO_ORIGIN_KEY },
                userKey = username.ifBlank { ANONYMOUS_USER_KEY },
                romId = romId,
                romHash = staged.sha256,
            ),
        )
        runCatching { Files.createDirectories(checkNotNull(savePath.parent)) }
            .getOrElse { return PlayerLaunchResult.Failed("cannot create saves directory: ${it.message}") }

        val coresDir = paths.dataDir.resolve("cores")
        val params = PlayerLaunchParams(
            coreId = core.coreId,
            // The player validates request.coreBuildRevision against the derived
            // ROMM_PLAYER_ALLOWED_CORES value, so the manifest's releaseTag (falling back to
            // commitSha) is the authoritative revision pin.
            coreBuildRevision = core.releaseTag.ifBlank { core.commitSha },
            corePath = resolveCoreLibraryPath(coresDir, core.coreId),
            contentPath = staged.path,
            contentHash = staged.sha256,
            systemDir = paths.firmwareDir(),
            savePath = savePath,
            video = VideoSettings(),
        )

        return when (val result = playerSupervisor.prepareLaunch(params, sessionId)) {
            is PrepareLaunchResult.Ready -> {
                activePlayerSessionId.value = sessionId
                // Post-play save-sync context for onPlayerProcessExited's enqueue hook: the ROM
                // file name + core identity are not recoverable from disk after reconciliation
                // deletes the session artifacts.
                playerLaunchContexts[sessionId] = PlayerLaunchContext(
                    romId = romId,
                    fileName = detail.fileName,
                    coreId = core.coreId,
                    coreBuildRevision = core.releaseTag.ifBlank { core.commitSha },
                )
                watchPlayerExit(result.launch, sessionId)
                PlayerLaunchResult.Started(sessionId)
            }
            is PrepareLaunchResult.Failed -> PlayerLaunchResult.Failed(result.reason)
        }
    }

    /**
     * Post-spawn exit watcher (§12.3): a daemon thread blocks on `ProcessHandle.onExit()` for
     * the spawned pid — which completes immediately if the process has already exited — then
     * calls [onPlayerProcessExited] so the session is reconciled as soon as the player exits
     * instead of waiting for the next startup scan. The exit code is always
     * [UNKNOWN_PLAYER_EXIT_CODE]: `ProcessHandle` does not expose it (only `java.lang.Process`
     * does) and the supervisor uses the code only in diagnostic strings. If no handle can be
     * obtained for the pid, the process is already gone and reconciliation runs immediately.
     * The thread dies with the player (or the JVM); a crash in between is still recoverable by
     * [scanPlayerJournals] at startup. Known limitation: if the OS reuses the pid before we
     * attach, we would wait on the wrong process — the startup scan remains the backstop.
     */
    private fun watchPlayerExit(launch: LaunchOutcome, sessionId: String) {
        val pid = (launch as? LaunchOutcome.Started)?.pid ?: return
        Thread {
            try {
                // Wait for the player to exit (completes immediately if it already has).
                ProcessHandle.of(pid).orElse(null)?.onExit()?.join()
            } catch (e: InterruptedException) {
                // Interrupted before exit — still reconcile; the startup scan is the backstop.
            } catch (e: Exception) {
                // No such process / platform error: it is already gone.
            }
            // ProcessHandle does not expose the exit code (only java.lang.Process does); the
            // supervisor uses the code only in diagnostic strings, so -1 ("unknown") is safe.
            onPlayerProcessExited(sessionId, UNKNOWN_PLAYER_EXIT_CODE)
        }.apply {
            isDaemon = true
            name = "player-exit-watch-$sessionId"
            start()
        }
    }

    init {
        RommLog.sink = LogSink { level, tag, message ->
            val julLevel = when (level) {
                RommLog.VERBOSE, RommLog.DEBUG -> Level.FINE
                RommLog.INFO -> Level.INFO
                RommLog.WARN -> Level.WARNING
                else -> Level.SEVERE
            }
            com.romm.desktop.log.DesktopLogger.get().log(julLevel, tag, message)
        }
    }

    // ------------------------------------------------------------------ AppMode gate

    /**
     * Pure initial-route decision, mirroring Android's `OnboardingRoutingPolicy` via the shared
     * [OnboardingRoutingDecision]. Called once on startup (before the first composition) and
     * whenever a session is invalidated.
     */
    fun decideAppMode(
        record: SessionStorage.Record?,
        profileOrigin: String?,
        hasMatchingToken: Boolean,
    ): AppMode = OnboardingRoutingDecision.decide(
        recordOrigin = record?.origin,
        recordUsername = record?.username,
        recordKioskMode = record?.kioskMode ?: false,
        profileOrigin = profileOrigin,
        hasMatchingToken = hasMatchingToken,
    )

    /** Computes the startup [AppMode] from the current persisted state (no I/O beyond reads). */
    fun computeStartupAppMode(): AppMode {
        val profile = settingsAdapter.currentProfile()
        val record = sessionStorage.coherentRecord(profile.origin)
        val token = record?.let { clientTokenStorage.getToken(it.origin, it.username.orEmpty()) }
        return decideAppMode(record, profile.origin, token != null)
    }

    /** Boot into MAIN (after startup or onboarding completion). Verifies the durable session. */
    fun enterMainMode() {
        appMode = AppMode.MAIN
        currentScreen = Screen.HOME
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return
        // Kiosk (anonymous read-only demo) sessions carry no durable client token by design, so
        // verifyDurableSession would fail locally with VERIFICATION_FAILED and the failure branch
        // below would delete the just-saved session — the "flash back to onboarding" bug. Kiosk
        // records are coherent without a token (same rule as computeStartupAppMode /
        // OnboardingRoutingDecision), so skip verification entirely and stay in MAIN.
        if (sessionStorage.coherentRecord(origin)?.kioskMode == true) return
        scope.launch {
            when (val result = network.authRepository.verifyDurableSession(origin)) {
                is AuthFlowResult.Success -> currentScreen = Screen.HOME
                is AuthFlowResult.Failure -> {
                    if (result.error == AuthError.VERIFICATION_FAILED) {
                        // Session is definitively expired/invalid: clear and route back to onboarding
                        // (mirrors MainActivity). Transient errors do NOT clear the session.
                        val stale = sessionStorage.coherentRecord(origin)
                        if (stale != null) {
                            network.authRepository.clearExpiredSession(stale.origin, stale.username.orEmpty())
                        }
                        appMode = AppMode.ONBOARDING
                    }
                }
            }
        }
    }

    /** Called when onboarding completes; switches the whole app to MAIN. */
    fun onOnboardingCompleted() {
        enterMainMode()
    }

    // ------------------------------------------------------------------ navigation

    /** Main-mode back moves up one view; Home remains the root and never exits the app. */
    fun onBack() {
        if (appMode != AppMode.MAIN) return
        if (currentScreen == Screen.GAME_DETAIL) detailPresenters.clear()
        currentScreen = when (currentScreen) {
            Screen.HOME -> Screen.HOME
            Screen.GAME_DETAIL -> gameDetailParent
            Screen.PLATFORM_DETAIL -> platformDetailParent
            Screen.COLLECTION_DETAIL -> collectionDetailParent
            else -> currentScreen.parent()
        }
    }

    fun navigate(screen: Screen) {
        currentScreen = screen
    }

    fun openPlatformDetail(platformId: Long) {
        selectedPlatformId = platformId
        platformDetailParent = currentScreen.takeIf {
            it == Screen.HOME || it == Screen.PLATFORMS
        } ?: Screen.PLATFORMS
        currentScreen = Screen.PLATFORM_DETAIL
    }

    fun openCollectionDetail(collectionId: Long) {
        selectedCollectionId = collectionId
        collectionDetailParent = currentScreen.takeIf {
            it == Screen.HOME || it == Screen.COLLECTIONS
        } ?: Screen.COLLECTIONS
        currentScreen = Screen.COLLECTION_DETAIL
    }

    fun openGameDetail(romId: Long, parent: Screen) {
        selectedRomId = romId
        gameDetailParent = parent
        currentScreen = Screen.GAME_DETAIL
    }

    fun openBiosConfiguration(system: BiosSystem) {
        selectedBiosSystem = system
        currentScreen = Screen.BIOS_CONFIGURATION
    }

    // ------------------------------------------------------------------ presenters (lazy per screen)

    fun onboardingPresenter(): OnboardingPresenter = onboardingPresenterLazy

    fun settingsPresenter(): SettingsPresenter = settingsPresenterLazy

    private val homePresenterLazy: HomePresenter by lazy {
        HomePresenter(
            scope = scope,
            repository = network.libraryRepository,
            hideUnsupportedSystems = { settingsAdapter.hideUnsupportedSystems() },
        )
    }

    fun homePresenter(): HomePresenter = homePresenterLazy

    fun searchPresenter(): SearchPresenter = SearchPresenter(
        scope = scope,
        repository = network.libraryRepository,
        hideUnsupportedSystems = { settingsAdapter.hideUnsupportedSystems() },
    )

    fun romGridPresenter(query: RomQuery): RomGridPresenter = RomGridPresenter(
        scope = scope,
        repository = network.libraryRepository,
        query = query,
        hideUnsupportedSystems = { settingsAdapter.hideUnsupportedSystems() },
    )

    fun romDetailPresenter(romId: Long): RomDetailPresenter =
        // synchronized: getOrPut on a Collections.synchronizedMap is not atomic — two threads
        // could both miss the key and construct two presenters. Lock on the map so the
        // check-then-put is a single critical section.
        synchronized(detailPresenters) {
            detailPresenters.getOrPut(romId) {
                RomDetailPresenter(
                    scope = scope,
                    repository = network.libraryRepository,
                    romId = romId,
                )
            }
        }

    fun biosConfigurationPresenter(platformSlug: String): BiosConfigurationPresenter =
        BiosConfigurationPresenter(scope, biosConfigurationProvider(platformSlug))

    fun biosConfigurationProvider(platformSlug: String): DesktopBiosConfigurationProvider =
        DesktopBiosConfigurationProvider(
            client = network.okHttpClient,
            originProvider = { settingsAdapter.currentProfile().origin },
            firmwareDir = paths.firmwareDir(),
            platformSlug = platformSlug,
        )

    // ------------------------------------------------------------------ internals

    /**
     * Resolves the core to launch for a platform slug.
     *
     * Resolves the platform's approved core, but ONLY when it is approved for the
     * [LINUX_X86_64_ABI] ABI and its shared library is actually installed in the desktop
     * data root's `cores/` directory. Unsupported and uninstalled platforms return null.
     */
    private fun resolveLaunchCore(platformSlug: String): CoreLicenseFinding? {
        val coresDir = paths.dataDir.resolve("cores")
        val installed: (CoreLicenseFinding) -> Boolean = { core ->
            coreLibraryFileNames(core.coreId).any { Files.exists(coresDir.resolve(it)) }
        }
        return CoreManifest.approvedEntries().firstOrNull {
            it.supportedSystems.contains(platformSlug) &&
                LINUX_X86_64_ABI in it.supportedAbis &&
                installed(it)
        }
    }

    /** Whether [platformSlug] has an approved, installed Linux desktop core. */
    fun isPlatformPlayable(platformSlug: String): Boolean = resolveLaunchCore(platformSlug) != null

    private val onboardingPresenterLazy by lazy {
        OnboardingPresenter(
                scope = scope,
                validateRommServer = ValidateRommServer { origin -> network.authRepository.validateServer(origin) },
                persistValidatedOrigin = PersistValidatedOrigin { origin -> settingsAdapter.persistValidatedOrigin(origin) },
                loginToRomm = LoginToRomm { origin, username, password ->
                    network.authRepository.loginOnboarding(origin, username, password)
                },
                removeOldestClientToken = RemoveOldestClientToken { origin ->
                    network.authRepository.removeOldestClientToken(origin)
                },
                establishKioskSession = EstablishKioskSession { origin ->
                    network.authRepository.establishKioskSession(origin)
                },
                beginQrLogin = BeginQrLogin { origin -> network.qrLoginRepository.start(origin) },
                pollQrLogin = PollQrLogin { origin, session -> network.qrLoginRepository.poll(origin, session) },
                initialServerInput = settingsAdapter.currentProfile().origin,
                initialStep = OnboardingStep.WELCOME,
                initialUsername = "",
            )
    }

    private val settingsPresenterLazy by lazy {
        SettingsPresenter(
                scope = scope,
                getCurrentProfile = { settingsAdapter.currentProfile() },
                setOriginFn = settingsAdapter::setOrigin,
                clearOverrideFn = settingsAdapter::clearOverride,
                getSessionRecord = { settingsAdapter.sessionRecord() },
                clearSessionFn = settingsAdapter::clearSession,
                checkHeartbeatFn = { origin -> network.authRepository.checkHeartbeat(origin) },
                loginFn = { origin, username, password ->
                    network.authRepository.login(origin, username, password)
                },
                onLoginSuccess = { currentScreen = Screen.HOME },
                onSessionInvalidated = { appMode = AppMode.ONBOARDING },
                getHideUnsupportedSystems = { settingsAdapter.hideUnsupportedSystems() },
                setHideUnsupportedSystemsFn = settingsAdapter::setHideUnsupportedSystems,
                getVerifySha1OnLaunch = { settingsAdapter.verifySha1OnLaunch() },
                setVerifySha1OnLaunchFn = settingsAdapter::setVerifySha1OnLaunch,
                getAutocleanSavesOnUpload = { settingsAdapter.autocleanSavesOnUpload() },
                setAutocleanSavesOnUploadFn = settingsAdapter::setAutocleanSavesOnUpload,
                getOnScreenGameControlsEnabled = { settingsAdapter.onScreenGameControlsEnabled() },
                setOnScreenGameControlsEnabledFn = settingsAdapter::setOnScreenGameControlsEnabled,
                getTheme = { settingsAdapter.theme() },
                setThemeFn = settingsAdapter::setTheme,
                applyTheme = { theme: RommTheme -> /* theme applied by shell from settingsAdapter */ },
                appVersion = appVersion,
                buildDefaultOrigin = buildDefaultOrigin,
            )
    }

    private companion object {
        const val DB_FILE_NAME = "rommulus.db"

        /** Replica states that require explicit user action — an unchanged checkpoint must not
         *  silently restart negotiation around them (mirrors Android syncPostPlay). */
        val BLOCKED_REPLAY_STATUSES = setOf(
            SaveSyncStatus.CONFLICT,
            SaveSyncStatus.QUARANTINED,
            SaveSyncStatus.AWAITING_CORE_VALIDATION,
            SaveSyncStatus.PENDING_DOWNLOAD,
        )

        private val log: java.util.logging.Logger = java.util.logging.Logger.getLogger("DesktopAppCoordinator")

        /** [SavePathPolicy] server key when no origin is configured. */
        private const val NO_ORIGIN_KEY = "no-origin"

        /** [SavePathPolicy] user key when the session record carries no username (e.g. kiosk). */
        private const val ANONYMOUS_USER_KEY = "anonymous"

        /** Exit code passed to [onPlayerProcessExited] by the watcher (see [watchPlayerExit]). */
        const val UNKNOWN_PLAYER_EXIT_CODE = -1
        val BIOS_PLATFORM_SLUGS = setOf("segacd", "psx")

        fun defaultDeviceName(): String {
            return try {
                java.net.InetAddress.getLocalHost().hostName
            } catch (_: Exception) {
                "romm-desktop"
            }
        }
    }
}

/**
 * The single explicit navigation destination (plans/LINUX_X64.md §8.1). The desktop
 * deliberately uses ONE [Screen] enum + a parent map for back — NOT an activity stack.
 */
enum class Screen {
    ONBOARDING, HOME, PLATFORMS, COLLECTIONS, SEARCH, SETTINGS,
    PLATFORM_DETAIL, COLLECTION_DETAIL, GAME_DETAIL, BIOS_CONFIGURATION, LICENSE;

    /** The screen Back returns to (root returns nothing; GAME_DETAIL uses its remembered parent). */
    fun parent(): Screen = when (this) {
        HOME -> HOME
        PLATFORMS, COLLECTIONS, SEARCH, SETTINGS, LICENSE -> HOME
        PLATFORM_DETAIL -> PLATFORMS
        COLLECTION_DETAIL -> COLLECTIONS
        BIOS_CONFIGURATION -> SETTINGS
        GAME_DETAIL, ONBOARDING -> HOME
    }
}

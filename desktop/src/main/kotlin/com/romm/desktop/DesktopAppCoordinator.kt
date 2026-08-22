package com.romm.desktop

import com.romm.androidtv.auth.ClientTokenStorage
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.controller.config.ControllerConfigRepository
import com.romm.androidtv.emulation.model.CoreLicenseFinding
import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.library.BiosConfigurationCatalog
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
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.romm.save.SaveSyncRequest
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.databaseDir
import com.romm.androidtv.storage.firmwareDir
import com.romm.androidtv.storage.ports.ContentIndexStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.androidtv.storage.romCacheDir
import com.romm.androidtv.storage.settingsFile
import com.romm.desktop.controller.JInputControllerSource
import com.romm.desktop.controller.JInputSource
import com.romm.desktop.controller.config.DesktopControllerConfigRepository
import com.romm.desktop.library.DesktopBiosConfigurationProvider
import com.romm.desktop.network.DesktopNetworkModule
import com.romm.desktop.player.AdoptionSummary
import com.romm.desktop.player.CONTROLLER_BINDINGS_SIDECAR_FILE_NAME
import com.romm.desktop.player.ControllerBindingSidecarCodec
import com.romm.desktop.player.ControllerBindings
import com.romm.desktop.player.LINUX_X86_64_ABI
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.LaunchOutcome
import com.romm.desktop.player.LaunchRecoveryDiagnostic
import com.romm.desktop.player.OkHttpRomContentStager
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.player.PlayerLaunchParams
import com.romm.desktop.player.PlayerProtocol
import com.romm.desktop.player.PrepareLaunchResult
import com.romm.desktop.player.RetroPadControlMapping
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
import com.romm.desktop.storage.contentindex.JsonContentIndexStore
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
import com.romm.desktop.sync.DefaultRommSaveContentVerifier
import com.romm.desktop.sync.DesktopSaveLaunchSynchronizer
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.GameLaunchRecorder
import com.romm.desktop.sync.PreLaunchDeviceIdentityLoader
import com.romm.desktop.sync.PreLaunchSaveSynchronizer
import com.romm.desktop.sync.RommSaveContentHash
import com.romm.desktop.sync.RommSaveContentVerification
import com.romm.desktop.sync.RommSaveContentVerifier
import com.romm.desktop.sync.RommSyncApiGateway
import com.romm.desktop.sync.RommSyncGateway
import com.romm.desktop.sync.SaveConflictChoice
import com.romm.desktop.sync.SaveConflictResolutionResult
import com.romm.desktop.sync.SaveConflictResolver
import com.romm.desktop.sync.SaveSyncDeviceIdentityLoader
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import com.romm.desktop.sync.SaveSyncSessionReader
import com.romm.desktop.ui.image.DesktopImageLoader
import com.romm.desktop.ui.screens.detail.SavePickerEntryUiModel
import com.romm.desktop.ui.screens.detail.SaveSyncStatusPresenter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
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
 *  - storage: [JsonSettingsStore] (settings JSON), [JsonContentIndexStore] (content cache
 *    index), [SqliteDatabase] + its SQLite stores (session records, save state,
 *    controller bindings, device identity, scheduler state), the [SecretBackend]-backed
 *    [SecretServiceClientTokenStore];
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
     * @param saveConflictResolverOverride Test seam: inject a [SaveConflictResolver] over fake seams
     *                                 so conflict-resolution tests never touch the network/filesystem.
     * @param gameLaunchRecorderOverride Test seam: inject a [GameLaunchRecorder] over a fake
     *                                 [com.romm.desktop.sync.RommSyncGateway] so play-session
     *                                 tests never touch the network; production records through
     *                                 [RommSyncApiGateway].
     * @param preLaunchSaveSynchronizerOverride Test seam for synchronous save negotiation before
     *                                 the player is staged/spawned.
     * @param chosenSaveContentVerifierOverride Test seam for RomM `content_hash` verification in
     *                                 the explicit Choose Save flow.
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

/** Outcome of adopting an explicitly chosen server save into a launch's autosave path. */
sealed interface ChosenSaveAdoption {
    /** The chosen save's bytes now sit at the launch's save path; the player will restore it. */
    data object Success : ChosenSaveAdoption

    /** [reason] is safe to surface in the UI as-is; nothing was written and no launch happened. */
    data class Failure(val reason: String) : ChosenSaveAdoption
}

/**
 * Per-ROM game detail launch state (Android `SavePreLaunchState` parity): whether a launch is
 * currently staging ("Preparing…" disabled Play button) or the session expired during the last
 * attempt. Scoped by [romId] — the screen applies it only while [matchesScope] holds, so a stale
 * state for another ROM never leaks across sibling versions. Transient failure messages are
 * deliberately NOT here: desktop surfaces those via `PlayerLaunchResult.Failed` under the Play
 * button (the screen's own `playStatus`).
 */
data class GameLaunchUiState(
    val romId: Long,
    val isStaging: Boolean = false,
    val isAuthExpired: Boolean = false,
) {
    /** True when this state belongs to the given ROM (Android `matchesScope` parity). */
    fun matchesScope(romId: Long): Boolean = this.romId == romId
}

/**
 * Required-BIOS availability for a BIOS-required console (SEGA CD / PlayStation), desktop mirror
 * of Android's `RequiredBiosState`. Drives the game detail screen's inline BIOS-unavailable
 * state; [Ready] renders the normal Play row.
 */
sealed interface RequiredBiosState {
    data object Checking : RequiredBiosState
    data object Ready : RequiredBiosState
    data object Missing : RequiredBiosState
    data object UnverifiedAvailable : RequiredBiosState
    data class Error(val message: String) : RequiredBiosState
}

/**
 * Whether a [PlayerLaunchResult.Failed] reason is an auth-expired condition (the desktop
 * counterpart of Android routing `PreparationResult.AuthExpired` / token-verification failures to
 * `SavePreLaunchState.isAuthExpired`). The launch pipeline produces exactly two families: the
 * BIOS staging "Session expired; log in again…" message and the chosen-save adoption's
 * "no active session — …" messages.
 */
internal fun launchFailureIsAuthExpired(reason: String): Boolean =
    reason.startsWith("Session expired") || reason.contains("no active session")

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
    saveConflictResolverOverride: SaveConflictResolver? = null,
    syncGatewayOverride: RommSyncGateway? = null,
    saveSyncDeviceIdentityLoaderOverride: SaveSyncDeviceIdentityLoader? = null,
    preLaunchSaveSynchronizerOverride: PreLaunchSaveSynchronizer? = null,
    chosenSaveContentVerifierOverride: RommSaveContentVerifier? = null,
    gameLaunchRecorderOverride: GameLaunchRecorder? = null,
    desktopEnvironment: Map<String, String> = emptyMap(),
) {
    private val displayPolicy = desktopDisplayPolicy(desktopEnvironment)

    // ------------------------------------------------------------------ storage

    val settingsStore: JsonSettingsStore = JsonSettingsStore(paths.settingsFile())

    /**
     * Content cache index (parity with Android's `CacheDatabaseContentIndexStore`): a
     * JSON-file-backed LRU index over the evictable ROM/firmware cache files, at
     * `cacheDir/content-index.json`. Parity infrastructure for now — the stager will
     * upsert records once cache-eviction logic lands.
     */
    val contentIndexStore: ContentIndexStore by lazy {
        JsonContentIndexStore(paths.cacheDir.resolve(JsonContentIndexStore.FILE_NAME))
    }

    val database: SqliteDatabase = SqliteDatabase.open(paths.databaseDir().resolve(DB_FILE_NAME))
        .getOrElse { throw IllegalStateException("Failed to open desktop database at ${paths.databaseDir()}", it) }

    private val sessionRecordStore = SqliteSessionRecordStore(database)

    /**
     * Durable save-replica + pending-operation queue (the source of truth for save sync).
     * `internal` so coordinator-level tests can assert on enqueued operations and feed the same
     * instance to an injected [saveSyncDrainExecutorOverride].
     */
    internal val saveStateStore: SaveStateStore = saveStateStoreOverride ?: SqliteSaveStateStore(database)

    /**
     * Durable controller-binding overrides (LINUX_X64.md §11.9). `internal` so coordinator-level
     * tests can assert on ingested rows; production traffic goes through [ingestControllerBindingSidecar]
     * and [loadLaunchControllerBindings].
     */
    internal val controllerBindingStore = SqliteControllerBindingStore(database)

    /**
     * Desktop [ControllerConfigRepository] for the controller-settings screens (E2): catalog
     * defaults merged over the durable overrides in [controllerBindingStore]. The same store
     * instance the player-launch sidecar ingest uses, so remaps made in Settings are picked up
     * by [loadLaunchControllerBindings] on the next launch.
     */
    val controllerConfigRepository: DesktopControllerConfigRepository by lazy {
        DesktopControllerConfigRepository(controllerBindingStore)
    }

    /**
     * The single shared JInput enumeration seam (E2): consumed both by the desktop focus
     * router (via [RommulusDesktopApp]) and by the controller-settings capture pump, so one
     * native-environment bootstrap serves both.
     */
    val controllerInputSource: JInputSource by lazy { JInputControllerSource() }

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

    /** Which core's controller-configuration screen is active (CONTROLLER_CONFIG). */
    var selectedControllerCoreId by mutableStateOf<String?>(null)

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
    /**
     * Durable-session reader shared by the drain executor and the conflict resolver: null without
     * a coherent non-kiosk session (blank origin, kiosk/anonymous record) — those sessions then
     * classify AUTH_REQUIRED / "no active session", matching Android.
     */
    private val saveSyncSessionReader = SaveSyncSessionReader {
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return@SaveSyncSessionReader null
        // coherentRecord is null without a non-blank username — kiosk sessions therefore
        // drain as AUTH_REQUIRED, matching Android's "no active session" classification.
        val record = sessionStorage.coherentRecord(origin) ?: return@SaveSyncSessionReader null
        SaveSyncSession(origin, record.username, record.kioskMode)
    }

    /**
     * Device-identity loader shared by the drain executor, the conflict resolver, and the save
     * picker ("Choose Save" listSaves + chosen-save adoption). Tests inject a fake via
     * [saveSyncDeviceIdentityLoaderOverride] so those flows never register over the network.
     */
    private val saveSyncDeviceIdentityLoader: SaveSyncDeviceIdentityLoader =
        saveSyncDeviceIdentityLoaderOverride ?: SaveSyncDeviceIdentityLoader { origin, username ->
            // Runs on the scheduler's drain thread or a worker (never the UI thread), so blocking is fine.
            when (val result = runBlocking { network.deviceRepository.ensureRegistered(origin, username) }) {
                is DeviceRegistrationResult.Success -> result.identity
                is DeviceRegistrationResult.Failure -> null
            }
        }

    private val preLaunchDeviceIdentityLoader = PreLaunchDeviceIdentityLoader { origin, username ->
        if (saveSyncDeviceIdentityLoaderOverride != null) {
            saveSyncDeviceIdentityLoaderOverride.load(origin, username)?.let {
                DeviceRegistrationResult.Success(it, alreadyExisted = true)
            } ?: DeviceRegistrationResult.Failure(RommApiError.AUTH_EXPIRED)
        } else {
            runBlocking { network.deviceRepository.ensureRegistered(origin, username) }
        }
    }

    val saveSyncDrainExecutor: SaveSyncDrainExecutor by lazy {
        saveSyncDrainExecutorOverride ?: SaveSyncDrainExecutor(
            pendingOperations = saveStateStore,
            saveReplicas = saveStateStore,
            content = saveContentGateway,
            sessionReader = saveSyncSessionReader,
            deviceIdentityLoader = saveSyncDeviceIdentityLoader,
            sync = syncGateway,
            shouldAutoclean = { settingsAdapter.autocleanSavesOnUpload() },
        )
    }

    /**
     * Explicit conflict resolver (Phase 9 — the user-facing half of "conflict preserves both
     * copies"): honors the detail screen's Keep-local / Keep-server choice for a CONFLICT replica.
     * Production seams mirror the drain executor's: [FileSaveContentGateway] over the data root,
     * the shared session/identity readers, and [RommSyncApiGateway] on the authenticated client.
     */
    val saveConflictResolver: SaveConflictResolver by lazy {
        saveConflictResolverOverride ?: SaveConflictResolver(
            saveReplicas = saveStateStore,
            content = saveContentGateway,
            sessionReader = saveSyncSessionReader,
            deviceIdentityLoader = saveSyncDeviceIdentityLoader,
            sync = syncGateway,
            shouldAutoclean = { settingsAdapter.autocleanSavesOnUpload() },
        )
    }

    /**
     * RomM sync HTTP surface for the save-picker UI ("Choose Save" — Phase 9 parity): lists the
     * server saves for a ROM and downloads an explicitly chosen one. Production: [RommSyncApiGateway]
     * on the authenticated client; tests inject a fake via [syncGatewayOverride].
     */
    val syncGateway: RommSyncGateway by lazy {
        syncGatewayOverride ?: RommSyncApiGateway(network.okHttpClient)
    }

    private val chosenSaveContentVerifier =
        chosenSaveContentVerifierOverride ?: DefaultRommSaveContentVerifier

    private val saveContentGateway by lazy {
        FileSaveContentGateway(paths.dataDir.toFile())
    }

    /**
     * Synchronous Android-equivalent pre-launch negotiation. It shares the durable save store,
     * authenticated gateway, device identity, and filesystem layout used by the background drain.
     */
    val preLaunchSaveSynchronizer: PreLaunchSaveSynchronizer by lazy {
        preLaunchSaveSynchronizerOverride ?: DesktopSaveLaunchSynchronizer(
            saveState = saveStateStore,
            content = saveContentGateway,
            sessionReader = saveSyncSessionReader,
            deviceIdentityLoader = preLaunchDeviceIdentityLoader,
            sync = syncGateway,
            onOperationQueued = { scheduler.requestDrain("pre-launch") },
        )
    }

    /**
     * Play-session recorder (parity with Android's `GameLaunchRecorder`): marks a ROM as played
     * when its player session STARTS — a 1ms session ending at the launch instant, reported
     * off-thread so `last_played`/`now_playing` (the Home screen's "Continue Playing" row)
     * reflect the launch immediately. Shares the drain's session/identity seams and reports
     * through [RommSyncApiGateway] on the authenticated client.
     */
    val gameLaunchRecorder: GameLaunchRecorder by lazy {
        gameLaunchRecorderOverride ?: GameLaunchRecorder(
            gateway = RommSyncApiGateway(network.okHttpClient),
            sessionReader = saveSyncSessionReader,
            deviceIdentityLoader = saveSyncDeviceIdentityLoader,
            onRecorded = { refreshContinuePlayingIfInitialized() },
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
    /**
     * Production wiring passes [ingestControllerBindingSidecar] as the sidecar ingestor so a
     * finished session's `<sessionDir>/controller-bindings.json` is persisted into
     * [controllerBindingStore] BEFORE reconciliation deletes the session artifacts (§11.9).
     */
    val playerSupervisor: LaunchJournalSupervisor by lazy {
        playerSupervisorOverride ?: LaunchJournalSupervisor.forPaths(paths, ::ingestControllerBindingSidecar)
    }

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

    /**
     * Game detail launch state for the current ROM (Android `preLaunchState` parity). The detail
     * screen collects this to render the "Preparing…" staging Play button and the "Session
     * expired" + Log in state. A StateFlow (not Compose snapshot state) because [finishGameLaunch]
     * runs on a worker thread (the same one that calls [launchPlayer]).
     */
    val gameLaunchState = MutableStateFlow<GameLaunchUiState?>(null)

    /**
     * Begin a launch for [romId] (Android `nativeLibraryOnPlay` parity): publishes the staging
     * state so the detail screen shows a disabled "Preparing…" Play button. Duplicate-entry guard:
     * returns false without touching state when a launch for the same ROM is already staging, so
     * repeated clicks cannot restart the pipeline.
     */
    fun beginGameLaunch(romId: Long): Boolean {
        val existing = gameLaunchState.value
        if (existing != null && existing.matchesScope(romId) && existing.isStaging) return false
        gameLaunchState.value = GameLaunchUiState(romId, isStaging = true)
        return true
    }

    /**
     * End a launch for [romId] with its [result]: clears staging on success; on an auth-expired
     * failure publishes the "Session expired" state so the detail screen offers the Log in action;
     * any other failure clears the state entirely (the screen surfaces the reason via
     * `PlayerLaunchResult.Failed` under the Play button). No-op when no launch for [romId] is
     * tracked (e.g. the user navigated away mid-launch).
     */
    fun finishGameLaunch(romId: Long, result: PlayerLaunchResult) {
        val current = gameLaunchState.value
        if (current == null || !current.matchesScope(romId)) return
        gameLaunchState.value = when {
            result is PlayerLaunchResult.Failed && launchFailureIsAuthExpired(result.reason) ->
                GameLaunchUiState(romId, isAuthExpired = true)
            else -> null
        }
    }

    /** Clears the detail screen's launch state (the "Dismiss" action on the auth-expired state). */
    fun dismissGameLaunchState() {
        gameLaunchState.value = null
    }

    /** Post-exit reconciliation hook for a spawned player process (pass the process exit code). */
    fun onPlayerProcessExited(sessionId: String, exitCode: Int): PlayerExitReport {
        val report = playerSupervisor.onPlayerExitBySessionId(sessionId, exitCode)
        activePlayerSessionId.compareAndSet(sessionId, null)
        // A play session is independent of SRAM. Refresh after every exit so an unchanged save
        // (or a core with no SRAM at all) still moves the game to the front of Continue Playing.
        refreshContinuePlayingIfInitialized()
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
            is PlayerExitReport.Reconciled -> {
                report.result.video?.let { video ->
                    settingsAdapter.setVideoOptions(
                        scanlines = video.scanlines,
                        integerScaling = video.integerScaling,
                        sharpFilter = video.sharpFilter,
                    )
                }
                playerLaunchContexts.remove(sessionId)?.let { context ->
                    runCatching { enqueuePostPlaySync(context, report.adoption) }
                        .onFailure { e -> log.warning("post-play save-sync enqueue failed for session $sessionId: $e") }
                }
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
     * Ingests the player's controller-binding sidecar (LINUX_X64.md §11.9): parses
     * `<sessionDir>/controller-bindings.json` and upserts each device's 12-slot table into
     * [controllerBindingStore] under the session's core — the sidecar carries no core identity,
     * so it is bound to the session's own request file (strictly re-parsed from disk). On success
     * the sidecar is DELETED (it is a session artifact); on any failure it is preserved for
     * forensics and reconciliation proceeds untouched.
     *
     * Never throws (the [LaunchJournalSupervisor] ingestor contract) and idempotent: absent or
     * already-ingested files are no-ops.
     */
    internal fun ingestControllerBindingSidecar(sessionDir: Path) {
        val sidecarPath = sessionDir.resolve(CONTROLLER_BINDINGS_SIDECAR_FILE_NAME)
        if (!Files.isRegularFile(sidecarPath)) return

        val coreId = runCatching {
            Files.readString(sessionDir.resolve("request.json"))
                .let { PlayerProtocol.parseRequest(it).getOrNull()?.coreId }
        }.getOrNull()
        if (coreId.isNullOrBlank()) {
            log.warning("binding sidecar ingestion skipped for $sessionDir: request file missing or unparseable; sidecar preserved")
            return
        }

        val text = runCatching { Files.readString(sidecarPath) }.getOrElse { e ->
            log.warning("binding sidecar unreadable at $sidecarPath: $e; preserved")
            return
        }
        val sidecar = ControllerBindingSidecarCodec.parse(text).getOrElse { e ->
            log.warning("binding sidecar unusable at $sidecarPath: ${e.message}; preserved")
            return
        }

        // Every device entry carries the player's single global table; upsert per device (the
        // last device wins — identical tables in practice, so this is a no-op).
        val records = sidecar.devices.flatMap { device -> RetroPadControlMapping.toRecords(coreId, device) }
        controllerBindingStore.upsertAll(records)
            .onSuccess {
                controllerConfigRepository.refreshFromStore(coreId)
                runCatching { Files.deleteIfExists(sidecarPath) }.onFailure { e ->
                    log.warning("binding sidecar ingested but could not be deleted at $sidecarPath: $e")
                }
            }
            .onFailure { e ->
                log.warning("binding sidecar ingestion failed for core $coreId: $e; sidecar preserved")
            }
    }

    /**
     * Loads the stored binding table for [coreId] and serializes it into the v2 request's
     * `controllerBindings` field so the player applies the user's remaps from the FIRST FRAME.
     * Returns null when nothing is stored (or the stored table is incomplete) — the field is then
     * omitted and the player keeps its built-in defaults.
     */
    private fun loadLaunchControllerBindings(coreId: String): ControllerBindings? = runCatching {
        val records = controllerConfigRepository.effectiveLaunchRecords(
            coreId,
            RetroPadControlMapping.PLAYER_INDEX,
        )
        RetroPadControlMapping.toLaunchBindings(records)
    }.getOrElse { e ->
        log.warning("loading stored controller bindings for $coreId failed: $e; launching with defaults")
        null
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
     * Downloads [entry]'s server save and atomically writes it to [savePath] (the launch's
     * autosave path), so the player restores exactly the save the user picked in the "Choose
     * Save" flow (Android `adoptChosenSave` parity). Validates recognized RomM server fingerprints
     * when the listing carried one; confirms the download best-effort AFTER a
     * successful write (mirrors keep-server — never fails an otherwise-good launch). Never
     * throws: every failure maps to [ChosenSaveAdoption.Failure] with a UI-safe reason, so the
     * caller aborts the launch fail-closed. Unlike Android's negotiate-driven download path
     * there is deliberately NO provenance gate: SRAM saves are cross-core compatible for the
     * same platform and the user explicitly picked this save from this ROM's own listing.
     */
    private fun adoptChosenSave(entry: SavePickerEntryUiModel, savePath: Path): ChosenSaveAdoption {
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return ChosenSaveAdoption.Failure("no active session — log in again to use a saved game")
        val record = sessionStorage.coherentRecord(origin)
            ?: return ChosenSaveAdoption.Failure("no active session — log in again to use a saved game")
        if (record.kioskMode) return ChosenSaveAdoption.Failure("kiosk sessions have no server saves to adopt")
        val username = record.username
            ?: return ChosenSaveAdoption.Failure("no active session — log in again to use a saved game")
        val deviceId = saveSyncDeviceIdentityLoader.load(origin, username)?.rommDeviceId
            ?: return ChosenSaveAdoption.Failure("device not registered — cannot download the chosen save")

        val bytes = when (val download = syncGateway.downloadSaveContent(origin, entry.saveId, deviceId, null)) {
            is SaveDownloadResult.Success -> download.bytes
            is SaveDownloadResult.Failure ->
                return ChosenSaveAdoption.Failure("could not download the chosen save: ${download.error}")
        }

        val reportedHash = entry.contentHash
        val expectedHash = RommSaveContentHash.parseOrNull(reportedHash)
        if (reportedHash != null && expectedHash == null) {
            // Android treats content_hash as carried server metadata rather than a SHA-256
            // contract. Preserve that behavior for unknown/future formats instead of rejecting
            // valid bytes with an invented digest algorithm.
            log.warning("chosen-save adoption: unsupported RomM content_hash format; skipping hash verification")
        } else if (expectedHash != null) {
            when (chosenSaveContentVerifier.verify(bytes, expectedHash)) {
                RommSaveContentVerification.Match -> Unit
                is RommSaveContentVerification.Mismatch ->
                    return ChosenSaveAdoption.Failure(
                        "chosen save failed verification (content hash mismatch)",
                    )
                is RommSaveContentVerification.Unreadable ->
                    return ChosenSaveAdoption.Failure(
                        "chosen save failed verification (content could not be inspected)",
                    )
            }
        }

        try {
            val dir = checkNotNull(savePath.parent)
            Files.createDirectories(dir)
            val temp = dir.resolve("${savePath.fileName}.tmp")
            Files.write(temp, bytes)
            try {
                Files.move(temp, savePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, savePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            return ChosenSaveAdoption.Failure("could not write the chosen save: ${e.message}")
        }

        // Best-effort download confirmation (mirrors keep-server); local adoption already succeeded.
        when (val confirm = syncGateway.confirmDownload(origin, entry.saveId, deviceId)) {
            is SaveConfirmResult.Success -> Unit
            is SaveConfirmResult.Failure ->
                log.warning("chosen-save adoption: confirmDownload failed (non-fatal): ${confirm.error}")
        }
        return ChosenSaveAdoption.Success
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
        val coreBuildRevision = core.releaseTag.ifBlank { core.commitSha }

        // Stable per-ROM save identity via the shared [SavePathPolicy] (mirrors Android's
        // files/saves layout under the desktop data root — always under the data root, which the
        // player validates).
        val origin = settingsAdapter.currentProfile().origin
        val username = sessionStorage.coherentRecord(origin)?.username.orEmpty()
        val serverKey = SavePathPolicy.sanitizeSegment(origin.ifBlank { NO_ORIGIN_KEY })
        val userKey = SavePathPolicy.sanitizeSegment(username.ifBlank { ANONYMOUS_USER_KEY })
        val savePath = Path.of(
            SavePathPolicy.autosaveSramPath(
                filesDir = paths.dataDir.toFile(),
                serverKey = serverKey,
                userKey = userKey,
                romId = romId,
                romHash = staged.sha256,
            ),
        )
        runCatching { Files.createDirectories(checkNotNull(savePath.parent)) }
            .getOrElse { return PlayerLaunchResult.Failed("cannot create saves directory: ${it.message}") }

        // "Choose Save" (Android adoptChosenSave parity): an explicitly picked server save
        // replaces whatever previously sat at the autosave path — the player restores exactly
        // that save on launch. One-shot: consumed by this launch even if a later step fails
        // (the user re-picks to retry). Fail-closed: an adoption failure aborts the launch.
        val saveScope = SaveReplicaScope(
            serverKey = serverKey,
            userKey = userKey,
            romId = romId,
            romHash = staged.sha256,
            slot = SavePathPolicy.AUTOSAVE_SLOT,
        )
        val chosen = chosenSaves.remove(romId)
        if (chosen != null) {
            when (val adopted = adoptChosenSave(chosen, savePath)) {
                is ChosenSaveAdoption.Success -> {
                    val bytes = try {
                        Files.readAllBytes(savePath)
                    } catch (e: IOException) {
                        return PlayerLaunchResult.Failed(
                            "could not record the chosen save: ${e.message}",
                        )
                    }
                    val existingReplica = saveStateStore.findByScope(saveScope)
                    val now = System.currentTimeMillis()
                    val replica = (existingReplica ?: SaveReplicaRecord(
                        serverKey = saveScope.serverKey,
                        userKey = saveScope.userKey,
                        romId = saveScope.romId,
                        romHash = saveScope.romHash,
                        slot = saveScope.slot,
                        coreId = core.coreId,
                        coreBuildRevision = coreBuildRevision,
                    )).copy(
                        coreId = core.coreId,
                        coreBuildRevision = coreBuildRevision,
                        localHash = sha256Hex(bytes),
                        localSizeBytes = bytes.size.toLong(),
                        localWrittenAtEpochMs = now,
                        rommSaveId = chosen.saveId,
                        serverHash = chosen.contentHash,
                        syncStatus = SaveSyncStatus.SYNCED,
                        lastError = null,
                    )
                    saveStateStore.upsert(replica).getOrElse {
                        return PlayerLaunchResult.Failed("could not record the chosen save: ${it.message}")
                    }
                    saveSyncStatusPresenter().refresh(romId)
                }
                is ChosenSaveAdoption.Failure -> return PlayerLaunchResult.Failed(adopted.reason)
            }
        } else if (saveSyncSessionReader.current() != null) {
            val existingReplica = saveStateStore.findByScope(saveScope)
            // Desktop learns the core-produced SRAM size from prior checkpoints. Treat that
            // durable local size as the trusted gate when an explicit expected size has not yet
            // been persisted, so later server downloads can be validated before adoption.
            val expectedSaveSize =
                existingReplica?.expectedSramSizeBytes ?: existingReplica?.localSizeBytes
            val syncOutcome = try {
                preLaunchSaveSynchronizer.syncBeforeLaunch(
                    SaveSyncRequest(
                        romId = romId,
                        romHash = staged.sha256,
                        coreId = core.coreId,
                        coreBuildRevision = coreBuildRevision,
                        expectedSramSizeBytes = expectedSaveSize,
                        fileName = detail.fileName,
                    ),
                )
            } catch (e: Exception) {
                return PlayerLaunchResult.Failed("Save sync error: ${e.message ?: "unknown"}")
            }
            runCatching { saveSyncStatusPresenter().refresh(romId) }
                .onFailure { log.warning("pre-launch save-status refresh failed for ROM $romId: $it") }
            when (syncOutcome) {
                is SaveSyncOutcome.NoOpSynced,
                is SaveSyncOutcome.Downloaded,
                is SaveSyncOutcome.UploadQueued,
                is SaveSyncOutcome.PlayOfflineLocal -> Unit

                is SaveSyncOutcome.ConflictRequiresResolution ->
                    return PlayerLaunchResult.Failed("Save conflict needs resolution before launch.")

                is SaveSyncOutcome.Quarantined ->
                    return PlayerLaunchResult.Failed(
                        "Server save was quarantined (${syncOutcome.reason}); review it before launching.",
                    )

                is SaveSyncOutcome.AwaitingCoreValidation ->
                    Unit // The player validates the staged candidate against the loaded core's SRAM.

                is SaveSyncOutcome.Failure -> {
                    if (syncOutcome.error == RommApiError.AUTH_EXPIRED) {
                        return PlayerLaunchResult.Failed("Session expired; log in again before launching.")
                    }
                    return PlayerLaunchResult.Failed("Save sync failed: ${syncOutcome.error.name}.")
                }
            }
        }

        val expectedSaveSize = if (chosen == null) {
            saveStateStore.findByScope(
                SaveReplicaScope(serverKey, userKey, romId, staged.sha256, SavePathPolicy.AUTOSAVE_SLOT),
            )?.let { it.expectedSramSizeBytes ?: it.localSizeBytes }
        } else {
            // Preserve the existing explicit picker behavior: it validates the server hash, but
            // has no exact byte-size field to compare against an older replica before launch.
            null
        }
        val coresDir = paths.dataDir.resolve("cores")
        val params = PlayerLaunchParams(
            coreId = core.coreId,
            // The player validates request.coreBuildRevision against the derived
            // ROMM_PLAYER_ALLOWED_CORES value, so the manifest's releaseTag (falling back to
            // commitSha) is the authoritative revision pin.
            coreBuildRevision = coreBuildRevision,
            corePath = resolveCoreLibraryPath(coresDir, core.coreId),
            contentPath = staged.path,
            contentHash = staged.sha256,
            systemDir = paths.firmwareDir(),
            savePath = savePath,
            expectedSaveSize = expectedSaveSize,
            // Persisted Video Options state (JsonSettingsStore via the settings
            // adapter): the player applies these at launch so a user's
            // scanlines / integer-scaling / sharp-filter choices survive relaunch.
            video = VideoSettings(
                fullscreen = displayPolicy.fullscreen,
                integerScaling = settingsAdapter.integerScalingEnabled(),
                scanlines = settingsAdapter.scanlinesEnabled(),
                sharpFilter = settingsAdapter.sharpFilterEnabled(),
            ),
            // Stored controller overrides (ingested from the previous session's sidecar, §11.9):
            // null when nothing is stored — the player then keeps its built-in defaults.
            controllerBindings = loadLaunchControllerBindings(core.coreId),
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
                    coreBuildRevision = coreBuildRevision,
                )
                watchPlayerExit(result.launch, sessionId)
                // Parity with Android (MainActivity → GameLaunchRecorder.recordLaunch): mark the
                // ROM played as soon as the session starts. Non-blocking (background thread) and
                // failure-swallowing — it must never break the launch flow.
                gameLaunchRecorder.recordLaunch(romId)
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
                        invalidateSessionAndReOnboard()
                    }
                }
            }
        }
    }

    /** Called when onboarding completes; switches the whole app to MAIN. */
    fun onOnboardingCompleted() {
        enterMainMode()
    }

    /**
     * The "Log in" action from the game detail's auth-expired state (Android `routeToCredentials`
     * parity): routes the app back to onboarding at the SERVER step with the current origin
     * prefilled so the user re-enters credentials — nothing is auto-submitted. Also clears the
     * detail screen's launch state (Android sets `preLaunchState = null` on this action).
     */
    fun openLogin() {
        dismissGameLaunchState()
        invalidateSessionAndReOnboard()
    }

    /**
     * Explicit sign-out (parity with Android's `SettingsViewModel` `clearSessionFn` +
     * `onSessionInvalidated` pair): clears the durable client token for the current session,
     * clears the session record, and routes the app back to onboarding. Android has no
     * confirmation dialog for this action, so neither does desktop. Safe to call with no
     * active session (best-effort clears, still routes to onboarding).
     */
    fun logout() {
        val record = sessionStorage.coherentRecord(settingsAdapter.currentProfile().origin)
        if (record != null) {
            clientTokenStorage.clearToken(record.origin, record.username.orEmpty())
        }
        settingsAdapter.clearSession()
        invalidateSessionAndReOnboard()
    }

    /**
     * Routes the app back to onboarding after a session has been invalidated (explicit logout
     * or a server-origin change), mirroring Android's `enterOnboarding(startStep =
     * OnboardingStep.SERVER)` (Phase 5a, spec §5.3): the memoized onboarding presenter is
     * dropped so the next entry rebuilds it with the CURRENT profile origin prefilled at the
     * SERVER step, and the app leaves MAIN mode.
     */
    private fun invalidateSessionAndReOnboard() {
        onboardingInitialStep = OnboardingStep.SERVER
        onboardingPresenterInstance = null
        appMode = AppMode.ONBOARDING
    }

    // ------------------------------------------------------------------ navigation

    /** Main-mode back moves up one view; Home remains the root and never exits the app. */
    fun onBack() {
        if (appMode != AppMode.MAIN) return
        val previousScreen = currentScreen
        // Leaving the detail screen also drops any pending "Choose Save" selection — a save
        // picked for one ROM must never leak into a launch of another.
        if (currentScreen == Screen.GAME_DETAIL) {
            detailPresenters.clear()
            chosenSaves.clear()
        }
        currentScreen = when (currentScreen) {
            Screen.HOME -> Screen.HOME
            Screen.GAME_DETAIL -> gameDetailParent
            Screen.PLATFORM_DETAIL -> platformDetailParent
            Screen.COLLECTION_DETAIL -> collectionDetailParent
            else -> currentScreen.parent()
        }
        refreshContinuePlayingOnHomeEntry(previousScreen)
    }

    fun navigate(screen: Screen) {
        val previousScreen = currentScreen
        currentScreen = screen
        refreshContinuePlayingOnHomeEntry(previousScreen)
    }

    private fun refreshContinuePlayingOnHomeEntry(previousScreen: Screen) {
        if (previousScreen != Screen.HOME && currentScreen == Screen.HOME) {
            refreshContinuePlayingIfInitialized()
        }
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

    /** Opens the controller console list (Settings → "Controller Settings", E2). */
    fun openControllerSettings() {
        currentScreen = Screen.CONTROLLER_LIST
    }

    /** Opens the per-core binding configuration screen for [coreId] (E2). */
    fun openControllerConfig(coreId: String) {
        selectedControllerCoreId = coreId
        currentScreen = Screen.CONTROLLER_CONFIG
    }

    // ------------------------------------------------------------------ presenters (lazy per screen)

    fun settingsPresenter(): SettingsPresenter = settingsPresenterLazy

    private val homePresenterLazy = lazy {
        HomePresenter(
            scope = scope,
            repository = network.libraryRepository,
            hideUnsupportedSystems = { settingsAdapter.hideUnsupportedSystems() },
        )
    }

    fun homePresenter(): HomePresenter = homePresenterLazy.value

    private fun refreshContinuePlayingIfInitialized() {
        if (homePresenterLazy.isInitialized()) {
            homePresenterLazy.value.retryContinuePlaying()
        }
    }

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

    /**
     * Read-only save-sync status for the game detail screen (first piece of the Linux saves UI):
     * whether the current ROM's autosave is synced / pending upload / in conflict, etc. One
     * app-wide instance — the screen calls [SaveSyncStatusPresenter.refresh] on show and after
     * every player-session end, so no per-ROM memoization is needed (its state always reflects
     * the most recent refresh). Also backs the "View quarantine" drill-down: [filesDir] is the
     * same data root [FileSaveContentGateway] writes under, so the presenter's quarantine-dir
     * scan resolves exactly where preserved copies live.
     */
    private val saveSyncStatusPresenterLazy by lazy {
        SaveSyncStatusPresenter(
            store = saveStateStore,
            sessionKeysProvider = { currentSaveSessionKeys() },
            filesDir = paths.dataDir.toFile(),
        )
    }

    fun saveSyncStatusPresenter(): SaveSyncStatusPresenter = saveSyncStatusPresenterLazy

    /**
     * The save-scope keys for the current session — [SavePathPolicy.sanitizeSegment] applied to
     * origin + username, exactly as [launchPlayer] persists them (via
     * `SavePathPolicy.autosaveSramPath`) and [enqueuePostPlaySync] parses them back off disk.
     * Null when there is no coherent non-kiosk session: blank origin, a kiosk/anonymous record
     * ([SessionStorage.coherentRecord] returns null for a blank username), or an origin that does
     * not match the profile. Those sessions never enqueue save-sync operations, so their saves
     * render as NoSave in the UI.
     */
    internal fun currentSaveSessionKeys(): Pair<String, String>? {
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return null
        val record = sessionStorage.coherentRecord(origin) ?: return null
        // Defensive: coherentRecord already requires a non-blank username, so kiosk records never
        // reach here — but the UI must not query with an anonymous key anyway (no replicas exist
        // for those; enqueuePostPlaySync drops them).
        val username = record.username ?: return null
        if (record.kioskMode || username.isBlank()) return null
        return SavePathPolicy.sanitizeSegment(origin) to SavePathPolicy.sanitizeSegment(username)
    }

    // ------------------------------------------------------------------ save-sync actions (saves UI)

    /**
     * "Sync now" on the save-status line: force an immediate drain of the durable save-sync queue
     * (the actual work runs on the scheduler's worker thread). Returns whether a drain was started
     * (false when one is already running or the scheduler is shut down — still a no-op-safe call).
     */
    fun requestSaveSync(): Boolean = scheduler.requestDrain("user-requested")

    /**
     * Explicitly resolves [romId]'s CONFLICT autosave with the user's three-way [choice]:
     * [SaveConflictChoice.KEEP_LOCAL] = the local file wins (uploaded over the server, losing
     * server copy backed up); [SaveConflictChoice.KEEP_SERVER] = the server copy wins (downloaded
     * and adopted, losing local copy backed up); [SaveConflictChoice.QUARANTINE] = the server copy
     * is preserved in the quarantine dir and the replica settles QUARANTINED (nothing adopted or
     * uploaded — the escape hatch for incompatible-provenance conflicts). Both copies are
     * preserved until this choice is applied — see [SaveConflictResolver]. The detail screen
     * refreshes its status presenter after calling this. Safe to call from any thread; performs
     * network I/O (callers dispatch off the UI thread).
     */
    fun resolveSaveConflict(romId: Long, choice: SaveConflictChoice): SaveConflictResolutionResult {
        val (serverKey, userKey) = currentSaveSessionKeys()
            ?: return SaveConflictResolutionResult.Failure("no active session — log in again to resolve")
        // Same newest-generation autosave lookup the status presenter uses (a re-uploaded ROM
        // leaves one replica per content hash; the newest local write is the current one).
        val replica = SaveSyncStatus.entries
            .flatMap { status -> saveStateStore.findByStatus(serverKey, userKey, status) }
            .filter { it.romId == romId && it.slot == SavePathPolicy.AUTOSAVE_SLOT }
            .maxByOrNull { it.localWrittenAtEpochMs ?: Long.MIN_VALUE }
            ?: return SaveConflictResolutionResult.Failure("no save recorded for this game")
        return saveConflictResolver.resolve(replica, choice)
    }

    /**
     * Two-way convenience overload for the existing detail-screen buttons (the third QUARANTINE
     * button is a follow-up sub-unit — F2). [keepLocal] true = KEEP_LOCAL, false = KEEP_SERVER.
     */
    fun resolveSaveConflict(romId: Long, keepLocal: Boolean): SaveConflictResolutionResult =
        resolveSaveConflict(romId, if (keepLocal) SaveConflictChoice.KEEP_LOCAL else SaveConflictChoice.KEEP_SERVER)

    // ------------------------------------------------------------------ save picker ("Choose Save")

    /**
     * One-shot pre-launch save selection (the "Choose Save" flow — Android `adoptChosenSave`
     * parity): the entry picked in the save picker, keyed by ROM id. Consumed (removed) by the
     * NEXT [launchPlayer] for that ROM, which adopts the chosen server save into the launch's
     * autosave path so the player restores exactly that save instead of whatever was there.
     * Cleared when navigation leaves GAME_DETAIL. Synchronized: written on the Compose main
     * thread, read/removed from [launchPlayer]'s worker thread.
     */
    private val chosenSaves: MutableMap<Long, SavePickerEntryUiModel> =
        Collections.synchronizedMap(mutableMapOf())

    /** Records the user's save-picker choice for [romId]'s upcoming launch (one-shot). */
    fun chooseSaveForLaunch(romId: Long, entry: SavePickerEntryUiModel) {
        chosenSaves[romId] = entry
    }

    /** The pending save-picker choice for [romId], or null when none is recorded. */
    internal fun chosenSaveForLaunch(romId: Long): SavePickerEntryUiModel? = chosenSaves[romId]

    /**
     * Lists every server save for [romId] (all cores/devices — SRAM saves are cross-core
     * compatible for the same platform, so no core filter is applied; Android
     * `listSavesForRom` parity). The game-detail "Choose Save" flow calls this before rendering
     * its entries. Blocking network I/O — callers dispatch off the UI thread. Kiosk sessions
     * expose no server saves (empty success, matching Android).
     */
    fun listSavesForRom(romId: Long): SaveListResult {
        val origin = settingsAdapter.currentProfile().origin
        if (origin.isBlank()) return SaveListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val record = sessionStorage.coherentRecord(origin)
            ?: return SaveListResult.Failure(RommApiError.AUTH_EXPIRED)
        if (record.kioskMode) return SaveListResult.Success(emptyList())
        val username = record.username
            ?: return SaveListResult.Failure(RommApiError.AUTH_EXPIRED)
        // Optional for the API, but supplied when known so the server can attach this device's
        // sync status per save (Android parity). A registration failure degrades to a
        // device-agnostic list rather than failing the picker.
        val deviceId = runCatching { saveSyncDeviceIdentityLoader.load(origin, username) }
            .getOrNull()?.rommDeviceId
        return syncGateway.listSaves(origin, romId, deviceId)
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

    /** Whether [platformSlug] is a BIOS-required console (SEGA CD / PlayStation). */
    fun requiresBios(platformSlug: String): Boolean = platformSlug in BIOS_PLATFORM_SLUGS

    /**
     * Checks whether a ROM's required BIOS is available (Android `checkRequiredBiosAvailability`
     * parity) for the game detail screen's inline display. Non-BIOS platforms are always [Ready].
     * Maps the catalog fetch to [RequiredBiosState] with Android's semantics: a staged/selected
     * file → [Ready]; an empty catalog → [Missing]; files present but none selected →
     * [UnverifiedAvailable]; auth expiry / fetch failure → [Error]. Blocking network I/O — callers
     * dispatch off the UI thread.
     */
    suspend fun checkRequiredBiosAvailability(platformSlug: String): RequiredBiosState {
        if (platformSlug !in BIOS_PLATFORM_SLUGS) return RequiredBiosState.Ready
        return when (val catalog = biosConfigurationProvider(platformSlug).fetchCatalog()) {
            is BiosConfigurationCatalog.Success -> when {
                catalog.options.isEmpty() -> RequiredBiosState.Missing
                catalog.selectedFirmwareId != null &&
                    catalog.options.any { it.firmware.firmwareId == catalog.selectedFirmwareId } ->
                    RequiredBiosState.Ready
                biosConfigurationProvider(platformSlug).hasAutoSelectableFirmware(catalog) ->
                    RequiredBiosState.Ready
                else -> RequiredBiosState.UnverifiedAvailable
            }
            BiosConfigurationCatalog.AuthExpired ->
                RequiredBiosState.Error("Session expired. Log in again to check for BIOS files.")
            is BiosConfigurationCatalog.Error ->
                RequiredBiosState.Error(
                    "Couldn't check BIOS files (${catalog.message.lowercase().replace('_', ' ')}).",
                )
        }
    }

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

    /**
     * The step a (re-)entered onboarding starts at. First launch is [OnboardingStep.WELCOME];
     * after a session invalidation (logout / server replacement) it is [OnboardingStep.SERVER]
     * with the current origin prefilled, mirroring Android's
     * `enterOnboarding(startStep = OnboardingStep.SERVER)`.
     */
    private var onboardingInitialStep: OnboardingStep = OnboardingStep.WELCOME

    /**
     * The onboarding presenter for the current onboarding entry. Unlike the other per-screen
     * presenters this one is NOT memoized for the coordinator's lifetime: Android builds a
     * FRESH OnboardingViewModel on every onboarding entry (so `initialServerInput` picks up the
     * newly selected origin), and desktop must do the same — [invalidateSessionAndReOnboard]
     * drops the instance so the next [onboardingPresenter] call rebuilds it with the current
     * profile origin. Volatile: [settingsPresenterLazy]'s `onSessionInvalidated` fires from the
     * settings presenter's scope (Dispatchers.Default) while the UI thread reads it via
     * `remember(coordinator)`.
     */
    @Volatile
    private var onboardingPresenterInstance: OnboardingPresenter? = null

    fun onboardingPresenter(): OnboardingPresenter {
        onboardingPresenterInstance?.let { return it }
        val presenter = OnboardingPresenter(
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
                initialStep = onboardingInitialStep,
                initialUsername = "",
            )
        onboardingPresenterInstance = presenter
        return presenter
    }

    /**
     * Raw (NOT coherence-checked) session record — parity with Android's `sessionStore.current()`
     * (the seam's [SessionStorage.coherentRecord] additionally requires the record's origin to
     * match the profile origin). The settings presenter needs the raw record:
     * [SettingsPresenter.onSave] persists the NEW origin BEFORE checking for a session to
     * invalidate, so a coherence-checked read would already see the new origin and miss the
     * old-origin record — the server-replacement invalidation would never fire.
     */
    private fun rawSessionRecord(): SessionStorage.Record? = sessionRecordStore.current()?.let {
        SessionStorage.Record(
            origin = it.origin,
            username = it.username,
            verifiedAtEpochMillis = it.verifiedAtEpochMillis,
            kioskMode = it.kioskMode,
        )
    }

    private val settingsPresenterLazy by lazy {
        SettingsPresenter(
                scope = scope,
                getCurrentProfile = { settingsAdapter.currentProfile() },
                setOriginFn = settingsAdapter::setOrigin,
                clearOverrideFn = settingsAdapter::clearOverride,
                getSessionRecord = { rawSessionRecord() },
                clearSessionFn = {
                    // Mirror Android's SettingsViewModel.Factory.clearSessionFn: drop the durable
                    // client token for the session being cleared BEFORE the session record, so a
                    // server-origin change (or restore-default) invalidates the old origin's token.
                    val session = rawSessionRecord()
                    session?.let { s -> clientTokenStorage.clearToken(s.origin, s.username.orEmpty()) }
                    settingsAdapter.clearSession()
                },
                checkHeartbeatFn = { origin -> network.authRepository.checkHeartbeat(origin) },
                loginFn = { origin, username, password ->
                    network.authRepository.login(origin, username, password)
                },
                onLoginSuccess = { currentScreen = Screen.HOME },
                onSessionInvalidated = { invalidateSessionAndReOnboard() },
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
    PLATFORM_DETAIL, COLLECTION_DETAIL, GAME_DETAIL, BIOS_CONFIGURATION, LICENSE,
    CONTROLLER_LIST, CONTROLLER_CONFIG;

    /** The screen Back returns to (root returns nothing; GAME_DETAIL uses its remembered parent). */
    fun parent(): Screen = when (this) {
        HOME -> HOME
        PLATFORMS, COLLECTIONS, SEARCH, SETTINGS, LICENSE -> HOME
        PLATFORM_DETAIL -> PLATFORMS
        COLLECTION_DETAIL -> COLLECTIONS
        BIOS_CONFIGURATION -> SETTINGS
        CONTROLLER_CONFIG -> CONTROLLER_LIST
        CONTROLLER_LIST -> SETTINGS
        GAME_DETAIL, ONBOARDING -> HOME
    }
}

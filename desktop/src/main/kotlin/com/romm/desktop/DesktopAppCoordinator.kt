package com.romm.desktop

import com.romm.androidtv.auth.ClientTokenStorage
import com.romm.androidtv.auth.SessionStorage
import com.romm.androidtv.library.BiosConfigurationPresenter
import com.romm.androidtv.library.BiosConfigurationProvider
import com.romm.androidtv.library.HomePresenter
import com.romm.androidtv.library.RomDetailPresenter
import com.romm.androidtv.library.RomGridPresenter
import com.romm.androidtv.library.RomQuery
import com.romm.androidtv.library.RommTheme
import com.romm.androidtv.library.SearchPresenter
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
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.databaseDir
import com.romm.androidtv.storage.firmwareDir
import com.romm.androidtv.storage.settingsFile
import com.romm.desktop.library.DesktopBiosConfigurationProvider
import com.romm.desktop.network.DesktopNetworkModule
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.LaunchRecoveryDiagnostic
import com.romm.desktop.player.PlayerExitReport
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
import com.romm.desktop.ui.image.DesktopImageLoader
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.logging.Level
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
 *    [BackgroundSyncSchedulerImpl] (no-op drain until Phase 9 wires save sync),
 *    [FileLockAppInstanceLock].
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
 */
class DesktopAppCoordinator(
    val paths: AppPaths,
    val secretBackend: SecretBackend,
    val appVersion: String,
    val buildDefaultOrigin: String,
    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    // ------------------------------------------------------------------ storage

    val settingsStore: JsonSettingsStore = JsonSettingsStore(paths.settingsFile())

    val database: SqliteDatabase = SqliteDatabase.open(paths.databaseDir().resolve(DB_FILE_NAME))
        .getOrElse { throw IllegalStateException("Failed to open desktop database at ${paths.databaseDir()}", it) }

    private val sessionRecordStore = SqliteSessionRecordStore(database)
    private val saveStateStore = SqliteSaveStateStore(database)
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

    /** Single-instance lock (plans/LINUX_X64.md §10.4). Constructed here; acquired by Main. */
    val appInstanceLock: FileLockAppInstanceLock = FileLockAppInstanceLock(null, paths.stateDir)

    /** Background save-sync scheduler. Phase 6 has no save sync yet, so [drain] is a no-op. */
    val scheduler: BackgroundSyncSchedulerImpl by lazy {
        // Phase 9 wires the real drain (PendingOperationStore + network). Documented here so the
        // wiring point is unambiguous.
        BackgroundSyncSchedulerImpl(drain = { /* no-op until Phase 9 wires save-sync drain */ }, stateStore = schedulerStateStore)
    }

    // ------------------------------------------------------------------ player supervision (Phase 8 Wave 2)

    /**
     * Launch journal supervisor for the `rommulus-player` process (plans/LINUX_X64.md §12.5).
     *
     * Integration points (the player binary itself lands in Phase 8 Wave 3+):
     * - [scanPlayerJournals] — startup crash-recovery scan; called once by [Main] before the
     *   first composition.
     * - [onPlayerProcessExited] — post-exit reconciliation hook; call it with the exit code
     *   when a spawned player process terminates.
     * - [playerSupervisor].prepareLaunch — the launch screen (Phase 8 Wave 3+) calls this to
     *   commit request + journal atomically and spawn the player.
     */
    val playerSupervisor: LaunchJournalSupervisor by lazy { LaunchJournalSupervisor.forPaths(paths) }

    /** Startup scan over incomplete launch journals (§12.5). Idempotent; safe to call more than once. */
    fun scanPlayerJournals(): List<LaunchRecoveryDiagnostic> = playerSupervisor.scanIncompleteJournals()

    /** Post-exit reconciliation hook for a spawned player process (pass the process exit code). */
    fun onPlayerProcessExited(sessionId: String, exitCode: Int): PlayerExitReport =
        playerSupervisor.onPlayerExitBySessionId(sessionId, exitCode)

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

    fun romDetailPresenter(romId: Long): RomDetailPresenter = RomDetailPresenter(
        scope = scope,
        repository = network.libraryRepository,
        romId = romId,
    )

    fun biosConfigurationPresenter(platformSlug: String): BiosConfigurationPresenter =
        BiosConfigurationPresenter(scope, biosConfigurationProvider(platformSlug))

    fun biosConfigurationProvider(platformSlug: String): BiosConfigurationProvider =
        DesktopBiosConfigurationProvider(
            client = network.okHttpClient,
            originProvider = { settingsAdapter.currentProfile().origin },
            firmwareDir = paths.firmwareDir(),
            platformSlug = platformSlug,
        )

    // ------------------------------------------------------------------ internals

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

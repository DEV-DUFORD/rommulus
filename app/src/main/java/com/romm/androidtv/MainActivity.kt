// Pre-existing: Fragment version is below 1.3.0 but ActivityResult API works at runtime.
@file:Suppress("InvalidFragmentVersionForActivityResult")

package com.romm.androidtv

import android.hardware.input.InputManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.os.Bundle
import java.util.Locale
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.onboarding.OnboardingEffect
import com.romm.androidtv.onboarding.OnboardingRoutingPolicy
import com.romm.androidtv.onboarding.OnboardingStep
import com.romm.androidtv.onboarding.OnboardingViewModel
import com.romm.androidtv.onboarding.OnboardingViewModelFactory
import com.romm.androidtv.onboarding.ui.OnboardingScreen
import com.romm.androidtv.romm.ClientTokenStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.controller.capture.ControllerBindingCaptureCoordinator
import com.romm.androidtv.controller.config.ControllerConfigDatabase
import com.romm.androidtv.controller.config.ControllerConfigRepository
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.RoomControllerConfigRepository
import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.router.ControllerEventRouter
import com.romm.androidtv.controller.ui.ControllerConfigScreen
import com.romm.androidtv.controller.ui.ControllerConsoleListScreen
import com.romm.androidtv.controller.ui.ControllerSettingsViewModel
import com.romm.androidtv.emulation.model.CandidateExtras
import com.romm.androidtv.emulation.model.CandidateSaveMetadata
import com.romm.androidtv.emulation.model.DescriptorState
import com.romm.androidtv.emulation.model.EmulationResult
import com.romm.androidtv.emulation.model.EmulationResultHandler
import com.romm.androidtv.emulation.model.LaunchSessionJournal
import com.romm.androidtv.emulation.model.SaveLaunchOrchestrator
import com.romm.androidtv.emulation.model.SessionDescriptorPatch
import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.gamepad.GamepadInjectionBridge
import com.romm.androidtv.gamepad.GamepadInjectionDiagnostics
import com.romm.androidtv.model.*
import com.romm.androidtv.network.*
import com.romm.androidtv.romm.DeviceRepositoryImpl
import com.romm.androidtv.romm.RomRepositoryImpl
import com.romm.androidtv.romm.StagingOutcome
import com.romm.androidtv.romm.StagingOutcomeMessage
import com.romm.androidtv.romm.save.ConflictChoice
import com.romm.androidtv.romm.save.FileSaveContentStore
import com.romm.androidtv.romm.save.ResolveConflictRequest
import com.romm.androidtv.romm.save.SaveSyncCoordinator
import com.romm.androidtv.romm.save.SaveSyncCoordinatorImpl
import com.romm.androidtv.romm.save.findSaveReplicaByScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reusable modifier: fires [onSelect] on DPAD_CENTER / Enter / NumpadEnter KeyDown,
 * with repeat-event suppression. Returns true to prevent downstream handlers
 * (avoids double-activation with Material3 semantic onClick).
 */
fun Modifier.tvSelect(onSelect: () -> Unit): Modifier = onKeyEvent { event ->
    if (event.type == KeyEventType.KeyDown && event.nativeKeyEvent.repeatCount > 0) {
        // Suppress repeats from long-press
        true
    } else if (event.type == KeyEventType.KeyDown &&
        (event.key == Key.Enter ||
         event.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER)) {
        onSelect()
        true
    } else {
        false
    }
}

/**
 * TV-launchable Activity with deterministic D-pad focus.
 *
 * Startup flow (Phase 5a):
 * 1. A root AppMode gate runs synchronously before the first composition. When a coherent
 *    session record, a matching durable client token, and the active profile origin all agree,
 *    the app boots straight into Native Library (AppMode.MAIN). Otherwise it boots into the
 *    first-run onboarding flow (AppMode.ONBOARDING).
 * 2. Onboarding completes when a durable token is created and verified; the app then switches
 *    to AppMode.MAIN and renders Native Library (currentScreen = NATIVE_HOME). The user can
 *    never navigate Back into onboarding afterwards.
 * 3. Library ViewModels (HomeViewModel, etc.) are constructed ONLY inside the MAIN branch, so
 *    onboarding never issues unauthorized library requests.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class MainActivity : ComponentActivity() {

    private enum class Screen {
        NATIVE_HOME, NATIVE_PLATFORMS, NATIVE_COLLECTIONS, NATIVE_SEARCH,
        NATIVE_SETTINGS, NATIVE_PLATFORM_DETAIL, NATIVE_COLLECTION_DETAIL, NATIVE_GAME_DETAIL,
        NATIVE_CONFLICT, NATIVE_QUARANTINE, NATIVE_SAVE_PICKER, NATIVE_VERSION_PICKER, NATIVE_BIOS_CONFIGURATION,
        NATIVE_CONTROLLER_LIST, NATIVE_CONTROLLER_CONFIG, NATIVE_SCREENSHOT_VIEWER
    }

    private var currentScreen by mutableStateOf(Screen.NATIVE_HOME)

    /**
     * Root launch mode (Phase 5a): ONBOARDING renders the first-run flow; MAIN renders the
     * native library. Selected SYNCHRONOUSLY in onCreate before setContent so onboarding never
     * flashes Home or constructs library ViewModels (spec sections 2.2, 5.1).
     */
    private var appMode by mutableStateOf(OnboardingRoutingPolicy.AppMode.MAIN)

    /**
     * Bumped each time the app (re)enters onboarding so a fresh [OnboardingViewModel] is
     * created per session (keyed by this value). A completed instance is never reused —
     * e.g. after Settings invalidates the session and routes back to onboarding.
     */
    private var onboardingSessionId by mutableStateOf(0)

    /** The currently-active onboarding ViewModel, used for Activity-level Back dispatch. */
    private var activeOnboardingViewModel: OnboardingViewModel? = null

    /**
     * Desired first step + username prefill for the NEXT onboarding session (Phase 5a).
     * Fresh installs default to WELCOME/""; Settings invalidation requests SERVER; a
     * definitively-invalid bearer token requests CREDENTIALS with the known username.
     */
    private var onboardingStartStep by mutableStateOf(OnboardingStep.WELCOME)
    private var onboardingStartUsername by mutableStateOf("")

    // Selected core for the Phase 6 controller-configuration flow (CONTROLLER_SETTINGS.md §7).
    private var selectedControllerCoreId by mutableStateOf<String?>(null)

    // Selection state for the Phase 2 detail screens (UI_REFACTOR.md section 7). `gameDetailParent`
    // remembers which screen opened the game detail screen, so Back returns to the grid/shelf that
    // was actually showing (Home, a platform detail grid, or a collection detail grid) rather than
    // always Home.
    private var selectedPlatformId by mutableStateOf<Long?>(null)
    private var selectedCollectionId by mutableStateOf<Long?>(null)
    private var selectedRomId by mutableStateOf<Long?>(null)
    private var gameDetailParent by mutableStateOf(Screen.NATIVE_HOME)
    // Selection state for the full-screen screenshot viewer, opened from the game detail
    // screen's screenshot shelf. Always returns to NATIVE_GAME_DETAIL on Back.
    private var selectedScreenshotUrls by mutableStateOf<List<String>>(emptyList())
    private var selectedScreenshotIndex by mutableStateOf(0)
    private var requiredBiosState by mutableStateOf<com.romm.androidtv.library.ui.RequiredBiosState>(
        com.romm.androidtv.library.ui.RequiredBiosState.Checking,
    )
    private enum class BiosSystem { SEGA_CD, PLAYSTATION }
    private var selectedBiosSystem by mutableStateOf(BiosSystem.SEGA_CD)

    /**
     * Bumped once per successfully-processed EmulationActivity result so the Native Library
     * home screen's Continue Playing section refreshes immediately after exiting a game,
     * instead of only on the next cold app start. Observed by a `LaunchedEffect` alongside
     * `HomeViewModel`'s creation (see `NATIVE_HOME` composable branch).
     */
    private var continuePlayingRefreshTick by mutableStateOf(0)

    /** Re-fetches any library view model that survived a pre-login navigation session. */
    private val libraryRefreshEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    // Pre-launch save sync overlay state (conflict/quarantine). Scoped to a single ROM/session;
    // survives recomposition because it lives on the Activity, not inside remember().
    private var preLaunchState by mutableStateOf<com.romm.androidtv.library.ui.SavePreLaunchState?>(null)

    // Native save-picker ("Choose Save") state. `savePickerStagedOutcome` holds the ROM already
    // staged while the list loads, so selecting an entry can adopt+launch without re-staging.
    private var savePickerState by mutableStateOf<com.romm.androidtv.library.ui.SavePickerState?>(null)
    private var savePickerStagedOutcome by mutableStateOf<com.romm.androidtv.romm.StagingOutcome.Success?>(null)

    // Native version-picker ("Choose Version") state — lists sibling roms (multi-disc/region/
    // revision variants of the same game) and lets the user switch the game detail screen to a
    // specific one, mirroring the save-picker's UX. Unlike the save picker, no staging happens
    // and there is no auto-launch: picking a version just re-scopes the detail screen to that
    // rom ID so the user presses Play there when ready.
    private var versionPickerState by mutableStateOf<com.romm.androidtv.library.ui.VersionPickerState?>(null)

    @Volatile
    private var authResult: AuthFlowResult? = null

    @Volatile
    private var verifiedUser: VerifiedUser? = null

    // OkHttp client — lazily initialized
    private val okHttpClient by lazy { RommOkHttpClient.build() }

    // Persisted server profile — falls back to the compiled-in BuildConfig origin
    // until a settings UI exists to override it (LIBRETRO_REFACTOR.md section 5).
    private val settingsRepository: SettingsRepository by lazy {
        SettingsRepository(
            getSharedPreferences(SettingsRepository.PREFS_NAME, MODE_PRIVATE),
            defaultOrigin = BuildConfig.ROMM_ORIGIN
        )
    }

    // Durable session record, independent of the WebView cookie jar.
    private val sessionStore: SessionStore by lazy {
        SessionStore(getSharedPreferences(SessionStore.PREFS_NAME, MODE_PRIVATE))
    }

    // Durable, encrypted bearer-client token store (origin + username scoped).
    private val clientTokenStore: com.romm.androidtv.romm.ClientTokenStore by lazy {
        com.romm.androidtv.romm.ClientTokenStore(this)
    }

    // Auth repository — owns login/session-verification/cookie-sync network calls so
    // MainActivity coordinates navigation rather than owning network internals.
    private val authRepository: AuthRepository by lazy {
        AuthRepository(okHttpClient, RommOkHttpClient.cookieSyncJar, sessionStore, clientTokenStore)
    }

    // Phase 3/4 native content pipeline — quota-limited, identity-keyed cache of
    // downloaded ROM/firmware content, and the repository that stages one ROM
    // for native launch through it (LIBRETRO_REFACTOR.md sections 6 and 10).
    // This all runs in the main process, never in :emulation (architectural
    // rule: "the emulation process does not make network requests").
    private val contentCache: ContentCache by lazy {
        ContentCache(filesDir.resolve("content_cache"), CacheDatabase(filesDir.resolve("content_cache/index.json")))
    }
    private val romRepository: RomRepositoryImpl by lazy {
        RomRepositoryImpl(
            okHttpClient,
            sessionStore,
            contentCache,
            verifySha1OnLaunch = { settingsRepository.verifySha1OnLaunch() },
        )
    }
    private val firmwareRepository by lazy {
        com.romm.androidtv.romm.FirmwareRepositoryImpl(okHttpClient, sessionStore, contentCache)
    }
    private val segaCdBiosManager by lazy {
        com.romm.androidtv.romm.SegaCdBiosManager(firmwareRepository, settingsRepository)
    }
    private val psxBiosManager by lazy {
        com.romm.androidtv.romm.PsxBiosManager(firmwareRepository, settingsRepository)
    }

    // Native browsing UI (UI_REFACTOR.md) — independent of romRepository, which is
    // scoped to single-ROM launch/staging, not list browsing.
    private val libraryRepository: com.romm.androidtv.library.LibraryRepository by lazy {
        com.romm.androidtv.library.LibraryRepositoryImpl(okHttpClient) { currentOrigin }
    }

    /** The currently configured RomM origin: persisted override, or the BuildConfig default. */
    private val currentOrigin: String
        get() = settingsRepository.currentProfile().origin

    // Controller event router — captures, maps, and produces StateFlow snapshots
    private val controllerRouter: ControllerEventRouter by lazy {
        ControllerEventRouter()
    }

    /**
     * Phase 4 capture coordinator. Intercepts raw input before normal routing
     * only while a capture is active; returns null when Idle so the existing
     * per-screen routing below is completely unaffected. Registered as an extra
     * InputDeviceListener so physical disconnects cancel an in-progress capture.
     */
    private val captureCoordinator: ControllerBindingCaptureCoordinator by lazy {
        ControllerBindingCaptureCoordinator(lifecycleScope)
    }

    // Reads the persisted per-core controller config overrides (Phase 3). Uses the shared
    // ControllerConfigDatabase (multi-instance invalidation) so this main process observes
    // the same rows the :emulation process writes.
    private val controllerConfigRepository: ControllerConfigRepository by lazy {
        RoomControllerConfigRepository.create(ControllerConfigDatabase.database(applicationContext))
    }

    // Gamepad injection diagnostics — shared observable state
    private val gamepadDiagnostics: GamepadInjectionDiagnostics by lazy {
        GamepadInjectionDiagnostics()
    }

    // Gamepad injection bridge — injects document-start script and pushes state
    private val gamepadBridge: GamepadInjectionBridge by lazy {
        GamepadInjectionBridge(gamepadDiagnostics)
    }

    // Phase B: SaveSyncCoordinator for pre-launch negotiation and post-play finalization.
    // Exposed as the interface type; implementation details (DAOs, stores) remain internal.
    // Uses a cookie-free bearer-authenticated client backed by encrypted ClientTokenStore,
    // matching RommWorkerFactory's durable credential rule.
    private val saveSyncCoordinator: SaveSyncCoordinator by lazy {
        val db = com.romm.androidtv.RommApplication.database(this)
        val tokenStore = clientTokenStore
        val bearerClient = buildBearerAuthClient(tokenStore)
        val devicePrefs = getSharedPreferences(com.romm.androidtv.romm.DeviceIdentityStore.PREFS_NAME, MODE_PRIVATE)
        SaveSyncCoordinatorImpl(
            client = bearerClient,
            sessionStore = sessionStore,
            deviceRepository = DeviceRepositoryImpl(bearerClient, com.romm.androidtv.romm.DeviceIdentityStore(devicePrefs)),
            saveReplicaDao = db.saveReplicaDao(),
            pendingOperationDao = db.pendingOperationDao(),
            saveContentStore = FileSaveContentStore(filesDir),
            onOperationQueued = { com.romm.androidtv.sync.SaveUploadEnqueueHelper.enqueue(applicationContext) },
        )
    }

    /**
     * Builds a cookie-free, bearer-authenticated OkHttp client backed by [ClientTokenStore].
     * Used exclusively for foreground device registration and save-sync write operations.
     * Never imports WebView cookies — follows the durable credential rule from RommWorkerFactory.
     */
    private fun buildBearerAuthClient(tokenStore: com.romm.androidtv.romm.ClientTokenStore): okhttp3.OkHttpClient {
        // Phase 5a: origin-scoped bearer auth (spec section 5.2, 7.4). The interceptor attaches
        // the stored client token ONLY to same-origin native API requests, so third-party cover
        // URLs never receive the credential. Cookie import/verification is compatibility
        // maintenance, NOT the native auth gate.
        //
        // Non-throwing by design: with empty/fresh app data the profile origin is blank. We must
        // NOT crash the UI or construct a bearer client for an invalid origin — fall back to a
        // plain token-less shared client instead (the "never attach a token to a non-origin"
        // invariant holds because there is no interceptor at all). A valid bearer client is only
        // built once a canonical origin exists (i.e. in MAIN mode after onboarding).
        val origin = RommServerAddress.parseAndNormalize(currentOrigin) as? ServerAddressResult.Valid
            ?: return RommOkHttpClient.build()
        return RommOkHttpClient.nativeClient(
            origin = origin,
            tokenProvider = {
                sessionStore.current()?.let { s ->
                    tokenStore.getToken(s.origin, s.username ?: "")?.raw
                }
            },
        )
    }

    // Phase B: Orchestrates pre-launch save-sync preparation. Eliminates duplicated
    // sync-outcome handling between debug and native-library flows.
    private val saveLaunchOrchestrator: SaveLaunchOrchestrator by lazy {
        SaveLaunchOrchestrator(saveSyncCoordinator)
    }

    // Phase B: Handles EmulationActivity results and journal-based recovery with
    // per-session serialization and thread-safe candidate metadata caching.
    private val emulationResultHandler: EmulationResultHandler by lazy {
        EmulationResultHandler(
            coordinator = saveSyncCoordinator,
            sessionStore = sessionStore,
            lifecycleScope = lifecycleScope,
            filesDir = filesDir,
        )
    }

    // Phase B: ActivityResultLauncher for EmulationActivity result.
    private lateinit var emulationLauncher: androidx.activity.result.ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "RomMMainActivity"
        /** Stable tag for all auth-loop boundary diagnostics (logcat -s RommAuthDx). */
        private const val DIAG_TAG = "RommAuthDx"
    }

    /** Maps a sidebar destination to the [Screen] it opens; SETTINGS has no native screen yet (out of scope, UI_REFACTOR.md). */
    private fun com.romm.androidtv.library.ui.NavDestination.toScreen(): Screen = when (this) {
        com.romm.androidtv.library.ui.NavDestination.HOME -> Screen.NATIVE_HOME
        com.romm.androidtv.library.ui.NavDestination.PLATFORMS -> Screen.NATIVE_PLATFORMS
        com.romm.androidtv.library.ui.NavDestination.COLLECTIONS -> Screen.NATIVE_COLLECTIONS
        com.romm.androidtv.library.ui.NavDestination.SEARCH -> Screen.NATIVE_SEARCH
        com.romm.androidtv.library.ui.NavDestination.SETTINGS -> Screen.NATIVE_SETTINGS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize controller router and register device listener
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(controllerRouter, null)
        // Phase 4: also listen for disconnects so an in-progress capture cancels
        // when the assigned controller is physically removed.
        inputManager.registerInputDeviceListener(captureCoordinator, null)
        controllerRouter.attachLifecycle(this)
        // Enumerate controllers already connected before listener registration.
        // Without this, controllers plugged in before app launch are never assigned.
        controllerRouter.enumerateExistingDevices(inputManager)
        // On lifecycle START, re-enumerate controllers. deactivate() on STOP clears
        // all device-to-slot mappings; this restores them when the Activity resumes.
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                controllerRouter.enumerateExistingDevices(inputManager)
            }
        })

        // Phase B: register ActivityResultLauncher for EmulationActivity result.
        emulationLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleEmulationActivityResult(result)
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        Log.d(TAG, "onCreate: savedInstanceState=$savedInstanceState intent=$intent")

        // Back navigation: NATIVE_HOME exits the app; every other screen returns to
        // NATIVE_HOME (or its immediate parent) — Native Library is the app's single
        // root screen (UI_REFACTOR.md section 5). Android Back is always reserved —
        // never delegated to WebView.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Phase 5a (spec section 5.4): during onboarding, Back drives the step machine
                // (Welcome → finish, Server → Welcome, Credentials → Server). The editing
                // text-field wrapper consumes Back while a field is focused, so this
                // Activity-level handler only fires once the keyboard is dismissed.
                if (appMode == OnboardingRoutingPolicy.AppMode.ONBOARDING) {
                    handleOnboardingBack()
                    return
                }
                when (currentScreen) {
                    // finish() exits the activity directly. Calling
                    // onBackPressedDispatcher.onBackPressed() here would re-invoke this
                    // very callback (it's still enabled), causing infinite recursion and
                    // a StackOverflowError crash.
                    Screen.NATIVE_HOME -> finish()
                    Screen.NATIVE_PLATFORMS, Screen.NATIVE_COLLECTIONS, Screen.NATIVE_SEARCH, Screen.NATIVE_SETTINGS ->
                        currentScreen = Screen.NATIVE_HOME
                    Screen.NATIVE_BIOS_CONFIGURATION -> currentScreen = Screen.NATIVE_SETTINGS
                    Screen.NATIVE_CONTROLLER_LIST -> currentScreen = Screen.NATIVE_SETTINGS
                    Screen.NATIVE_CONTROLLER_CONFIG -> currentScreen = Screen.NATIVE_CONTROLLER_LIST
                    Screen.NATIVE_PLATFORM_DETAIL -> currentScreen = Screen.NATIVE_PLATFORMS
                    Screen.NATIVE_COLLECTION_DETAIL -> currentScreen = Screen.NATIVE_COLLECTIONS
                    Screen.NATIVE_GAME_DETAIL -> currentScreen = gameDetailParent
                    Screen.NATIVE_SCREENSHOT_VIEWER -> currentScreen = Screen.NATIVE_GAME_DETAIL
                    Screen.NATIVE_SAVE_PICKER -> {
                        // Dismiss picker; no filesystem/Room/network mutation occurred yet.
                        savePickerState = null
                        savePickerStagedOutcome = null
                        currentScreen = Screen.NATIVE_GAME_DETAIL
                    }
                    Screen.NATIVE_VERSION_PICKER -> {
                        // Dismiss picker; no filesystem/Room/network mutation occurred yet.
                        versionPickerState = null
                        currentScreen = Screen.NATIVE_GAME_DETAIL
                    }
                    Screen.NATIVE_CONFLICT, Screen.NATIVE_QUARANTINE -> {
                        // Dismiss overlay; return to the game detail screen for this ROM.
                        preLaunchState?.clear()
                        preLaunchState = null
                        currentScreen = Screen.NATIVE_GAME_DETAIL
                    }
                    else -> currentScreen = Screen.NATIVE_HOME
                }
            }
        })

        // Phase 5a: select the root AppMode SYNCHRONOUSLY, before setContent renders its
        // first branch. This prevents a Home flash and prevents unauthorized library calls
        // during onboarding (spec sections 2.2, 5.1). MAIN requires a coherent session record
        // AND a matching durable client token AND canonical origin agreement.
        val startupProfile = settingsRepository.currentProfile()
        val startupSession = sessionStore.current()
        val startupToken = startupSession?.let { s ->
            clientTokenStore.getToken(s.origin, s.username.orEmpty())
        }
        appMode = OnboardingRoutingPolicy.decide(startupSession, startupProfile.origin, startupToken != null)
        Log.d(DIAG_TAG, "MainActivity.onCreate: appMode=${appMode.name}")

        // Startup: verify existing session via lifecycle-aware coroutine. Runs ONLY in
        // AppMode.MAIN — during onboarding we must not import cookies or verify a session
        // (no unauthorized native calls). It refreshes cookies/session state in the background.
        lifecycleScope.launch {
            if (appMode != OnboardingRoutingPolicy.AppMode.MAIN) {
                Log.d(TAG, "Startup: skipping session verification (appMode=$appMode)")
                return@launch
            }
            val origin = currentOrigin
            Log.d(TAG, "Startup: origin configured=${origin.isNotBlank()}")
            val sessionBefore = sessionStore.current()
            val sessionPresent = sessionBefore != null
            Log.d(DIAG_TAG, "MainActivity.startup: sessionPresent=$sessionPresent")
            if (origin.isNotBlank()) {
                // Step 1: Import cookies from Android CookieManager into OkHttp store
                authRepository.importCookiesFromWebView(origin)

                // Step 2: Check existing session using imported cookies
                Log.d(TAG, "Startup: verifying existing session")
                val result = authRepository.verifySession(origin)
                Log.d(TAG, "Startup: verify success=${result is AuthFlowResult.Success}")

                when (result) {
                    is AuthFlowResult.Success -> {
                        verifiedUser = result.verifiedUser
                        authResult = result
                        // Keep WebView cookies fresh (it remains available as a settings-only
                        // fallback), but boot straight into Native Library — it no longer requires
                        // a manual Back-out-of-WebView step to reach.
                        Log.d(TAG, "Startup: session valid, syncing cookies")
                        authRepository.syncCookiesToWebView(origin)
                        currentScreen = Screen.NATIVE_HOME
                        Log.d(DIAG_TAG, "MainActivity.startup: nav NATIVE_HOME verify=success")
                    }
                    is AuthFlowResult.Failure -> {
                        authResult = result
                        Log.d(TAG, "Startup: verify failed error=${result.error} httpCode=${result.httpCode}")
                        Log.d(DIAG_TAG, "MainActivity.startup: verify failure error=${result.error.name} httpCode=${result.httpCode}")
                        // Only clear stale session when authentication is definitively expired/invalid.
                        // Transient network/TLS errors must NOT clear the SessionStore — the cookies
                        // may still be valid and a retry on next launch could succeed.
                        if (result.error == com.romm.androidtv.network.AuthError.VERIFICATION_FAILED) {
                            val stale = sessionStore.current()
                            if (stale != null) {
                                Log.d(TAG, "Startup: clearing expired session usernamePresent=${stale.username != null}")
                                authRepository.clearExpiredSession(stale.origin, stale.username ?: "")
                            }
                        }
                    }
                }
            }
        }

        setContent {
            // Phase 4 PLACEHOLDER: cancel any in-progress capture whenever the
            // active screen changes. The actual capture-triggering UI (pause /
            // controller-config screens) does not exist yet — it arrives in
            // Phase 5/7 — so this generic hook guarantees a capture can never
            // survive a screen transition. Revisit when that UI is wired.
            LaunchedEffect(Unit) {
                snapshotFlow { currentScreen }.collect { captureCoordinator.cancel() }
            }
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
        ) {
            if (appMode == OnboardingRoutingPolicy.AppMode.ONBOARDING) {
                OnboardingHost()
            } else {
                when (currentScreen) {
                Screen.NATIVE_HOME, Screen.NATIVE_PLATFORMS, Screen.NATIVE_COLLECTIONS, Screen.NATIVE_SEARCH,
                        Screen.NATIVE_SETTINGS, Screen.NATIVE_PLATFORM_DETAIL, Screen.NATIVE_COLLECTION_DETAIL,
                        Screen.NATIVE_GAME_DETAIL, Screen.NATIVE_CONFLICT, Screen.NATIVE_QUARANTINE,
                        Screen.NATIVE_SAVE_PICKER, Screen.NATIVE_VERSION_PICKER, Screen.NATIVE_BIOS_CONFIGURATION,
                        Screen.NATIVE_CONTROLLER_LIST, Screen.NATIVE_CONTROLLER_CONFIG,
                        Screen.NATIVE_SCREENSHOT_VIEWER -> {
                            val homeViewModel: com.romm.androidtv.library.HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                factory = com.romm.androidtv.library.HomeViewModel.Factory(
                                    libraryRepository,
                                    hideUnsupportedSystems = { settingsRepository.hideUnsupportedSystems() },
                                    hideUnsupportedSystemsFlow = settingsRepository.hideUnsupportedSystemsFlow,
                                    refreshEvents = libraryRefreshEvents,
                                )
                            )
                            // Re-fetch Continue Playing right after exiting a game (continuePlayingRefreshTick's
                            // doc comment) instead of waiting for the next cold app start. Skips the initial
                            // tick==0 composition since HomeViewModel.init already loads it once.
                            androidx.compose.runtime.LaunchedEffect(continuePlayingRefreshTick) {
                                if (continuePlayingRefreshTick > 0) {
                                    homeViewModel.retryContinuePlaying()
                                }
                            }
                            com.romm.androidtv.library.ui.RommTvTheme {
                                when (currentScreen) {
                                    Screen.NATIVE_PLATFORM_DETAIL -> {
                                        val platformId = selectedPlatformId
                                        if (platformId != null) {
                                            val gridViewModel: com.romm.androidtv.library.RomGridViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                                key = "platform-detail-$platformId",
                                                factory = com.romm.androidtv.library.RomGridViewModel.Factory(
                                                    libraryRepository,
                                                    com.romm.androidtv.library.RomQuery.ByPlatform(platformId),
                                                    hideUnsupportedSystems = { settingsRepository.hideUnsupportedSystems() },
                                                    hideUnsupportedSystemsFlow = settingsRepository.hideUnsupportedSystemsFlow,
                                                    refreshEvents = libraryRefreshEvents,
                                                ),
                                            )
                                            com.romm.androidtv.library.ui.RomGridScreen(
                                                title = "Platform",
                                                viewModel = gridViewModel,
                                                onOpenGameDetail = { romId ->
                                                    selectedRomId = romId
                                                    gameDetailParent = Screen.NATIVE_PLATFORM_DETAIL
                                                    currentScreen = Screen.NATIVE_GAME_DETAIL
                                                },
                                            )
                                        }
                                    }
                                    Screen.NATIVE_COLLECTION_DETAIL -> {
                                        val collectionId = selectedCollectionId
                                        if (collectionId != null) {
                                            val gridViewModel: com.romm.androidtv.library.RomGridViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                                key = "collection-detail-$collectionId",
                                                factory = com.romm.androidtv.library.RomGridViewModel.Factory(
                                                    libraryRepository,
                                                    com.romm.androidtv.library.RomQuery.ByCollection(collectionId),
                                                    hideUnsupportedSystems = { settingsRepository.hideUnsupportedSystems() },
                                                    hideUnsupportedSystemsFlow = settingsRepository.hideUnsupportedSystemsFlow,
                                                    refreshEvents = libraryRefreshEvents,
                                                ),
                                            )
                                            com.romm.androidtv.library.ui.RomGridScreen(
                                                title = "Collection",
                                                viewModel = gridViewModel,
                                                onOpenGameDetail = { romId ->
                                                    selectedRomId = romId
                                                    gameDetailParent = Screen.NATIVE_COLLECTION_DETAIL
                                                    currentScreen = Screen.NATIVE_GAME_DETAIL
                                                },
                                            )
                                        }
                                    }
                                    Screen.NATIVE_GAME_DETAIL -> {
                                        val romId = selectedRomId
                                        if (romId != null) {
                                            val detailViewModel: com.romm.androidtv.library.RomDetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                                key = "game-detail-$romId",
                                                factory = com.romm.androidtv.library.RomDetailViewModel.Factory(
                                                    libraryRepository,
                                                    romId,
                                                    refreshEvents = libraryRefreshEvents,
                                                ),
                                            )
                                            // Production overlay: only conflict/quarantine (blocking) overlays replace the game detail screen.
                                            // Error-only states render inline within GameDetailScreen.
                                            val state = preLaunchState
                                            if (state != null && state.matchesScope(romId, state.sessionId) && state.hasBlockingOverlay) {
                                                renderPreLaunchOverlay(state)
                                            } else {
                                                com.romm.androidtv.library.ui.GameDetailScreen(
                                                        viewModel = detailViewModel,
                                                        onPlay = { playRomId ->
                                                            nativeLibraryOnPlay(playRomId)
                                                        },
                                                        onChooseSave = { chooseRomId ->
                                                            nativeLibraryOnChooseSave(chooseRomId)
                                                        },
                                                        onChooseVersion = { chooseRomId ->
                                                            nativeLibraryOnChooseVersion(chooseRomId)
                                                        },
                                                        isStaging = state?.let { s ->
                                                            s.matchesScope(romId, s.sessionId) && s.isStaging
                                                        } ?: false,
                                                        errorMessage = state?.let { s ->
                                                            if (s.matchesScope(romId, s.sessionId)) s.errorMessage else null
                                                        },
                                                        onDismissError = {
                                                            // Clear error-only overlay; Play re-enabled.
                                                            preLaunchState = null
                                                        },
                                                        isAuthExpired = state?.let { s ->
                                                            s.matchesScope(romId, s.sessionId) && s.isAuthExpired
                                                        } ?: false,
                                                         onLogin = {
                                                             // Navigate to Native Settings, where login now lives; do NOT auto-submit credentials.
                                                             Log.d(DIAG_TAG, "MainActivity.nav: NATIVE_GAME_DETAIL->NATIVE_SETTINGS authExpiredPrompt")
                                                             preLaunchState = null
                                                             currentScreen = Screen.NATIVE_SETTINGS
                                                         },
                                                         biosState = requiredBiosState,
                                                         onCheckBios = ::checkRequiredBiosAvailability,
                                                         onOpenScreenshot = { urls, index ->
                                                             selectedScreenshotUrls = urls
                                                             selectedScreenshotIndex = index
                                                             currentScreen = Screen.NATIVE_SCREENSHOT_VIEWER
                                                         },
                                                    )
                                            }
                                        }
                                    }
                                    Screen.NATIVE_SCREENSHOT_VIEWER -> {
                                        com.romm.androidtv.library.ui.ScreenshotViewerScreen(
                                            screenshotUrls = selectedScreenshotUrls,
                                            initialIndex = selectedScreenshotIndex,
                                        )
                                    }
                                    Screen.NATIVE_CONFLICT -> {
                                        val state = preLaunchState
                                        if (state != null && state.conflictModel != null) {
                                            renderPreLaunchOverlay(state)
                                        } else {
                                            // Safety fallback: return to game detail.
                                            preLaunchState = null
                                            currentScreen = Screen.NATIVE_GAME_DETAIL
                                        }
                                    }
                                    Screen.NATIVE_QUARANTINE -> {
                                        val state = preLaunchState
                                        if (state != null && state.quarantineModel != null) {
                                            renderPreLaunchOverlay(state)
                                        } else {
                                            // Safety fallback: return to game detail.
                                            preLaunchState = null
                                            currentScreen = Screen.NATIVE_GAME_DETAIL
                                        }
                                    }
                                    Screen.NATIVE_SAVE_PICKER -> {
                                        val pickerState = savePickerState
                                        if (pickerState != null) {
                                            com.romm.androidtv.library.ui.SavePickerScreen(
                                                state = pickerState,
                                                onSelect = { entry -> nativeLibraryOnChooseSaveSelected(entry) },
                                                onBack = {
                                                    savePickerState = null
                                                    savePickerStagedOutcome = null
                                                    currentScreen = Screen.NATIVE_GAME_DETAIL
                                                },
                                                onRetry = {
                                                    selectedRomId?.let { nativeLibraryOnChooseSave(it) }
                                                },
                                            )
                                        } else {
                                            // Safety fallback: return to game detail.
                                            currentScreen = Screen.NATIVE_GAME_DETAIL
                                        }
                                    }
                                    Screen.NATIVE_VERSION_PICKER -> {
                                        val pickerState = versionPickerState
                                        if (pickerState != null) {
                                            com.romm.androidtv.library.ui.VersionPickerScreen(
                                                state = pickerState,
                                                onSelect = { entry -> nativeLibraryOnVersionSelected(entry) },
                                                onBack = {
                                                    versionPickerState = null
                                                    currentScreen = Screen.NATIVE_GAME_DETAIL
                                                },
                                                onRetry = {
                                                    selectedRomId?.let { nativeLibraryOnChooseVersion(it) }
                                                },
                                            )
                                        } else {
                                            // Safety fallback: return to game detail.
                                            currentScreen = Screen.NATIVE_GAME_DETAIL
                                        }
                                    }
                                    Screen.NATIVE_PLATFORMS -> {
                                        val state by homeViewModel.uiState.collectAsState()
                                        com.romm.androidtv.library.ui.LibraryScaffold(
                                            current = com.romm.androidtv.library.ui.NavDestination.PLATFORMS,
                                            onNavigate = { destination -> currentScreen = destination.toScreen() },
                                        ) {
                                            com.romm.androidtv.library.ui.PlatformsScreen(
                                                state = state.platforms,
                                                onRetry = homeViewModel::retryPlatforms,
                                                onOpenPlatform = { platformId ->
                                                    selectedPlatformId = platformId
                                                    currentScreen = Screen.NATIVE_PLATFORM_DETAIL
                                                },
                                            )
                                        }
                                    }
                                    Screen.NATIVE_COLLECTIONS -> {
                                        val state by homeViewModel.uiState.collectAsState()
                                        com.romm.androidtv.library.ui.LibraryScaffold(
                                            current = com.romm.androidtv.library.ui.NavDestination.COLLECTIONS,
                                            onNavigate = { destination -> currentScreen = destination.toScreen() },
                                        ) {
                                            com.romm.androidtv.library.ui.CollectionsScreen(
                                                state = state.collections,
                                                onRetry = homeViewModel::retryCollections,
                                                onOpenCollection = { collectionId ->
                                                    selectedCollectionId = collectionId
                                                    currentScreen = Screen.NATIVE_COLLECTION_DETAIL
                                                },
                                            )
                                        }
                                    }
                                    Screen.NATIVE_SEARCH -> com.romm.androidtv.library.ui.LibraryScaffold(
                                        current = com.romm.androidtv.library.ui.NavDestination.SEARCH,
                                        onNavigate = { destination -> currentScreen = destination.toScreen() },
                                    ) {
                                        com.romm.androidtv.library.ui.SearchScreen(
                                            onGameSelected = { romId ->
                                                selectedRomId = romId
                                                gameDetailParent = Screen.NATIVE_SEARCH
                                                currentScreen = Screen.NATIVE_GAME_DETAIL
                                            },
                                            hideUnsupportedSystems = { settingsRepository.hideUnsupportedSystems() },
                                            hideUnsupportedSystemsFlow = settingsRepository.hideUnsupportedSystemsFlow,
                                            refreshEvents = libraryRefreshEvents,
                                        )
                                    }
                                    Screen.NATIVE_SETTINGS -> {
                                        com.romm.androidtv.library.ui.LibraryScaffold(
                                            current = com.romm.androidtv.library.ui.NavDestination.SETTINGS,
                                            onNavigate = { destination -> currentScreen = destination.toScreen() },
                                        ) {
                                            com.romm.androidtv.library.ui.SettingsScreen(
                                                viewModelFactory = com.romm.androidtv.library.SettingsViewModel.Factory(
                                                    settingsRepository,
                                                    sessionStore,
                                                    authRepository,
                                                    BuildConfig.ROMM_ORIGIN,
                                                    onSessionInvalidated = {
                                                        // Phase 5a (spec section 5.3): a server-origin change invalidated
                                                        // the session. Route to onboarding (Server step) with the newly
                                                        // selected origin prefilled by the fresh OnboardingViewModel; do
                                                        // NOT show Home. SettingsViewModel's clearSessionFn already cleared
                                                        // the old username + client token.
                                                        verifiedUser = null
                                                        authResult = null
                                                        // Route to onboarding at the SERVER step; the newly selected origin
                                                        // is prefilled via initialServerInput (currentProfile().origin).
                                                        enterOnboarding(startStep = OnboardingStep.SERVER)
                                                    },
                                                    onLoginSuccess = {
                                                        // Existing view models may still contain responses fetched
                                                        // before credentials were available.
                                                        libraryRefreshEvents.tryEmit(Unit)
                                                        currentScreen = Screen.NATIVE_HOME
                                                    },
                                                ),
                                                onOpenSegaCdBios = {
                                                    selectedBiosSystem = BiosSystem.SEGA_CD
                                                    currentScreen = Screen.NATIVE_BIOS_CONFIGURATION
                                                },
                                                onOpenPlayStationBios = {
                                                    selectedBiosSystem = BiosSystem.PLAYSTATION
                                                    currentScreen = Screen.NATIVE_BIOS_CONFIGURATION
                                                },
                                                onOpenControllerSettings = {
                                                    currentScreen = Screen.NATIVE_CONTROLLER_LIST
                                                },
                                            )
                                        }
                                    }
                                    Screen.NATIVE_CONTROLLER_LIST -> {
                                        com.romm.androidtv.controller.ui.ControllerConsoleListScreen(
                                            profiles = com.romm.androidtv.controller.config.CoreControllerProfiles.forApprovedCores(),
                                            onSelectCore = { coreId ->
                                                selectedControllerCoreId = coreId
                                                currentScreen = Screen.NATIVE_CONTROLLER_CONFIG
                                            },
                                            onBack = { currentScreen = Screen.NATIVE_SETTINGS },
                                        )
                                    }
                                    Screen.NATIVE_CONTROLLER_CONFIG -> {
                                        val profile = selectedControllerCoreId?.let {
                                            com.romm.androidtv.controller.config.CoreControllerProfiles.byCoreId(it)
                                        }
                                        if (profile == null) {
                                            Log.w(
                                                "MainActivity",
                                                "Controller config opened without a valid core profile; " +
                                                    "returning to controller list.",
                                            )
                                            currentScreen = Screen.NATIVE_CONTROLLER_LIST
                                        } else {
                                            val controllerFactory =
                                                com.romm.androidtv.controller.ui.ControllerSettingsViewModel.Factory(
                                                    coreId = selectedControllerCoreId!!,
                                                    profile = profile,
                                                    repository = controllerConfigRepository,
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
                                            val controllerViewModel:
                                                com.romm.androidtv.controller.ui.ControllerSettingsViewModel =
                                                androidx.lifecycle.viewmodel.compose.viewModel(
                                                    key = "controller-settings-${selectedControllerCoreId}",
                                                    factory = controllerFactory,
                                                )
                                            val controllerSlots by controllerRouter.slotsFlow.collectAsState()
                                            LaunchedEffect(controllerSlots) {
                                                controllerViewModel.refreshConnectedDevices()
                                            }
                                            LaunchedEffect(controllerViewModel) {
                                                controllerRouter.physicalInputActivity.collect {
                                                    controllerViewModel.onControllerActivity(it)
                                                }
                                            }
                                            val uiState by controllerViewModel.uiState.collectAsState()
                                            com.romm.androidtv.controller.ui.ControllerConfigScreen(
                                                state = uiState,
                                                onBack = { currentScreen = Screen.NATIVE_CONTROLLER_LIST },
                                                onSelectTab = controllerViewModel::selectTab,
                                                onRowFocused = controllerViewModel::onRowFocused,
                                                onRowSelected = controllerViewModel::onRowSelected,
                                                onCaptureDialogDismiss = controllerViewModel::dismissCaptureDialog,
                                                onConflictResolution = controllerViewModel::resolveConflict,
                                                onResetPlayer = controllerViewModel::resetPlayer,
                                                onResetAllConfirm = controllerViewModel::confirmResetAll,
                                                onResetAllRequest = controllerViewModel::requestResetAll,
                                                onResetAllCancel = controllerViewModel::cancelResetAll,
                                            )
                                        }
                                    }
                                    Screen.NATIVE_BIOS_CONFIGURATION -> {
                                       val biosFactory = when (selectedBiosSystem) {
                                           BiosSystem.SEGA_CD ->
                                               com.romm.androidtv.library.BiosConfigurationViewModel.Factory(segaCdBiosManager)
                                           BiosSystem.PLAYSTATION ->
                                               com.romm.androidtv.library.BiosConfigurationViewModel.Factory(psxBiosManager)
                                       }
                                       val biosViewModel: com.romm.androidtv.library.BiosConfigurationViewModel =
                                           androidx.lifecycle.viewmodel.compose.viewModel(
                                               key = "${selectedBiosSystem.name.lowercase()}-bios-configuration",
                                               factory = biosFactory,
                                           )
                                       com.romm.androidtv.library.ui.BiosConfigurationScreen(
                                           viewModel = biosViewModel,
                                           onBack = { currentScreen = Screen.NATIVE_SETTINGS },
                                       )
                                    }
                                    else -> com.romm.androidtv.library.ui.LibraryScaffold(
                                        current = com.romm.androidtv.library.ui.NavDestination.HOME,
                                        onNavigate = { destination -> currentScreen = destination.toScreen() },
                                    ) {
                                        com.romm.androidtv.library.ui.NativeHomeScreen(
                                            viewModel = homeViewModel,
                                            onOpenGameDetail = { romId ->
                                                selectedRomId = romId
                                                gameDetailParent = Screen.NATIVE_HOME
                                                currentScreen = Screen.NATIVE_GAME_DETAIL
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }

    /**
     * Phase 5a: renders the first-run onboarding flow when [OnboardingRoutingPolicy.AppMode]
     * is ONBOARDING. Constructs a fresh [OnboardingViewModel] per onboarding session (keyed by
     * [onboardingSessionId]) so a completed instance is never reused. On
     * [OnboardingEffect.Completed]: syncs cookies best-effort, flips to MAIN, lands in Native
     * Library Home, and refreshes surviving library view models. Home's initial focus is placed
     * by NativeHomeScreen's existing focus behavior.
     */
    @Composable
    private fun OnboardingHost() {
        val onboardingViewModel: OnboardingViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
            key = "onboarding-${onboardingSessionId}",
            factory = OnboardingViewModelFactory(
                validateRommServer = { origin -> authRepository.validateServer(origin) },
                persistValidatedOrigin = { origin -> settingsRepository.persistValidatedOrigin(origin) },
                loginToRomm = { origin, username, password -> authRepository.loginOnboarding(origin, username, password) },
                initialServerInput = settingsRepository.currentProfile().origin,
                initialStep = onboardingStartStep,
                initialUsername = onboardingStartUsername,
            ),
        )
        activeOnboardingViewModel = onboardingViewModel

        LaunchedEffect(onboardingViewModel) {
            onboardingViewModel.effects.collect { effect ->
                if (effect == OnboardingEffect.Completed) {
                    // 1. Best-effort cookie sync so any served WebView shares the native session.
                    runCatching { authRepository.syncCookiesToWebView(settingsRepository.currentProfile().origin) }
                    // 2–3. Switch to MAIN and land in Native Library Home.
                    appMode = OnboardingRoutingPolicy.AppMode.MAIN
                    currentScreen = Screen.NATIVE_HOME
                    // 4. Refresh any surviving library view models.
                    libraryRefreshEvents.tryEmit(Unit)
                    // 5. NativeHomeScreen already places initial focus on Home.
                }
            }
        }

        val state by onboardingViewModel.uiState.collectAsState()
        OnboardingScreen(
            state = state,
            onContinue = onboardingViewModel::onContinue,
            onServerChanged = onboardingViewModel::onServerChanged,
            onValidateServer = onboardingViewModel::onValidateServer,
            onUsernameChanged = onboardingViewModel::onUsernameChanged,
            onPasswordChanged = onboardingViewModel::onPasswordChanged,
            onLogin = onboardingViewModel::onLogin,
            onBack = ::handleOnboardingBack,
        )

        DisposableEffect(onboardingViewModel) {
            onDispose {
                if (activeOnboardingViewModel === onboardingViewModel) {
                    activeOnboardingViewModel = null
                }
            }
        }
    }

    /**
     * Phase 5a Back dispatch (spec section 5.4). Welcome finishes the Activity; Server and
     * Credentials delegate to [OnboardingViewModel.onBack], which handles step transitions and
     * password clearing. Called both from the onboarding screen's key handler and the
     * Activity-level Back callback (mutually exclusive in practice).
     */
    private fun handleOnboardingBack() {
        val vm = activeOnboardingViewModel
        if (vm == null) {
            finish()
            return
        }
        when (vm.uiState.value.step) {
            OnboardingStep.WELCOME -> finish()
            else -> vm.onBack()
        }
    }

    /**
     * Switches to the onboarding flow with a fresh ViewModel (new session). [startStep]
     * selects the initial step (WELCOME fresh install, SERVER after Settings invalidation,
     * CREDENTIALS after a definitively-invalid bearer token) and [prefillUsername] is the
     * known username to prefill when starting at CREDENTIALS.
     */
    private fun enterOnboarding(
        startStep: OnboardingStep = OnboardingStep.WELCOME,
        prefillUsername: String = "",
    ) {
        onboardingStartStep = startStep
        onboardingStartUsername = prefillUsername
        onboardingSessionId++
        appMode = OnboardingRoutingPolicy.AppMode.ONBOARDING
    }

    /**
     * Routes to onboarding CREDENTIALS with [username] prefilled after a DEFINITIVE token or
     * session failure (spec 5.2.7). Only called after verification confirmed the token is
     * actually invalid — never on transient network/TLS failures or permission-only errors.
     */
    private suspend fun routeToCredentials(username: String?) {
        withContext(Dispatchers.Main) {
            preLaunchState = null
            enterOnboarding(
                startStep = OnboardingStep.CREDENTIALS,
                prefillUsername = username.orEmpty(),
            )
        }
    }

    // Phase B: recover pending journal entries on resume. If EmulationActivity died
    // without delivering a result, the journal's ADOPTED state is replayed idempotently.
    override fun onResume() {
        super.onResume()
        // Phase 5a: do NOT force the sync/emulation lazy chain (emulationResultHandler ->
        // saveSyncCoordinator -> buildBearerAuthClient) while onboarding is active — with
        // empty app data the profile origin is blank and the chain must not run at all.
        // It evaluates lazily only once the app is in MAIN mode with a valid origin.
        if (appMode == OnboardingRoutingPolicy.AppMode.MAIN) {
            emulationResultHandler.recoverPendingSessions()
        }
    }

    private fun launchEmulationActivity(spec: com.romm.androidtv.emulation.model.LaunchSpec, savePath: String, candidateMetadata: CandidateSaveMetadata?) {
        // Use LaunchSpec.sessionId (UUID) as the authoritative app session ID for ALL correlation.
        val appSessionId = spec.sessionIdString

        // Cache candidate metadata keyed by app session ID for finalization lookup (thread-safe).
        candidateMetadata?.let { meta ->
            emulationResultHandler.cacheCandidateMetadata(appSessionId, meta)
        }

        // Patch journal with core metadata for post-play recovery (syncPostPlay needs coreId/coreBuildRevision).
        try {
            val journalDir = filesDir.resolve("launch_sessions")
            val journal = LaunchSessionJournal(journalDir)
            // Must exist before patchIdentity() can target it — createOrGet() is idempotent,
            // so this is safe even if EmulationActivity's own onCreate() later calls it again
            // for the same session ID.
            journal.createOrGet(appSessionId)
            val coreFinding = com.romm.androidtv.emulation.model.CoreManifest.findById(spec.coreId)
            val coreBuildRevision = coreFinding?.commitSha?.takeIf { it.isNotBlank() }
                ?: coreFinding?.releaseTag?.takeIf { it.isNotBlank() }
            if (coreBuildRevision != null) {
                journal.patchIdentity(appSessionId, SessionDescriptorPatch(
                    romId = spec.romId,
                    romHash = spec.romHash,
                    coreId = spec.coreId,
                    coreBuildRevision = coreBuildRevision,
                    canonicalFileName = spec.serverSaveFileName,
                ))
            }
        } catch (e: Exception) {
            Log.w(TAG, "launchEmulationActivity: failed to patch journal with core metadata", e)
        }

        emulationLauncher.launch(
            Intent(this, com.romm.androidtv.emulation.process.EmulationActivity::class.java).apply {
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_APP_SESSION_ID, appSessionId)
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_CORE_ID, spec.coreId)
                putExtra(
                    com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_CONTENT_PATH,
                    spec.contentPath
                )
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_SAVE_PATH, savePath)
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_ROM_ID, spec.romId)
                candidateMetadata?.let { CandidateExtras.putIntoIntent(this, it) }
            }
        )
    }

    /**
     * Phase B: handles the ActivityResult from EmulationActivity. Delegates to [EmulationResultHandler]
     * which provides per-session serialization and thread-safe candidate metadata caching.
     */
    private fun handleEmulationActivityResult(result: androidx.activity.result.ActivityResult) {
        val data = result.data ?: return
        val sessionId = data.getStringExtra("session_id") ?: return

        when (result.resultCode) {
            android.app.Activity.RESULT_OK -> {
                val checkpointedPath = data.getStringExtra("checkpointed_save_path")
                val checkpointedHash = data.getStringExtra("checkpointed_save_hash")
                val resultRomId = data.getLongExtra("rom_id", -1L)
                val playSessionStartEpochMs = data.getLongExtra("play_session_start_epoch_ms", -1L)
                val playSessionEndEpochMs = data.getLongExtra("play_session_end_epoch_ms", -1L)

                lifecycleScope.launch {
                    emulationResultHandler.handleEmulationResult(
                        sessionId = sessionId,
                        resultCode = android.app.Activity.RESULT_OK,
                        checkpointedPath = checkpointedPath,
                        checkpointedHash = checkpointedHash,
                        resultRomId = resultRomId,
                        playSessionStartEpochMs = playSessionStartEpochMs,
                        playSessionEndEpochMs = playSessionEndEpochMs,
                    )
                    // Refresh Continue Playing immediately rather than waiting for the next
                    // cold app start — see continuePlayingRefreshTick's doc comment.
                    continuePlayingRefreshTick++
                }
            }
            android.app.Activity.RESULT_CANCELED -> {
                Log.w(TAG, "handleEmulationActivityResult: cancelled for session $sessionId")
                emulationResultHandler.removeCandidateMetadata(sessionId)
            }
        }
    }

    // ---- Native Library Play button: stage → sync negotiate → launch or overlay ----

    /**
     * Entry point invoked by GameDetailScreen's Play button in the Native Library flow.
     * Stages the ROM, pre-launch-syncs saves, and either launches EmulationActivity
     * or shows a conflict/quarantine overlay. Does NOT wire served RomM WebView;
     * does NOT change PlaybackBackendPolicy.
     *
     * Duplicate-entry guard: if current [preLaunchState] for the same [romId] is already
     * staging, this call returns immediately — repeated clicks cannot launch multiple pipelines.
     * On failure, clears staging then sets error (recomposition-safe order).
     * On success, proceeds to activity launch and clears preLaunchState.
     */
    private fun nativeLibraryOnPlay(romId: Long) {
        // Duplicate-entry guard: reject if same romId is already staging.
        val existing = preLaunchState
        if (existing != null && existing.matchesScope(romId, existing.sessionId) && existing.isStaging) {
            Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnPlay: duplicate rejected")
            return
        }

        Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnPlay: entered romId=$romId")
        lifecycleScope.launch {
            // Clear old error on retry; set staging BEFORE async work so UI recomposes.
            val state = com.romm.androidtv.library.ui.SavePreLaunchState(romId = romId)
                .apply { isStaging = true }
            preLaunchState = state

            try {
                val outcome = romRepository.stageForLaunch(romId)
                if (outcome is com.romm.androidtv.romm.StagingOutcome.Success) {
                    // Keep the guard active through BIOS staging and save negotiation.
                    launchStagedRomNativeLibrary(outcome)
                } else {
                    withContext(Dispatchers.Main) {
                        // Failure transition: clear staging FIRST, then set error.
                        // Order matters: Compose recomposes on each mutableStateOf write,
                        // so clearing staging first removes "Preparing…" before error appears.
                        state.isStaging = false
                        state.errorMessage = StagingOutcomeMessage.toUserMessage(outcome)
                    }
                }

            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    state.isStaging = false
                    state.errorMessage = "Launch preparation failed: ${t.message}"
                }
            }
        }
    }

    private fun checkRequiredBiosAvailability(platformSlug: String) {
        requiredBiosState = com.romm.androidtv.library.ui.RequiredBiosState.Checking
        lifecycleScope.launch {
            requiredBiosState = when (platformSlug) {
                "segacd" -> when (val availability = segaCdBiosManager.checkAvailability()) {
                com.romm.androidtv.romm.SegaCdBiosManager.Availability.Ready ->
                    com.romm.androidtv.library.ui.RequiredBiosState.Ready
                com.romm.androidtv.romm.SegaCdBiosManager.Availability.Missing ->
                    com.romm.androidtv.library.ui.RequiredBiosState.Missing
                com.romm.androidtv.romm.SegaCdBiosManager.Availability.NeedsManualSelection ->
                    com.romm.androidtv.library.ui.RequiredBiosState.UnverifiedAvailable
                com.romm.androidtv.romm.SegaCdBiosManager.Availability.AuthExpired ->
                    com.romm.androidtv.library.ui.RequiredBiosState.Error(
                        "Session expired. Log in again to check for BIOS files.",
                    )
                is com.romm.androidtv.romm.SegaCdBiosManager.Availability.Error ->
                    com.romm.androidtv.library.ui.RequiredBiosState.Error(
                        "Couldn't check BIOS files (${availability.message.lowercase().replace('_', ' ')}).",
                    )
                }
                "psx" -> when (val availability = psxBiosManager.checkAvailability()) {
                    com.romm.androidtv.romm.PsxBiosManager.Availability.Ready ->
                        com.romm.androidtv.library.ui.RequiredBiosState.Ready
                    com.romm.androidtv.romm.PsxBiosManager.Availability.Missing ->
                        com.romm.androidtv.library.ui.RequiredBiosState.Missing
                    com.romm.androidtv.romm.PsxBiosManager.Availability.NeedsManualSelection ->
                        com.romm.androidtv.library.ui.RequiredBiosState.UnverifiedAvailable
                    com.romm.androidtv.romm.PsxBiosManager.Availability.AuthExpired ->
                        com.romm.androidtv.library.ui.RequiredBiosState.Error(
                            "Session expired. Log in again to check for BIOS files.",
                        )
                    is com.romm.androidtv.romm.PsxBiosManager.Availability.Error ->
                        com.romm.androidtv.library.ui.RequiredBiosState.Error(
                            "Couldn't check BIOS files (${availability.message.lowercase().replace('_', ' ')}).",
                        )
                }
                else -> com.romm.androidtv.library.ui.RequiredBiosState.Ready
            }
        }
    }

    /**
     * Entry point invoked by GameDetailScreen's "Choose Save" affordance. Stages the ROM (needed
     * for its LaunchSpec — content path, coreId, romHash — used later if the user picks a save),
     * then lists every server save for the ROM (all cores/devices; SRAM saves are cross-core
     * compatible for the same platform, so no core filter is applied). Does not download or
     * adopt anything yet — selecting a row is handled by [nativeLibraryOnChooseSaveSelected].
     */
    private fun nativeLibraryOnChooseSave(romId: Long) {
        Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnChooseSave: entered romId=$romId")
        savePickerState = com.romm.androidtv.library.ui.SavePickerState.Loading
        savePickerStagedOutcome = null
        currentScreen = Screen.NATIVE_SAVE_PICKER

        lifecycleScope.launch {
            try {
                val stagingOutcome = romRepository.stageForLaunch(romId)
                if (stagingOutcome !is com.romm.androidtv.romm.StagingOutcome.Success) {
                    withContext(Dispatchers.Main) {
                        savePickerState = com.romm.androidtv.library.ui.SavePickerState.Error(
                            StagingOutcomeMessage.toUserMessage(stagingOutcome)
                        )
                    }
                    return@launch
                }
                savePickerStagedOutcome = stagingOutcome
                val spec = stagingOutcome.launchSpec

                val romTitle = when (val detail = libraryRepository.fetchRomDetail(romId)) {
                    is com.romm.androidtv.library.LibraryResult.Success -> detail.data.title
                    is com.romm.androidtv.library.LibraryResult.Failure -> "Game #$romId"
                }

                when (val listResult = saveSyncCoordinator.listSavesForRom(romId)) {
                    is com.romm.androidtv.romm.SaveListResult.Success -> {
                        val session = sessionStore.current()
                        val currentlyAdoptedSaveId = session?.let { sess ->
                            saveSyncCoordinator.findSaveReplicaByScope(
                                serverKey = extractServerKey(sess.origin),
                                userKey = sess.username ?: "",
                                romId = spec.romId,
                                romHash = spec.romHash,
                                slot = SavePathPolicy.AUTOSAVE_SLOT,
                            )?.rommSaveId
                        }

                        val entries = listResult.saves
                            // Shows every save for this ROM regardless of which core produced it —
                            // SRAM saves are cross-core compatible for the same platform (e.g. a
                            // sameboy save loads fine under gambatte), so filtering by coreId would
                            // hide perfectly valid choices.
                            .sortedByDescending { it.updatedAt }
                            .map { save ->
                                com.romm.androidtv.library.ui.SavePickerEntryUiModel(
                                    saveId = save.saveId,
                                    fileName = save.fileName,
                                    coreId = save.emulator,
                                    sizeText = com.romm.androidtv.library.ui.ConflictResolutionMapper.formatSize(save.fileSizeBytes),
                                    updatedAtText = com.romm.androidtv.library.ui.ConflictResolutionMapper.formatInstant(
                                        save.updatedAt?.toEpochMilli()
                                    ),
                                    isCurrentlyAdopted = save.saveId == currentlyAdoptedSaveId,
                                    contentHash = save.contentHash,
                                )
                            }

                        withContext(Dispatchers.Main) {
                            savePickerState = com.romm.androidtv.library.ui.SavePickerState.Loaded(
                                com.romm.androidtv.library.ui.SavePickerUiModel(romTitle = romTitle, entries = entries)
                            )
                        }
                    }
                    is com.romm.androidtv.romm.SaveListResult.Failure -> {
                        withContext(Dispatchers.Main) {
                            savePickerState = com.romm.androidtv.library.ui.SavePickerState.Error(
                                "Couldn't load saves (${listResult.error})"
                            )
                        }
                    }
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    savePickerState = com.romm.androidtv.library.ui.SavePickerState.Error(
                        "Failed to load saves: ${t.message}"
                    )
                }
            }
        }
    }

    /**
     * Handles the user picking one entry in the save picker: returns to the game detail screen
     * immediately (matching the ordinary Play flow's screen state) and downloads+adopts the
     * chosen save via [SaveLaunchOrchestrator.prepareWithChosenSave], then dispatches the result
     * exactly like an ordinary launch — Ready launches EmulationActivity, Quarantined/Conflict
     * show their existing overlays, Failed/AuthExpired surface as an error/auth-expired state.
     */
    private fun nativeLibraryOnChooseSaveSelected(entry: com.romm.androidtv.library.ui.SavePickerEntryUiModel) {
        val outcome = savePickerStagedOutcome ?: return
        val spec = outcome.launchSpec
        Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnChooseSaveSelected: romId=${spec.romId} chosenSaveId=${entry.saveId}")

        savePickerState = null
        savePickerStagedOutcome = null
        currentScreen = Screen.NATIVE_GAME_DETAIL

        val session = sessionStore.current()
        val serverKey = session?.origin?.let { extractServerKey(it) } ?: "unknown-server"
        val userKey = session?.username ?: "unknown-user"
        val savePath = SavePathPolicy.autosaveSramPath(
            filesDir = filesDir,
            serverKey = serverKey,
            userKey = userKey,
            romId = spec.romId,
            romHash = spec.romHash,
        )

        preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId)
            .apply { isStaging = true }

        lifecycleScope.launch {
            val requiredBios = when (spec.platformSlug) {
                "segacd" -> segaCdBiosManager.prepareForLaunch(filesDir.resolve("system")) to "Sega CD"
                "psx" -> psxBiosManager.prepareForLaunch(filesDir.resolve("system")) to "PlayStation"
                else -> null
            }
            if (requiredBios != null && requiredBios.first !is
                com.romm.androidtv.romm.FirmwareStagingOutcome.Success
            ) {
                withContext(Dispatchers.Main) {
                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(
                        romId = spec.romId,
                        romHash = spec.romHash,
                    ).apply {
                        isStaging = false
                        errorMessage = biosStagingError(requiredBios.first, requiredBios.second)
                    }
                }
                return@launch
            }

            val knownSramSize = session?.let { sess ->
                saveSyncCoordinator.findSaveReplicaByScope(
                    serverKey = extractServerKey(sess.origin),
                    userKey = sess.username ?: "",
                    romId = spec.romId,
                    romHash = spec.romHash,
                    slot = SavePathPolicy.AUTOSAVE_SLOT,
                )?.expectedSramSizeBytes
            }
            val preparation = saveLaunchOrchestrator.prepareWithChosenSave(
                romId = spec.romId,
                romHash = spec.romHash,
                coreId = spec.coreId,
                expectedSramSizeBytes = knownSramSize,
                chosenSaveId = entry.saveId,
                chosenSaveEmulator = entry.coreId,
                chosenSaveContentHash = entry.contentHash,
            )
            dispatchPreparationResult(preparation, spec, savePath)
        }
    }

    /**
     * Entry point invoked by GameDetailScreen's "Choose Version" affordance. Fetches this ROM's
     * detail (for its title + sibling list) and builds the version-picker's entries from the
     * current rom plus each [com.romm.androidtv.library.SiblingRomInfo] — no staging happens
     * here, unlike [nativeLibraryOnChooseSave]: picking a version just re-scopes the game detail
     * screen to whichever rom ID the user selects (see [nativeLibraryOnVersionSelected]).
     */
    private fun nativeLibraryOnChooseVersion(romId: Long) {
        Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnChooseVersion: entered romId=$romId")
        versionPickerState = com.romm.androidtv.library.ui.VersionPickerState.Loading
        currentScreen = Screen.NATIVE_VERSION_PICKER

        lifecycleScope.launch {
            when (val result = libraryRepository.fetchRomDetail(romId)) {
                is com.romm.androidtv.library.LibraryResult.Success -> {
                    val rom = result.data
                    val entries = buildList {
                        add(
                            com.romm.androidtv.library.ui.VersionPickerEntryUiModel(
                                romId = rom.id,
                                fileName = rom.fileName.ifBlank { rom.title },
                                isCurrentVersion = true,
                                isMainSibling = rom.siblingRoms.none { it.isMainSibling },
                            ),
                        )
                        rom.siblingRoms.forEach { sibling ->
                            add(
                                com.romm.androidtv.library.ui.VersionPickerEntryUiModel(
                                    romId = sibling.id,
                                    fileName = sibling.fileName.ifBlank { sibling.title },
                                    isCurrentVersion = false,
                                    isMainSibling = sibling.isMainSibling,
                                ),
                            )
                        }
                    }
                    withContext(Dispatchers.Main) {
                        versionPickerState = com.romm.androidtv.library.ui.VersionPickerState.Loaded(
                            com.romm.androidtv.library.ui.VersionPickerUiModel(gameTitle = rom.title, entries = entries)
                        )
                    }
                }
                is com.romm.androidtv.library.LibraryResult.Failure -> {
                    withContext(Dispatchers.Main) {
                        versionPickerState = com.romm.androidtv.library.ui.VersionPickerState.Error(
                            result.error.name.lowercase().replace('_', ' ')
                        )
                    }
                }
            }
        }
    }

    /**
     * Handles the user picking one entry in the version picker: switches the game detail screen
     * to the chosen rom (so Back behaves like an ordinary detail visit) and lets the user press
     * Play there when ready, exactly as if they'd navigated to that version's own detail screen
     * directly — unlike the save picker, choosing a version does NOT auto-launch.
     */
    private fun nativeLibraryOnVersionSelected(entry: com.romm.androidtv.library.ui.VersionPickerEntryUiModel) {
        Log.d(DIAG_TAG, "MainActivity.nativeLibraryOnVersionSelected: romId=${entry.romId}")
        versionPickerState = null
        selectedRomId = entry.romId
        currentScreen = Screen.NATIVE_GAME_DETAIL
    }

    private fun biosStagingError(
        outcome: com.romm.androidtv.romm.FirmwareStagingOutcome,
        systemName: String,
    ): String = when (outcome) {
        com.romm.androidtv.romm.FirmwareStagingOutcome.AuthExpired ->
            "Session expired while downloading the $systemName BIOS."
        is com.romm.androidtv.romm.FirmwareStagingOutcome.InsufficientSpace ->
            "Not enough storage to download the $systemName BIOS."
        is com.romm.androidtv.romm.FirmwareStagingOutcome.CorruptedDownload ->
            "The $systemName BIOS download failed verification."
        is com.romm.androidtv.romm.FirmwareStagingOutcome.Missing ->
            "Missing BIOS files on server. Please contact your RomM administrator."
        is com.romm.androidtv.romm.FirmwareStagingOutcome.NetworkError ->
            "Couldn't download the $systemName BIOS (${outcome.message})."
        is com.romm.androidtv.romm.FirmwareStagingOutcome.Success -> ""
    }

    /**
     * Dispatches a [SaveLaunchOrchestrator.PreparationResult] into UI state or activity launch.
     * This is the shared dispatcher used by both initial preparation and post-reconciliation retry.
     */
    private suspend fun dispatchPreparationResult(
        preparation: SaveLaunchOrchestrator.PreparationResult,
        spec: com.romm.androidtv.emulation.model.LaunchSpec,
        savePath: String,
    ) {
        when (preparation) {
            is SaveLaunchOrchestrator.PreparationResult.Ready -> {
                preLaunchState = null
                launchEmulationActivity(spec, savePath, preparation.candidateMetadata)
            }
            is SaveLaunchOrchestrator.PreparationResult.Conflict -> {
                handleConflictPreparation(preparation, spec)
            }
            is SaveLaunchOrchestrator.PreparationResult.Quarantined -> {
                handleQuarantinePreparation(preparation, spec)
            }
            is SaveLaunchOrchestrator.PreparationResult.AuthExpired -> {
                // Retry path: do NOT recurse into another reconciliation; treat as terminal failure.
                Log.d(DIAG_TAG, "MainActivity.dispatchPrepResult: authExpired terminal")
                withContext(Dispatchers.Main) {
                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId, romHash = spec.romHash)
                        .apply { isAuthExpired = true }
                }
            }
            is SaveLaunchOrchestrator.PreparationResult.Failed -> {
                withContext(Dispatchers.Main) {
                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId, romHash = spec.romHash)
                        .apply { errorMessage = preparation.reason }
                }
            }
        }
    }

    /** Routes a Conflict preparation result into the conflict-resolution overlay. */
    private suspend fun handleConflictPreparation(
        preparation: SaveLaunchOrchestrator.PreparationResult.Conflict,
        spec: com.romm.androidtv.emulation.model.LaunchSpec,
    ) {
        val sess = sessionStore.current()
        val sessUsername = sess?.username
        if (sess != null && sessUsername != null) {
            val sk = extractServerKey(sess.origin)
            val localEntity = saveSyncCoordinator.findSaveReplicaByScope(
                serverKey = sk,
                userKey = sessUsername,
                romId = spec.romId,
                romHash = spec.romHash,
                slot = SavePathPolicy.AUTOSAVE_SLOT,
            )
            if (localEntity != null) {
                val uiModel = com.romm.androidtv.library.ui.ConflictResolutionMapper.mapConflict(localEntity, preparation.operation)
                withContext(Dispatchers.Main) {
                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(
                        romId = spec.romId, sessionId = preparation.sessionId, romHash = spec.romHash,
                    ).apply {
                        conflictModel = uiModel
                        conflictOperation = preparation.operation
                    }
                    selectedRomId = spec.romId
                    currentScreen = Screen.NATIVE_CONFLICT
                }
            } else {
                withContext(Dispatchers.Main) {
                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId, romHash = spec.romHash)
                        .apply { errorMessage = "No local save replica found for ROM ${spec.romId}." }
                }
            }
        } else {
            withContext(Dispatchers.Main) {
                preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId, romHash = spec.romHash)
                    .apply { errorMessage = "No active session; cannot resolve conflict." }
            }
        }
    }

    /** Routes a Quarantined preparation result into the quarantine overlay. */
    private suspend fun handleQuarantinePreparation(
        preparation: SaveLaunchOrchestrator.PreparationResult.Quarantined,
        spec: com.romm.androidtv.emulation.model.LaunchSpec,
    ) {
        val uiModel = com.romm.androidtv.library.ui.ConflictResolutionMapper.mapQuarantine(
            reason = preparation.reason,
            quarantinedPath = preparation.quarantinedPath,
            localEntity = null,
        )
        withContext(Dispatchers.Main) {
            preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(romId = spec.romId, romHash = spec.romHash)
                .apply { quarantineModel = uiModel }
            selectedRomId = spec.romId
            currentScreen = Screen.NATIVE_QUARANTINE
        }
    }

    /**
     * Launch-staged-ROM path for Native Library (conflict/quarantine → overlay).
     * Uses [SaveLaunchOrchestrator] for shared pre-launch preparation logic.
     */
    private fun launchStagedRomNativeLibrary(outcome: com.romm.androidtv.romm.StagingOutcome.Success) {
        val spec = outcome.launchSpec
        val session = sessionStore.current()
        val serverKey = session?.origin?.let { extractServerKey(it) } ?: "unknown-server"
        val userKey = session?.username ?: "unknown-user"
        val savePath = com.romm.androidtv.emulation.model.SavePathPolicy.autosaveSramPath(
            filesDir = filesDir,
            serverKey = serverKey,
            userKey = userKey,
            romId = spec.romId,
            romHash = spec.romHash,
        )

        lifecycleScope.launch {
            val requiredBios = when (spec.platformSlug) {
                "segacd" -> segaCdBiosManager.prepareForLaunch(filesDir.resolve("system")) to "Sega CD"
                "psx" -> psxBiosManager.prepareForLaunch(filesDir.resolve("system")) to "PlayStation"
                else -> null
            }
            if (requiredBios != null) {
                val (firmwareOutcome, biosSystemName) = requiredBios
                if (firmwareOutcome !is com.romm.androidtv.romm.FirmwareStagingOutcome.Success) {
                    withContext(Dispatchers.Main) {
                        preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(
                            romId = spec.romId,
                            romHash = spec.romHash,
                        ).apply {
                            errorMessage = biosStagingError(firmwareOutcome, biosSystemName)
                        }
                    }
                    return@launch
                }
            }

            // Reuse trusted existing replica's expectedSramSizeBytes when available.
            val knownSramSize = session?.let { sess ->
                saveSyncCoordinator.findSaveReplicaByScope(
                    serverKey = extractServerKey(sess.origin),
                    userKey = sess.username ?: "",
                    romId = spec.romId,
                    romHash = spec.romHash,
                    slot = SavePathPolicy.AUTOSAVE_SLOT,
                )?.expectedSramSizeBytes
            }
            val preparation = saveLaunchOrchestrator.prepare(
                romId = spec.romId,
                romHash = spec.romHash,
                coreId = spec.coreId,
                coreBuildRevision = "", // Orchestrator re-resolves from CoreManifest.
                expectedSramSizeBytes = knownSramSize,
                fileName = spec.serverSaveFileName,
            )

            when (preparation) {
                is SaveLaunchOrchestrator.PreparationResult.AuthExpired -> {
                    // Pre-launch recovery: attempt exactly one foreground reconciliation.
                    val sess = sessionStore.current()
                    val origin = sess?.origin ?: currentOrigin
                    val username = sess?.username
                    Log.d(DIAG_TAG, "MainActivity.launchNative: authExpired sessionPresent=${sess != null}")

                    // No coherent session to reconcile — token is missing/undecryptable/unknown
                    // user, or the session was already cleared. Route to Credentials.
                    if (origin.isBlank() || username.isNullOrBlank()) {
                        routeToCredentials(username)
                        return@launch
                    }

                    // Verify the cookie session ONCE to distinguish a definitively-invalid token
                    // from a transient network/TLS failure (spec 5.2.4-5.2.6). Only a definitive
                    // auth failure (VERIFICATION_FAILED) or a failed token re-acquisition against
                    // a valid cookie session means the token is actually revoked/expired, and
                    // only then do we erase the session/token and route to Credentials (5.2.7).
                    val verifyResult = authRepository.verifySession(origin)
                    when (verifyResult) {
                        is AuthFlowResult.Success -> {
                            // Cookie session is valid; the bearer token was stale/revoked.
                            // Force-reconcile to acquire a fresh durable token.
                            val reconciled = authRepository.forceReconcileClientToken(origin, username)
                            Log.d(DIAG_TAG, "MainActivity.launchNative: reconciled=$reconciled")
                            if (reconciled) {
                                // Retry preparation once after successful reconciliation.
                                val retryPreparation = saveLaunchOrchestrator.prepare(
                                    romId = spec.romId,
                                    romHash = spec.romHash,
                                    coreId = spec.coreId,
                                    coreBuildRevision = "",
                                    expectedSramSizeBytes = knownSramSize,
                                    fileName = spec.serverSaveFileName,
                                )
                                // Dispatch retry result (no recursive auth-expired recovery).
                                dispatchPreparationResult(retryPreparation, spec, savePath)
                            } else {
                                // Valid cookie session but token re-acquisition failed: definitive.
                                Log.d(DIAG_TAG, "MainActivity.launchNative: token reconcile failed — routing to Credentials")
                                authRepository.clearExpiredSession(origin, username)
                                routeToCredentials(username)
                            }
                        }
                        is AuthFlowResult.Failure -> {
                            if (verifyResult.error == com.romm.androidtv.network.AuthError.VERIFICATION_FAILED) {
                                // Definitive: token AND cookie session are both invalid.
                                Log.d(DIAG_TAG, "MainActivity.launchNative: verify failed — routing to Credentials")
                                authRepository.clearExpiredSession(origin, username)
                                routeToCredentials(username)
                            } else {
                                // Transient network/TLS failure: preserve session + token, stay in Main.
                                Log.d(DIAG_TAG, "MainActivity.launchNative: transient verify failure — preserving session/token")
                                withContext(Dispatchers.Main) {
                                    preLaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(
                                        romId = spec.romId,
                                        romHash = spec.romHash,
                                    ).apply { isAuthExpired = true }
                                }
                            }
                        }
                    }
                }

                else -> {
                    // All non-AuthExpired results (Ready, Conflict, Quarantined, Failed) are dispatched normally.
                    dispatchPreparationResult(preparation, spec, savePath)
                }
            }
        }
    }

    // ---- Pre-launch overlay rendering (conflict / quarantine / error) ----

    @Composable
    private fun renderPreLaunchOverlay(state: com.romm.androidtv.library.ui.SavePreLaunchState) {
        val session = sessionStore.current()
        val username = session?.username ?: "unknown"

        if (state.conflictModel != null) {
            val actions = createConflictPresentationActions(state, username)
            com.romm.androidtv.library.ui.RommTvTheme {
                com.romm.androidtv.library.ui.SaveConflictScreen(
                    model = state.conflictModel!!,
                    actions = actions,
                )
            }
        } else if (state.quarantineModel != null) {
            val actions = object : com.romm.androidtv.library.ui.QuarantinePresentationAction {
                override fun dismiss() {
                    // Non-mutating: returns to game detail without filesystem/Room/network mutation.
                    state.clear()
                    preLaunchState = null
                    currentScreen = Screen.NATIVE_GAME_DETAIL
                }
            }
            com.romm.androidtv.library.ui.RommTvTheme {
                com.romm.androidtv.library.ui.SaveQuarantineScreen(
                    model = state.quarantineModel!!,
                    actions = actions,
                )
            }
        } else if (state.errorMessage != null) {
            // Safety fallback: error-only overlay (normally rendered inline in GameDetailScreen).
            com.romm.androidtv.library.ui.RommTvTheme {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                ) {
                    androidx.compose.material3.Text(
                        text = "Launch Blocked",
                        style = androidx.compose.material3.MaterialTheme.typography.headlineSmall,
                        color = com.romm.androidtv.library.ui.RommTvColors.TextPrimary,
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.Text(
                        text = state.errorMessage!!,
                        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFf44336),
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(24.dp))
                    androidx.compose.material3.TextButton(onClick = {
                        state.clear()
                        preLaunchState = null
                        currentScreen = Screen.NATIVE_GAME_DETAIL
                    }) {
                        androidx.compose.material3.Text("Go Back", color = com.romm.androidtv.library.ui.RommTvColors.Romm300)
                    }
                }
            }
        }
    }

    /**
     * Creates a [ConflictPresentationAction] that delegates to the coordinator's
     * conflict resolution via its internal interface. Guards duplicate submissions
     * via [SavePreLaunchState.isResolving]. On success, proceeds to native launch.
     * On failure, stays on screen showing actionable error.
     */
    private fun createConflictPresentationActions(
        state: com.romm.androidtv.library.ui.SavePreLaunchState,
        username: String,
    ): com.romm.androidtv.library.ui.ConflictPresentationAction {
        return object : com.romm.androidtv.library.ui.ConflictPresentationAction {
            override fun keepLocal() {
                if (state.isResolving) return // Duplicate submission guard
                state.isResolving = true
                lifecycleScope.launch {
                    resolveConflictAndRelaunch(
                        state, ConflictChoice.KEEP_LOCAL, username,
                    )
                }
            }

            override fun keepServer() {
                if (state.isResolving) return // Duplicate submission guard
                state.isResolving = true
                lifecycleScope.launch {
                    resolveConflictAndRelaunch(
                        state, ConflictChoice.KEEP_SERVER, username,
                    )
                }
            }

            override fun cancel() {
                // Non-mutating: returns to game detail without filesystem/Room/network mutation and without launch.
                state.clear()
                preLaunchState = null
                currentScreen = Screen.NATIVE_GAME_DETAIL
            }
        }
    }

    /**
     * Shared conflict resolution + relaunch logic. Delegates to the coordinator's
     * internal [SaveSyncCoordinatorInternal.resolveConflict] which owns all DAO/store access.
     */
    private suspend fun resolveConflictAndRelaunch(
        state: com.romm.androidtv.library.ui.SavePreLaunchState,
        choice: ConflictChoice,
        username: String,
    ) {
        try {
            val session = sessionStore.current() ?: run {
                withContext(Dispatchers.Main) {
                    state.errorMessage = "No active session"
                    state.isResolving = false
                }
                return
            }

            val conflictModel = state.conflictModel ?: run {
                withContext(Dispatchers.Main) {
                    state.errorMessage = "No conflict model available for resolution."
                    state.isResolving = false
                }
                return
            }

            val result = (saveSyncCoordinator as com.romm.androidtv.romm.save.SaveSyncCoordinatorInternal).resolveConflict(
                ResolveConflictRequest(
                    sessionId = state.sessionId ?: 0,
                    serverOrigin = session.origin,
                    username = username,
                    romId = state.romId,
                    romHash = state.romHash,
                    slot = SavePathPolicy.AUTOSAVE_SLOT,
                    choice = choice,
                    operation = state.conflictOperation,
                    serverSaveId = conflictModel.server.saveId,
                    fileName = conflictModel.server.fileName,
                    serverSlot = conflictModel.server.slot,
                    serverEmulator = conflictModel.server.coreId,
                    reason = conflictModel.description.ifBlank { "both changed since last sync" },
                )
            )

            if (result is com.romm.androidtv.romm.save.ConflictResolutionResult.Success) {
                val relaunchState = com.romm.androidtv.library.ui.SavePreLaunchState(
                    romId = state.romId,
                    romHash = state.romHash,
                ).apply { isStaging = true }
                withContext(Dispatchers.Main) {
                    state.clear()
                    preLaunchState = relaunchState
                    currentScreen = Screen.NATIVE_GAME_DETAIL
                }
                lifecycleScope.launch {
                    val relaunchOutcome = romRepository.stageForLaunch(state.romId)
                    if (relaunchOutcome is com.romm.androidtv.romm.StagingOutcome.Success) {
                        launchStagedRomNativeLibrary(relaunchOutcome)
                    } else {
                        withContext(Dispatchers.Main) {
                            relaunchState.isStaging = false
                            relaunchState.errorMessage =
                                "Relaunch failed: ${StagingOutcomeMessage.toUserMessage(relaunchOutcome)}"
                        }
                    }
                }
            } else if (result is com.romm.androidtv.romm.save.ConflictResolutionResult.Failure) {
                withContext(Dispatchers.Main) {
                    state.errorMessage = "Resolution failed: ${result.reason}"
                    state.isResolving = false
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                state.errorMessage = "Resolution error: ${e.message ?: "unknown"}"
                state.isResolving = false
            }
        }
    }

    // ---- Event dispatch: controller routing at Activity boundary ----

    /**
     * Policy:
     * - Android Back is ALWAYS reserved for native overlay (never consumed by WebView).
     * - All screens are native Compose UI, so D-pad/buttons always go directly to native
     *   UI here; the controller router is only consulted for capture (controller
     *   configuration) and physical-activity bookkeeping, never to consume events.
     */
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Android Back is always reserved for native handling
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            controllerRouter.recordPhysicalInputActivity(event.deviceId)
        }

        // Phase 4: capture raw input before all normal routing. Returns non-null
        // (consume) only while a capture is active; null when Idle lets the
        // existing per-screen routing below run completely unchanged.
        captureCoordinator.onKeyEvent(event)?.let { return it }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Phase 4: capture raw input before all normal routing (see dispatchKeyEvent).
        captureCoordinator.onMotionEvent(event)?.let { return it }

        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        // Phase 4: capture must not survive activity destroy (spec rule 8).
        captureCoordinator.cancel()
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        try {
            inputManager.unregisterInputDeviceListener(controllerRouter)
            inputManager.unregisterInputDeviceListener(captureCoordinator)
        } catch (_: Exception) {
            // Already unregistered or activity finishing
        }
        super.onDestroy()
    }
}


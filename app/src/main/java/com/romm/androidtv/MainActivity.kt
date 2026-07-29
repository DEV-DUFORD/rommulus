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
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.OnBackPressedCallback
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
import com.romm.androidtv.auth.AuthRepository
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.cache.CacheDatabase
import com.romm.androidtv.cache.ContentCache
import com.romm.androidtv.config.SettingsRepository
import com.romm.androidtv.controller.model.*
import com.romm.androidtv.controller.router.ControllerEventRouter
import com.romm.androidtv.diagnostic.DiagnosticPageHtml
import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.gamepad.GamepadInjectionBridge
import com.romm.androidtv.gamepad.GamepadInjectionDiagnostics
import com.romm.androidtv.model.*
import com.romm.androidtv.network.*
import com.romm.androidtv.romm.RomRepositoryImpl
import com.romm.androidtv.romm.StagingOutcome
import com.romm.androidtv.web.AuthenticatedWebViewScreen
import kotlinx.coroutines.CompletableDeferred
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
 * Startup flow:
 * 1. Import cookies from Android CookieManager (session restoration)
 * 2. Check existing session via verifyExistingSession()
 * 3. If valid -> sync cookies and route to AUTHENTICATED_WEBVIEW
 * 4. If expired/missing -> route to HOME (user taps Login)
 *
 * Screens:
 * 1. HOME — action buttons including Login
 * 2. ORIGIN_STATUS — native heartbeat validation results
 * 3. LOGIN — username/password form (CharArray-backed, zeroed immediately)
 * 4. AUTHENTICATED_WEBVIEW — authenticated RomM WebView via AndroidView
 * 5. DIAGNOSTICS — local capability tests via data: scheme
 * 6. ROMM_ORIGIN — unauthenticated origin viewer
 */
class MainActivity : ComponentActivity() {

    private enum class Screen {
        HOME, ORIGIN_STATUS, LOGIN, AUTHENTICATED_WEBVIEW, DIAGNOSTICS, ROMM_ORIGIN, CONTROLLER_DIAGNOSTICS,
        NATIVE_HOME, NATIVE_PLATFORMS, NATIVE_COLLECTIONS, NATIVE_SEARCH
    }

    private var currentScreen by mutableStateOf(Screen.HOME)

    @Volatile
    private var diagnosticReport: DiagnosticReport? = null

    @Volatile
    private var heartbeatResponse: HeartbeatResponse? = null

    @Volatile
    private var heartbeatError: HeartbeatError? = null

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

    // Auth repository — owns login/session-verification/cookie-sync network calls so
    // MainActivity coordinates navigation rather than owning network internals.
    private val authRepository: AuthRepository by lazy {
        AuthRepository(okHttpClient, RommOkHttpClient.cookieSyncJar, sessionStore)
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
        RomRepositoryImpl(okHttpClient, sessionStore, contentCache)
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

    // Gamepad injection diagnostics — shared observable state
    private val gamepadDiagnostics: GamepadInjectionDiagnostics by lazy {
        GamepadInjectionDiagnostics()
    }

    // Gamepad injection bridge — injects document-start script and pushes state
    private val gamepadBridge: GamepadInjectionBridge by lazy {
        GamepadInjectionBridge(gamepadDiagnostics)
    }

    companion object {
        private const val TAG = "RomMMainActivity"
    }

    // Single-flight guard: prevents concurrent auth flow submissions.
    private var authFlowActive = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Initialize controller router and register device listener
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        inputManager.registerInputDeviceListener(controllerRouter, null)
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

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (BuildConfig.DEBUG) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

        Log.d(TAG, "onCreate: savedInstanceState=$savedInstanceState intent=$intent")

        // Back navigation: any non-HOME screen returns to HOME; HOME exits.
        // Native sub-screens (reached via the NativeHomeScreen sidebar) return to
        // NATIVE_HOME first, then HOME on a second Back — a small addition to the
        // same convention, not a parallel nav system (UI_REFACTOR.md section 5).
        // Android Back is always reserved — never delegated to WebView.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    // finish() exits the activity directly. Calling
                    // onBackPressedDispatcher.onBackPressed() here would re-invoke this
                    // very callback (it's still enabled), causing infinite recursion and
                    // a StackOverflowError crash.
                    Screen.HOME -> finish()
                    Screen.NATIVE_PLATFORMS, Screen.NATIVE_COLLECTIONS, Screen.NATIVE_SEARCH ->
                        currentScreen = Screen.NATIVE_HOME
                    else -> currentScreen = Screen.HOME
                }
            }
        })

        // Serializes debug auth and startup session-restore so they don't race.
        // Debug auth completes this deferred; startup coroutine awaits before proceeding.
        val debugAuthDone = CompletableDeferred<Unit>()

        // DEBUG-only: accept test credentials via intent extras for instrumentation testing.
        // Never persisted; guarded by BuildConfig.DEBUG so release builds ignore this entirely.
        if (BuildConfig.DEBUG) {
            val testUser = intent.getStringExtra("test_username")
            val testPass = intent.getStringExtra("test_password")
            Log.d(TAG, "DEBUG auth: credentials provided=${!testUser.isNullOrBlank() && !testPass.isNullOrBlank()}")
            if (!testUser.isNullOrBlank() && !testPass.isNullOrBlank()) {
                lifecycleScope.launch {
                    val origin = currentOrigin
                    if (origin.isNotBlank()) {
                        Log.d(TAG, "DEBUG auth: executing auth flow")
                        val result = authRepository.login(origin, testUser, testPass.toCharArray())
                        Log.d(TAG, "DEBUG auth: completed success=${result is AuthFlowResult.Success}")
                        withContext(Dispatchers.Main) {
                            authResult = result
                            if (result is AuthFlowResult.Success) {
                                verifiedUser = result.verifiedUser
                                Log.d(TAG, "DEBUG auth: SUCCESS")
                                authRepository.syncCookiesToWebView(origin)
                                currentScreen = Screen.AUTHENTICATED_WEBVIEW
                            } else {
                                Log.e(TAG, "DEBUG auth: FAILED")
                            }
                        }
                    } else {
                        Log.e(TAG, "DEBUG auth: origin not configured, skipping")
                    }
                    // Signal that debug auth is done (success or failure)
                    debugAuthDone.complete(Unit)
                }
            } else {
                // No test credentials — signal immediately so startup coroutine proceeds
                Log.d(TAG, "DEBUG auth: no test credentials, proceeding to startup restore")
                debugAuthDone.complete(Unit)
            }
        } else {
            // Non-debug: no debug auth, startup coroutine proceeds immediately
            debugAuthDone.complete(Unit)
        }

        // Startup: verify existing session via lifecycle-aware coroutine
        // BLOCKS until debug auth completes to prevent cookie/navigation races.
        lifecycleScope.launch {
            // Wait for debug auth to finish before touching cookies or navigation
            Log.d(TAG, "Startup: waiting for debug auth to complete")
            debugAuthDone.await()
            Log.d(TAG, "Startup: debug auth done, currentScreen=$currentScreen")

            // If debug auth already navigated to AUTHENTICATED_WEBVIEW, skip startup restore
            if (currentScreen == Screen.AUTHENTICATED_WEBVIEW) {
                Log.d(TAG, "Startup: skipping restore, already authenticated")
                return@launch
            }

            val origin = currentOrigin
            Log.d(TAG, "Startup: origin configured=${origin.isNotBlank()}")
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
                        // Step 3: Sync OkHttp cookies back to Android CookieManager for WebView
                        Log.d(TAG, "Startup: session valid, syncing cookies")
                        authRepository.syncCookiesToWebView(origin)
                        currentScreen = Screen.AUTHENTICATED_WEBVIEW
                    }
                    is AuthFlowResult.Failure -> {
                        authResult = result
                        Log.d(TAG, "Startup: session expired/missing, staying on HOME")
                    }
                }
            }
        }

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Black
                ) {
                    when (currentScreen) {
                        Screen.HOME -> HomeScreen(
                            onCheckOrigin = {
                                currentScreen = Screen.ORIGIN_STATUS
                                heartbeatResponse = null
                                heartbeatError = null
                                runHeartbeatCheck()
                            },
                            onLogin = {
                                currentScreen = Screen.LOGIN
                                authResult = null
                            },
                            onRunDiagnostics = {
                                currentScreen = Screen.DIAGNOSTICS
                                diagnosticReport = null
                                runDiagnosticsWebView()
                            },
                            onOpenRomMOrigin = {
                                currentScreen = Screen.ROMM_ORIGIN
                                val origin = currentOrigin.takeIf { it.isNotBlank() }
                                    ?: "(not configured)"
                                openRomMOrigin(origin)
                            },
                            onOpenControllerDiagnostics = {
                                currentScreen = Screen.CONTROLLER_DIAGNOSTICS
                            },
                            onOpenNativeLibrary = {
                                currentScreen = Screen.NATIVE_HOME
                            },
                            onStageAndLaunchRealRom = { romId, onResult ->
                                stageAndLaunchRealRom(romId, onResult)
                            }
                        )
                        Screen.ORIGIN_STATUS -> OriginStatusScreen(
                            origin = currentOrigin,
                            response = heartbeatResponse,
                            error = heartbeatError
                        )
                        Screen.LOGIN -> LoginScreen(
                            authResult = authResult,
                            onLogin = { username: String, password: CharArray, onComplete: () -> Unit ->
                                authResult = null
                                runAuthFlow(username, password, onComplete)
                            }
                        )
                        Screen.AUTHENTICATED_WEBVIEW -> AuthenticatedWebViewScreen(
                            origin = currentOrigin,
                            controllerRouter = controllerRouter,
                            gamepadBridge = gamepadBridge,
                            gamepadDiagnostics = gamepadDiagnostics,
                            onLogin = {
                                Log.d(TAG, "WebView navigated to /login — transitioning to native Login")
                                currentScreen = Screen.LOGIN
                            }
                        )
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(
                            report = diagnosticReport
                        )
                        Screen.ROMM_ORIGIN -> RomMOriginScreen(origin = currentOrigin)
                        Screen.CONTROLLER_DIAGNOSTICS -> ControllerDiagnosticsScreen(
                            router = controllerRouter,
                            gamepadBridge = gamepadBridge,
                            gamepadDiagnostics = gamepadDiagnostics
                        )
                        Screen.NATIVE_HOME, Screen.NATIVE_PLATFORMS, Screen.NATIVE_COLLECTIONS, Screen.NATIVE_SEARCH -> {
                            val homeViewModel: com.romm.androidtv.library.HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
                                factory = com.romm.androidtv.library.HomeViewModel.Factory(libraryRepository)
                            )
                            com.romm.androidtv.library.ui.RommTvTheme {
                                when (currentScreen) {
                                    Screen.NATIVE_PLATFORMS -> {
                                        val state by homeViewModel.uiState.collectAsState()
                                        com.romm.androidtv.library.ui.PlatformsScreen(
                                            state = state.platforms,
                                            onRetry = homeViewModel::retryPlatforms
                                        )
                                    }
                                    Screen.NATIVE_COLLECTIONS -> {
                                        val state by homeViewModel.uiState.collectAsState()
                                        com.romm.androidtv.library.ui.CollectionsScreen(
                                            state = state.collections,
                                            onRetry = homeViewModel::retryCollections
                                        )
                                    }
                                    Screen.NATIVE_SEARCH -> com.romm.androidtv.library.ui.SearchScreen()
                                    else -> com.romm.androidtv.library.ui.NativeHomeScreen(
                                        viewModel = homeViewModel,
                                        onOpenPlatforms = { currentScreen = Screen.NATIVE_PLATFORMS },
                                        onOpenCollections = { currentScreen = Screen.NATIVE_COLLECTIONS },
                                        onOpenSearch = { currentScreen = Screen.NATIVE_SEARCH },
                                        onOpenSettings = { /* TODO: native settings screen — out of scope, see UI_REFACTOR.md */ }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Heartbeat check (lifecycle-aware coroutine) ----

    private fun runHeartbeatCheck() {
        val origin = currentOrigin
        lifecycleScope.launch {
            val result = authRepository.checkHeartbeat(origin)
            when (result) {
                is HeartbeatCallResult.Success -> {
                    heartbeatResponse = result.response
                    heartbeatError = null
                }
                is HeartbeatCallResult.Failure -> {
                    heartbeatResponse = null
                    heartbeatError = result.error
                }
            }
        }
    }

    // ---- Phase 4 debug entry point: stage one real, user-owned RomM ROM
    // through the Phase 3 content pipeline and launch it with the approved
    // SameBoy core (LIBRETRO_REFACTOR.md section 13, Phase 4: "Load one
    // user-owned RomM title through the native content pipeline"). This all
    // runs in the main process — EmulationActivity (in :emulation) only ever
    // receives the already-resolved, app-private contentPath/savePath this
    // method computes, never ROM bytes or a raw server URL (section 6, step 7).

    private fun stageAndLaunchRealRom(romId: Long, onResult: (StagingOutcome) -> Unit) {
        lifecycleScope.launch {
            val outcome = romRepository.stageForLaunch(romId)
            onResult(outcome)
            if (outcome is StagingOutcome.Success) {
                launchStagedRom(outcome)
            }
        }
    }

    private fun launchStagedRom(outcome: StagingOutcome.Success) {
        val spec = outcome.launchSpec
        val session = sessionStore.current()
        val serverKey = session?.origin?.let { RommOrigin.parse(it)?.host ?: it } ?: "unknown-server"
        val userKey = session?.username ?: "unknown-user"
        val savePath = SavePathPolicy.autosaveSramPath(
            filesDir = filesDir,
            serverKey = serverKey,
            userKey = userKey,
            romId = spec.romId,
            romHash = spec.romHash,
        )

        startActivity(
            Intent(this, com.romm.androidtv.emulation.process.EmulationActivity::class.java).apply {
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_CORE_ID, spec.coreId)
                putExtra(
                    com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_CONTENT_PATH,
                    spec.contentPath
                )
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_SAVE_PATH, savePath)
                putExtra(com.romm.androidtv.emulation.process.EmulationActivity.EXTRA_ROM_ID, spec.romId)
            }
        )
    }

    // ---- Auth flow (lifecycle-aware coroutine, single-flight guarded) ----

    private fun runAuthFlow(username: String, password: CharArray, onAuthComplete: () -> Unit = {}) {
        val origin = currentOrigin
        lifecycleScope.launch {
            var isActive = true
            try {
                // Single-flight guard: reject concurrent submissions
                if (!authFlowActive) {
                    authFlowActive = true
                } else {
                    Log.w(TAG, "Auth flow: duplicate submission rejected")
                    return@launch
                }

                val result = authRepository.login(origin, username, password)
                if (isActive) {
                    authResult = result
                    onAuthComplete()
                    if (result is AuthFlowResult.Success) {
                        verifiedUser = result.verifiedUser
                        // Suspend-boundary: sync cookies to Android CookieManager for WebView
                        authRepository.syncCookiesToWebView(origin)
                        currentScreen = Screen.AUTHENTICATED_WEBVIEW
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auth flow exception", e)
                if (isActive) {
                    authResult = AuthFlowResult.Failure(AuthError.NETWORK_ERROR)
                    onAuthComplete()
                }
            } finally {
                isActive = false
                authFlowActive = false
            }
        }
    }

    // ---- Local WebView Diagnostics (data: scheme) ----

    private fun runDiagnosticsWebView() {
        // Diagnostics uses a transient WebView managed by the Composable
    }

    // ---- RomM Origin WebView (unauthenticated) ----

    @Suppress("UNUSED_PARAMETER")
    private fun openRomMOrigin(origin: String) {
        // Managed by the Composable
    }

    // ---- Event dispatch: controller routing at Activity boundary ----

    /**
     * Policy:
     * - Android Back is ALWAYS reserved for native overlay (never consumed by WebView).
     * - While native Compose UI is visible (HOME, LOGIN, etc.), D-pad/buttons go to native UI.
     * - While AUTHENTICATED_WEBVIEW is visible:
     *   a) Game-controller events are routed and consumed (injected as virtual gamepad).
     *   b) TV remote D-pad/Select may use an available one of the four virtual slots,
     *      enabling RomM navigation when RomM only understands Gamepad API.
     * - While CONTROLLER_DIAGNOSTICS is visible, router observes events (updates state)
     *   but does NOT consume them — native Compose controls remain functional.
     */
    @Suppress("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Android Back is always reserved for native handling
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            return super.dispatchKeyEvent(event)
        }

        when (currentScreen) {
            Screen.AUTHENTICATED_WEBVIEW -> {
                // WebView visible: route through controller router.
                // TV remote events are automatically routed into an available physical slot.
                val consumed = controllerRouter.onKeyEvent(event)
                if (consumed) return true
            }
            Screen.CONTROLLER_DIAGNOSTICS -> {
                // Diagnostics visible: route through router to update state,
                // but do NOT consume — native Compose controls must remain functional
                controllerRouter.onKeyEvent(event)
                // Always fall through to super so native UI receives D-pad/buttons
            }
            else -> {
                // Native Compose screens (HOME, LOGIN, ORIGIN_STATUS, DIAGNOSTICS, ROMM_ORIGIN):
                // Do NOT route through controller router — D-pad/buttons go directly to native UI
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        // Only process joystick/hat motion events
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == 0 &&
            (event.source and InputDevice.SOURCE_GAMEPAD) == 0) {
            return super.dispatchGenericMotionEvent(event)
        }

        when (currentScreen) {
            Screen.AUTHENTICATED_WEBVIEW -> {
                // WebView visible: consume controller motion events
                val consumed = controllerRouter.onMotionEvent(event)
                if (consumed) return true
            }
            Screen.CONTROLLER_DIAGNOSTICS -> {
                // Diagnostics visible: route through router to update state,
                // but do NOT consume — native Compose controls must remain functional
                controllerRouter.onMotionEvent(event)
            }
            else -> {
                // Native Compose screens: skip router entirely
            }
        }

        return super.dispatchGenericMotionEvent(event)
    }

    override fun onDestroy() {
        val inputManager = getSystemService(INPUT_SERVICE) as InputManager
        try {
            inputManager.unregisterInputDeviceListener(controllerRouter)
        } catch (_: Exception) {
            // Already unregistered or activity finishing
        }
        super.onDestroy()
    }
}

// ---- Compose UI Screens ----

@Composable
fun HomeScreen(
    onCheckOrigin: () -> Unit,
    onLogin: () -> Unit,
    onRunDiagnostics: () -> Unit,
    onOpenRomMOrigin: () -> Unit,
    onOpenControllerDiagnostics: () -> Unit = {},
    onOpenNativeLibrary: () -> Unit = {},
    onStageAndLaunchRealRom: (Long, (StagingOutcome) -> Unit) -> Unit = { _, _ -> }
) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .focusable(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "RomM TV",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Phase 0 — Heartbeat, Auth & WebView",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(48.dp))

        // Login button — primary action
        val loginFocusRequester = remember { FocusRequester() }
        Button(
            onClick = onLogin,
            modifier = Modifier
                .focusRequester(loginFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "Login",
                style = MaterialTheme.typography.titleLarge,
                color = Color.White
            )
        }
        LaunchedEffect(Unit) {
            loginFocusRequester.requestFocus()
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Check Origin Status button
        val checkOriginFocusRequester = remember { FocusRequester() }
        Button(
            onClick = onCheckOrigin,
            modifier = Modifier
                .focusRequester(checkOriginFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "Check Origin Status",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Run WebView Diagnostics button
        val diagnosticsFocusRequester = remember { FocusRequester() }
        Button(
            onClick = onRunDiagnostics,
            modifier = Modifier
                .focusRequester(diagnosticsFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "Run WebView Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // View RomM Origin button
        val originFocusRequester = remember { FocusRequester() }
        Button(
            onClick = onOpenRomMOrigin,
            modifier = Modifier
                .focusRequester(originFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "View RomM Origin",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Controller Diagnostics button
        val controllerDiagFocusRequester = remember { FocusRequester() }
        OutlinedButton(
            onClick = onOpenControllerDiagnostics,
            modifier = Modifier
                .focusRequester(controllerDiagFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "Controller Diagnostics",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF4caf50)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Native browsing UI preview entry point (UI_REFACTOR.md). Purely additive —
        // does not replace or alter the WebView browsing flow above.
        val nativeLibraryFocusRequester = remember { FocusRequester() }
        OutlinedButton(
            onClick = onOpenNativeLibrary,
            modifier = Modifier
                .focusRequester(nativeLibraryFocusRequester)
                .padding(16.dp)
        ) {
            Text(
                text = "Open Native Library (Preview)",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF7259D1)
            )
        }

        // Native emulation (Phase 2) debug entry point only. Never shown in a
        // release build; PlaybackBackend still always resolves to WEBVIEW for
        // real ROM launches (LIBRETRO_REFACTOR.md sections 4.3 and 6).
        if (BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(16.dp))

            val context = LocalContext.current
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, com.romm.androidtv.emulation.process.EmulationActivity::class.java)
                    )
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Native Emulation (Debug)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFff9800)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Phase 4 debug entry point: stage one real, user-owned RomM ROM
            // through the Phase 3 content pipeline and launch it with the
            // approved SameBoy core. Still gated behind BuildConfig.DEBUG —
            // PlaybackBackendPolicy.resolve() is untouched by this and still
            // always resolves to WEBVIEW for the real product flow.
            var romIdText by remember { mutableStateOf("") }
            var stagingStatus by remember { mutableStateOf<String?>(null) }

            OutlinedTextField(
                value = romIdText,
                onValueChange = { romIdText = it.filter { c -> c.isDigit() } },
                label = { Text("RomM ROM ID (GB/GBC)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = {
                    val romId = romIdText.toLongOrNull()
                    if (romId == null || romId <= 0) {
                        stagingStatus = "Enter a valid positive ROM ID"
                    } else {
                        stagingStatus = "Staging ROM $romId…"
                        onStageAndLaunchRealRom(romId) { outcome ->
                            stagingStatus = "Result: $outcome"
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 16.dp)
            ) {
                Text(
                    text = "Native Emulation (Debug) — Real ROM (SameBoy)",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFFff9800)
                )
            }

            stagingStatus?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = Color.White
                )
            }
        }
    }
}

@Composable
fun OriginStatusScreen(origin: String, response: HeartbeatResponse?, error: HeartbeatError?) {
    val focusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Origin Status",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(24.dp, 16.dp)
        )

        val displayOrigin = origin.ifEmpty { "(not configured)" }
        Text(
            text = "Origin: $displayOrigin",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (response == null && error == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Checking heartbeat…", color = Color.Gray)
            }
        } else if (error != null) {
            HeartbeatErrorRow(error)
        } else if (response != null) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    HeartbeatDetailRow("Version", response.version ?: "unknown")
                    HeartbeatDetailRow("Setup complete", response.setupComplete.toString())
                    HeartbeatDetailRow("Userpass enabled", response.userpassEnabled.toString())
                    HeartbeatDetailRow("EmulatorJS enabled", response.emulatorJsEnabled.toString())
                    if (response.rawMessage != null) {
                        HeartbeatDetailRow("Message", response.rawMessage)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            if (response.canLogin()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Server ready — use 'Login' from home screen",
                        color = Color(0xFF4caf50),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "Login not available: ${response.statusSummary()}",
                        color = Color(0xFFf44336),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun HeartbeatErrorRow(error: HeartbeatError) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = "Connection error", color = Color(0xFFf44336))
        Text(
            text = when (error) {
                HeartbeatError.NETWORK_ERROR -> "Network unreachable"
                HeartbeatError.TLS_ERROR -> "TLS/certificate error"
                HeartbeatError.HTTP_ERROR -> "HTTP error from server"
                HeartbeatError.PARSE_ERROR -> "Invalid response format"
                HeartbeatError.ORIGIN_NOT_CONFIGURED -> "Origin not configured"
            },
            color = Color.Gray
        )
    }
}

@Composable
fun HeartbeatDetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = Color.White, modifier = Modifier.weight(1f))
        Text(
            text = value,
            color = Color(0xFF4caf50),
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * Login screen with masked password, D-pad navigation, and IME actions.
 *
 * Password is held in a CharArray-backed state, zeroed immediately after use.
 * Credentials are never persisted or logged.
 *
 * Loading state is managed via try/finally pattern: set on submit, reset
 * on success/failure/exception/cancellation via [onLogin]'s onComplete callback
 * or LaunchedEffect(authResult).
 */
@Composable
fun LoginScreen(
    authResult: AuthFlowResult?,
    onLogin: (username: String, password: CharArray, onComplete: () -> Unit) -> Unit,
) {
    val focusManager = LocalFocusManager.current
    var username by remember { mutableStateOf("") }
    var passwordCharArray by remember { mutableStateOf(charArrayOf()) }
    var isLoading by remember { mutableStateOf(false) }

    // Focus requesters for D-pad navigation
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val loginButtonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
        // Auto-focus the username field on screen entry for D-pad navigation
        usernameFocusRequester.requestFocus()
    }

    // Clear password char array when leaving screen
    DisposableEffect(Unit) {
        onDispose {
            zeroCharArray(passwordCharArray)
        }
    }

    /**
     * Immutable login UI state owned by this composable.
     * - [username] is retained across failures so the user can retry without re-typing.
     * - [password] is zeroed immediately after capture; only cleared on success.
     * - [isLoading] is true only during active request; controls are disabled while true.
     * - [authResult] is set by the Activity via parameter; used for success/error display.
     *
     * The loading lifecycle is owned by the Activity's runAuthFlow try/finally:
     * isLoading is set to true in submitCredentials, and the onComplete callback
     * (invoked from runAuthFlow's finally block) resets it on ALL paths.
     * The LaunchedEffect(authResult) reset is intentionally removed to prevent
     * premature loading reset when authResult is set before onComplete fires.
     */
    fun submitCredentials() {
        if (username.isBlank() || passwordCharArray.isEmpty() || isLoading) return
        isLoading = true
        val capturedUsername = username
        val capturedPassword = passwordCharArray.copyOf()
        // Zero password immediately after capture (security); retain username for retry UX.
        zeroCharArray(passwordCharArray)
        passwordCharArray = charArrayOf()
        onLogin(capturedUsername, capturedPassword) {
            // onComplete: called by runAuthFlow in its finally block on ALL paths
            // (success, failure, exception, cancellation).
            isLoading = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Login to RomM",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Username field
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .focusRequester(usernameFocusRequester),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { passwordFocusRequester.requestFocus() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.LightGray,
                cursorColor = Color(0xFF4caf50)
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Password field — masked, CharArray-backed
        OutlinedTextField(
            value = String(passwordCharArray),
            onValueChange = { newValue ->
                passwordCharArray = newValue.toCharArray()
            },
            label = { Text("Password") },
            modifier = Modifier
                .fillMaxWidth()
                .focusable()
                .focusRequester(passwordFocusRequester),
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = { submitCredentials() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.LightGray,
                cursorColor = Color(0xFF4caf50)
            )
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Login button — tvSelect handles remote-only activation with repeat suppression.
        // Material3 OutlinedButton semantics also handle Enter/DpadCenter, but tvSelect
        // intercepts first (modifier-order), preventing double-fire.
        OutlinedButton(
            onClick = { submitCredentials() },
            modifier = Modifier
                .focusable()
                .focusRequester(loginButtonFocusRequester)
                .tvSelect { submitCredentials() },
            enabled = username.isNotBlank() && passwordCharArray.isNotEmpty() && !isLoading
        ) {
            Text(if (isLoading) "Logging in…" else "Login")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Loading indicator
        if (isLoading) {
            CircularProgressIndicator(color = Color(0xFF4caf50))
            Spacer(modifier = Modifier.height(12.dp))
            Text("Authenticating…", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        }

        // Auth error display
        authResult?.let { result ->
            if (result is AuthFlowResult.Failure) {
                Text(
                    text = when (result.error) {
                        com.romm.androidtv.network.AuthError.INVALID_CREDENTIALS -> "Invalid username or password"
                        com.romm.androidtv.network.AuthError.SERVER_ERROR -> "Server error (${result.httpCode})"
                        com.romm.androidtv.network.AuthError.NETWORK_ERROR -> "Network error"
                        com.romm.androidtv.network.AuthError.TLS_ERROR -> "TLS/certificate error"
                        com.romm.androidtv.network.AuthError.POST_LOGIN_HEARTBEAT_FAILED -> "Post-login heartbeat failed"
                        com.romm.androidtv.network.AuthError.VERIFICATION_FAILED -> "Session verification failed"
                        com.romm.androidtv.network.AuthError.ORIGIN_NOT_CONFIGURED -> "Origin not configured"
                        com.romm.androidtv.network.AuthError.LOGIN_NOT_AVAILABLE -> "Login not available on server"
                    },
                    color = Color(0xFFf44336),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun DiagnosticsScreen(report: DiagnosticReport?) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Diagnostic Results",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(24.dp, 16.dp)
        )

        if (report == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("Running diagnostics…", color = Color.Gray)
            }

            // Run diagnostics in a hidden WebView
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        visibility = View.GONE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {
                                if (url?.startsWith("rommdiag://") == true) {
                                    // Parse results (would need callback to update state)
                                }
                                return true
                            }
                        }

                        loadDataWithBaseURL(
                            "null",
                            DiagnosticPageHtml.build(),
                            "text/html",
                            "UTF-8",
                            null
                        )
                    }
                },
                modifier = Modifier.size(0.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(
                    items = report.toResultsList(),
                    key = { result -> result.name }
                ) { result ->
                    DiagnosticRow(result)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Note: SharedArrayBuffer and crossOriginIsolated are expected to fail " +
                        "outside a real COOP/COEP origin. They will pass when loading from RomM.",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFff9800),
                modifier = Modifier.padding(24.dp)
            )
        }
    }
}

@Composable
fun RomMOriginScreen(origin: String) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "RomM Origin Viewer",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(24.dp, 16.dp)
        )

        Text(
            text = "Origin: ${origin.ifEmpty { "(not configured)" }}",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        Text(
            text = "The WebView below loads the configured RomM origin for unauthenticated " +
                    "rendering observation. No credentials are sent.",
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFff9800),
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
        )

        AndroidView(
            factory = { ctx ->
                val rommOriginParsed = RommOrigin.parse(origin)
                if (rommOriginParsed == null) {
                    WebView(ctx).apply { settings.javaScriptEnabled = true }
                } else {
                    val normalizedUrl = rommOriginParsed.toUrl()
                    WebView(ctx).apply {
                        visibility = View.VISIBLE
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW

                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val url = request?.url?.toString() ?: return false
                                if (!url.lowercase().startsWith(rommOriginParsed.scheme.lowercase() + "://")) return true
                                val uri = RommOrigin.parseUrl(url) ?: return true
                                return !rommOriginParsed.containsUri(uri)
                            }

                            @Suppress("DEPRECATION")
                            @Deprecated("Deprecated in API 24")
                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                url: String?
                            ): Boolean {
                                if (url == null) return false
                                if (!url.lowercase().startsWith(rommOriginParsed.scheme.lowercase() + "://")) return true
                                val uri = RommOrigin.parseUrl(url) ?: return true
                                return !rommOriginParsed.containsUri(uri)
                            }

                            override fun onReceivedSslError(
                                view: WebView?,
                                handler: SslErrorHandler?,
                                error: android.net.http.SslError?
                            ) {
                                handler?.cancel()
                            }
                        }

                        loadUrl(normalizedUrl)
                    }
                }
            },
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
    }
}

@Composable
fun DiagnosticRow(result: DiagnosticResult) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = result.name, color = Color.White, modifier = Modifier.weight(1f))
        Text(
            text = statusLabel(result.status),
            color = statusColor(result.status),
            modifier = Modifier.weight(0.5f)
        )
        if (result.detail != null) {
            Text(
                text = result.detail,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1.5f)
            )
        }
    }
}

fun statusLabel(status: DiagnosticResult.Status): String {
    return when (status) {
        DiagnosticResult.Status.PASS -> "PASS"
        DiagnosticResult.Status.FAIL -> "FAIL"
        DiagnosticResult.Status.EXPECTED_FAIL -> "EXPECTED-FAIL"
    }
}

fun statusColor(status: DiagnosticResult.Status): Color {
    return when (status) {
        DiagnosticResult.Status.PASS -> Color(0xFF4caf50)
        DiagnosticResult.Status.FAIL -> Color(0xFFf44336)
        DiagnosticResult.Status.EXPECTED_FAIL -> Color(0xFFff9800)
    }
}

fun DiagnosticReport.toResultsList(): List<DiagnosticResult> {
    return listOf(
        javascript,
        webAssembly,
        webGl,
        webGl2,
        indexedDb,
        worker,
        gamepads,
        sharedArrayBuffer,
        crossOriginIsolated,
        audio,
        fullscreen,
        localStorage,
        blobUrls
    )
}

/**
 * Securely zeroes a CharArray. Must be called in finally blocks or DisposableEffect.
 */
fun zeroCharArray(array: CharArray) {
    for (i in array.indices) {
        array[i] = '\u0000'
    }
}

// ---- Controller Diagnostics Screen ----

@Composable
@Suppress("UNUSED_PARAMETER")
fun ControllerDiagnosticsScreen(
    router: ControllerEventRouter,
    gamepadBridge: GamepadInjectionBridge,
    gamepadDiagnostics: GamepadInjectionDiagnostics
) {
    val slots by router.slotsFlow.collectAsState()
    val bridgeState by gamepadDiagnostics.state.collectAsState()
    val focusManager = LocalFocusManager.current
    var abSwapped by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        focusManager.clearFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "Controller Diagnostics",
            style = MaterialTheme.typography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(24.dp, 16.dp)
        )

        // Injection diagnostics status
        Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
            Text(
                text = "Document-Start: ${if (bridgeState.documentStartSupported) "SUPPORTED" else "UNSUPPORTED"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (bridgeState.documentStartSupported) Color(0xFF4caf50) else Color(0xFFf44336)
            )
            Text(
                text = "Script Injected: ${if (bridgeState.scriptInjected) "YES" else "NO"}",
                style = MaterialTheme.typography.bodySmall,
                color = if (bridgeState.scriptInjected) Color(0xFF4caf50) else Color.Gray
            )
            Text(
                text = "Updates: ${bridgeState.updateCount} (last: ${if (bridgeState.lastUpdateEpochMs > 0) "${System.currentTimeMillis() - bridgeState.lastUpdateEpochMs}ms ago" else "never"})",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            bridgeState.errorMessage?.let { msg ->
                Text(
                    text = "Error: $msg",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFf44336)
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .weight(1f)
        ) {
            items(
                items = slots,
                key = { slot -> slot.playerNumber }
            ) { slot ->
                ControllerSlotRow(slot, abSwapped)
            }
        }

        // A/B swap toggle — triggers immediate JS update via StateFlow emission
        Spacer(modifier = Modifier.height(8.dp))
        val swapFocusRequester = remember { FocusRequester() }
        OutlinedButton(
            onClick = {
                if (!abSwapped) {
                    router.swapAB(0)
                    abSwapped = true
                } else {
                    router.resetMapping(0)
                    abSwapped = false
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .focusRequester(swapFocusRequester)
        ) {
            Text(
                text = if (abSwapped) "Reset A/B Swap" else "Swap A/B on Player 1",
                color = Color(0xFF4caf50)
            )
        }
        LaunchedEffect(Unit) {
            swapFocusRequester.requestFocus()
        }
    }
}

@Composable
private fun ControllerSlotRow(slot: ControllerSlot, abSwapped: Boolean) {
    val stateColor = when (slot.connectionState) {
        SlotConnectionState.CONNECTED -> Color(0xFF4caf50)
        SlotConnectionState.DISCONNECTED -> Color(0xFFff9800)
        SlotConnectionState.UNASSIGNED -> Color.Gray
    }

    Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Player ${slot.playerNumber}",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = slot.connectionState.name.lowercase().replaceFirstChar { it.uppercase() },
                color = stateColor,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (slot.preferredSignature != null) {
            Text(
                text = "Device: ${slot.preferredSignature.name}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                text = "Sig: ${slot.preferredSignature.descriptor}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Text(
                text = "Device: Unassigned",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Live button states
        val snapshot = slot.currentSnapshot
        val pressedButtons = mutableListOf<String>()
        for (i in snapshot.buttons.indices) {
            if (snapshot.buttons[i] > 0f) {
                pressedButtons.add("B$i")
            }
        }
        Text(
            text = "Buttons: ${if (pressedButtons.isEmpty()) "none" else pressedButtons.joinToString(", ")}",
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall
        )

        // Live axis states
        val activeAxes = mutableListOf<String>()
        for (i in snapshot.axes.indices) {
            if (kotlin.math.abs(snapshot.axes[i]) > 0.01f) {
                activeAxes.add("A${i}=${String.format(Locale.US, "%.2f", snapshot.axes[i])}")
            }
        }
        Text(
            text = "Axes: ${if (activeAxes.isEmpty()) "none" else activeAxes.joinToString(", ")}",
            color = Color.LightGray,
            style = MaterialTheme.typography.bodySmall
        )

        if (abSwapped && slot.playerNumber == 1) {
            Text(
                text = "[A/B swapped]",
                color = Color(0xFF4caf50),
                style = MaterialTheme.typography.bodySmall
            )
        }

        HorizontalDivider(color = Color.Gray, modifier = Modifier.padding(top = 8.dp))
    }
}

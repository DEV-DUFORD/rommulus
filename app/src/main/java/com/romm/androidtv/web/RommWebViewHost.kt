package com.romm.androidtv.web

import android.util.Log
import android.view.View
import android.webkit.SslErrorHandler
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.romm.androidtv.BuildConfig
import com.romm.androidtv.controller.router.ControllerEventRouter
import com.romm.androidtv.gamepad.GamepadInjectionBridge
import com.romm.androidtv.gamepad.GamepadInjectionDiagnostics
import com.romm.androidtv.network.RommOrigin

private const val TAG = "RommWebViewHost"

/**
 * Authenticated WebView screen that embeds a real WebView via AndroidView.
 * The WebView is created, configured, and loaded with session cookies already
 * synced from the native auth flow.
 *
 * Integrates the GamepadInjectionBridge for document-start navigator.getGamepads()
 * interception and native-to-JS state pushes.
 *
 * This is the seam LIBRETRO_REFACTOR.md section 5 calls `web/RommWebViewHost.kt`:
 * WebView hosting and navigation policy live here rather than in `MainActivity`,
 * which now just supplies the resolved origin and callbacks.
 */
@Composable
fun AuthenticatedWebViewScreen(
    origin: String,
    controllerRouter: ControllerEventRouter,
    gamepadBridge: GamepadInjectionBridge,
    gamepadDiagnostics: GamepadInjectionDiagnostics,
    onLogin: () -> Unit
) {
    val slots by controllerRouter.slotsFlow.collectAsState()
    val bridgeDiagnostics by gamepadDiagnostics.state.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        // Diagnostic status bar (debug-only, shows injection state)
        if (BuildConfig.DEBUG) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "RomM (Authenticated)",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                Text(
                    text = gamepadDiagnosticsLabel(bridgeDiagnostics),
                    style = MaterialTheme.typography.bodySmall,
                    color = gamepadDiagnosticsColor(bridgeDiagnostics)
                )
            }
        } else {
            Text(
                text = "RomM (Authenticated)",
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
                modifier = Modifier.padding(24.dp, 8.dp)
            )
        }

        AndroidView(
            factory = { ctx ->
                val rommOriginParsed = RommOrigin.parse(origin)
                    ?: throw IllegalArgumentException("Invalid origin: $origin")
                val normalizedUrl = rommOriginParsed.toUrl()

                WebView(ctx).apply {
                    visibility = View.VISIBLE
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    // Multiple windows disabled (deprecated API, no Kotlin property on SDK 34)

                    // CRITICAL: Request focus so WebView receives KeyEvent dispatch.
                    // Without this, TV remote D-pad/Enter events never reach the WebView.
                    // Post to ensure view is laid out before requesting focus.
                    post { requestFocus() }

                    webViewClient = object : WebViewClient() {
                        @Suppress("DEPRECATION")
                        @Deprecated("Deprecated in API 24, kept for backward compat")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val urlString = request?.url?.toString() ?: return false
                            return handleWebViewNavigation(urlString, rommOriginParsed, onLogin)
                        }

                        @Suppress("DEPRECATION")
                        @Deprecated("Deprecated in API 24")
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            url: String?
                        ): Boolean {
                            if (url == null) return false
                            return handleWebViewNavigation(url, rommOriginParsed, onLogin)
                        }

                        override fun onReceivedSslError(
                            view: WebView?,
                            handler: SslErrorHandler?,
                            error: android.net.http.SslError?
                        ) {
                            handler?.cancel()
                        }

                        override fun onReceivedHttpAuthRequest(
                            view: WebView?,
                            handler: android.webkit.HttpAuthHandler?,
                            host: String?,
                            realm: String?
                        ) {
                            handler?.cancel()
                            // Session expired — transition to native Login
                            onLogin()
                        }
                    }

                    // CRITICAL: Activate gamepad bridge BEFORE loadUrl.
                    // Document-start scripts must be registered before the first navigation.
                    gamepadBridge.activate(this, rommOriginParsed)

                    loadUrl(normalizedUrl)
                }
            },
            update = { webView ->
                // On recomposition (e.g., after Activity resume from background),
                // ensure the WebView retains focus to continue receiving key events.
                webView.requestFocus()
            },
            modifier = Modifier.fillMaxSize().padding(16.dp)
        )
    }

    // Wire ControllerEventRouter slots to the gamepad bridge.
    // Every StateFlow emission triggers an immediate JS update.
    LaunchedEffect(slots) {
        gamepadBridge.setSlots(slots)
    }

    // Dispose bridge when leaving this screen
    DisposableEffect(Unit) {
        onDispose {
            gamepadBridge.dispose()
        }
    }
}

/**
 * Debug-only label for gamepad injection diagnostics status.
 */
private fun gamepadDiagnosticsLabel(state: GamepadInjectionDiagnostics.State): String {
    if (!state.documentStartSupported) return "Gamepad: UNSUPPORTED"
    if (!state.scriptInjected) return "Gamepad: INJECT-FAIL"
    if (state.updateCount > 0) {
        val age = System.currentTimeMillis() - state.lastUpdateEpochMs
        return "Gamepad: OK (${state.updateCount} updates, ${age}ms ago)"
    }
    return "Gamepad: READY"
}

/**
 * Debug-only color for gamepad injection diagnostics status.
 */
private fun gamepadDiagnosticsColor(state: GamepadInjectionDiagnostics.State): Color {
    return when {
        !state.documentStartSupported -> Color(0xFFf44336) // Red — unsupported
        state.errorMessage != null -> Color(0xFFf44336) // Red — error
        state.updateCount > 0 -> Color(0xFF4caf50) // Green — active
        state.scriptInjected -> Color(0xFFff9800) // Orange — injected but no updates yet
        else -> Color.Gray
    }
}

/**
 * Centralized WebView navigation handler. Uses parsed URI comparison
 * for same-origin enforcement and exact /login path detection.
 *
 * Uses the configured origin scheme (not hardcoded HTTPS) to allow
 * HTTP origins when explicitly configured, while preserving
 * network-security enforcement (MIXED_CONTENT_NEVER_ALLOW).
 *
 * Also passively detects same-origin `/rom/{id}/ejs` native-launch candidates
 * via [NativeLaunchInterceptor] and logs them. This is detection only: native
 * launch stays fully disabled in this build, so the return value and WebView
 * behavior are unchanged regardless of what is detected (LIBRETRO_REFACTOR.md
 * sections 4.3 and 6).
 */
internal fun handleWebViewNavigation(
    urlString: String,
    rommOriginParsed: RommOrigin,
    onLogin: () -> Unit
): Boolean {
    // Use configured scheme instead of hardcoded HTTPS.
    // HTTP origins are allowed only when explicitly configured in local.properties.
    val expectedScheme = rommOriginParsed.scheme.lowercase()
    if (!urlString.startsWith("$expectedScheme://", ignoreCase = true)) {
        return true // Non-matching scheme — allow WebView to handle (will likely fail)
    }

    val uri = RommOrigin.parseUrl(urlString) ?: return true

    if (!rommOriginParsed.containsUri(uri)) {
        return true // Different host/path — allow WebView to handle
    }

    // Detect navigation to /login — session expired or not set.
    // Transition to native Login screen via callback, do NOT freeze WebView.
    if (rommOriginParsed.isLoginPath(uri)) {
        onLogin()
        return true // Block WebView navigation; native screen transition handles it
    }

    // Detection only — native launch is not implemented or enabled in this build.
    // This never changes WebView's normal navigation behavior.
    val nativeLaunchCandidate = NativeLaunchInterceptor.parse(urlString, rommOriginParsed)
    if (nativeLaunchCandidate != null) {
        Log.d(TAG, "Detected native-launch candidate romId=${nativeLaunchCandidate.romId} (native launch disabled; loading in WebView)")
    }

    return false // Same-origin path — let WebView load normally
}

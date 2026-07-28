package com.romm.androidtv.gamepad

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.emulator.EmulatorPerformanceScript
import com.romm.androidtv.network.RommOrigin
import kotlinx.coroutines.flow.StateFlow

/**
 * Lifecycle-aware bridge that injects a document-start script to intercept
 * navigator.getGamepads() and pushes translated controller state from
 * native [ControllerSlot] snapshots into the WebView's JavaScript context.
 *
 * PHASE 0 REQUIREMENT: True document-start interception before RomM code executes.
 * Late injection (onPageCommitVisible/onPageFinished/evaluateJavascript script-installation)
 * is NEVER used as production behavior. It may only appear in diagnostic experiments,
 * not in the activate() path.
 *
 * Security properties:
 * - Script is installed ONLY via DOCUMENT_START_SCRIPT API (addDocumentStartJavaScript).
 * - AllowedOriginRules restricts injection to the exact configured RomM origin.
 * - Script self-validates document.origin against the exact allowed RomM origin.
 * - evaluateJavascript is called ONLY for bounded native STATE updates
 *   (__rommUpdateGamepads) after a successfully installed document-start wrapper.
 * - No addJavascriptInterface or generic native method exposure.
 * - All numeric values are clamped and validated before serialization.
 * - Payload size is capped to prevent OOM on the JS side.
 * - JSON is safely escaped for embedding in JS string literals.
 *
 * Failure modes (visible, disable translation):
 * - DOCUMENT_START_SCRIPT not supported: diagnostics.error set, injection disabled.
 * - addDocumentStartJavaScript throws: diagnostics.error set, injection disabled.
 * - Runtime marker verification fails: diagnostics.error set, injection disabled.
 *
 * Lifecycle:
 * - activate(WebView, RommOrigin): installs document-start script (before loadUrl).
 *   Fails visibly if unavailable. Begins change-only updates only on success.
 * - pause(): stops pushes but keeps script installed.
 * - resume(): restarts pushes.
 * - dispose(): neutralizes all slots, removes script via ScriptHandler.remove() on UI thread.
 */
class GamepadInjectionBridge(
    private val diagnostics: GamepadInjectionDiagnostics
) {

    private companion object {
        private const val TAG = "GamepadInjectionBridge"
        /** Timeout (ms) for runtime marker verification after page load. */
        private const val MARKER_VERIFY_TIMEOUT_MS = 5_000L
    }

    private var webView: WebView? = null
    private var allowedOrigin: RommOrigin? = null
    private var mainHandler: Handler? = null
    private var scriptHandler: ScriptHandler? = null
    private var isActive = false
    private var isVerified = false

    /** Current slots snapshot, updated externally via setSlots(). */
    private var currentSlots: List<ControllerSlot> = emptyList()

    /** The compiled script for the allowed origin. */
    private var compiledScript: String? = null
    private var originString: String? = null

    /**
     * Whether the DOCUMENT_START_SCRIPT feature is supported on this device.
     * Checked once during activation.
     */
    val isDocumentStartSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /** Observable diagnostics state. */
    val diagnosticsState: StateFlow<GamepadInjectionDiagnostics.State>
        get() = diagnostics.state

    /**
     * Activate the bridge. Must be called before webView.loadUrl().
     *
     * Attempts document-start script injection via AndroidX WebKit API ONLY.
     * If DOCUMENT_START_SCRIPT is unsupported or verification fails, diagnostics
     * visibly report the error and injection remains DISABLED. No fallback is used.
     */
    fun activate(webView: WebView, rommOrigin: RommOrigin) {
        this.webView = webView
        this.allowedOrigin = rommOrigin
        this.mainHandler = Handler(Looper.getMainLooper())
        this.isActive = false
        this.isVerified = false

        // Validate origin before attempting injection.
        if (rommOrigin.host.isBlank()) {
            diagnostics.setInvalidConfiguration("Origin host is empty. Gamepad injection requires a valid RomM origin.")
            Log.e(TAG, "Invalid origin: host is blank")
            return
        }
        if (rommOrigin.scheme !in listOf("http", "https")) {
            diagnostics.setInvalidConfiguration("Origin scheme '${rommOrigin.scheme}' is not http or https.")
            Log.e(TAG, "Invalid origin: unsupported scheme ${rommOrigin.scheme}")
            return
        }

        val supported = isDocumentStartSupported
        diagnostics.setFeatureSupported(supported)

        if (!supported) {
            diagnostics.setError("DOCUMENT_START_SCRIPT not supported by this WebView. Gamepad injection DISABLED.")
            Log.e(TAG, "DOCUMENT_START_SCRIPT not reported as supported — gamepad injection DISABLED")
            return
        }

        originString = buildOriginString(rommOrigin)
        compiledScript = listOf(
            GamepadInjectionScript.build(originString!!),
            // enableUnvalidatedGenesisFallback stays false here: the Genesis Plus GX ->
            // PicoDrive remap is an unvalidated, unlicensed experiment (see
            // LIBRETRO_REFACTOR.md section 4.3) and must never run in the production
            // WebView path, debug or release.
            EmulatorPerformanceScript.build(originString!!, enableUnvalidatedGenesisFallback = false)
        ).joinToString("\n")

        // Attempt document-start registration.
        val handler: ScriptHandler
        try {
            val allowedOrigins = setOf(originString!!)
            handler = WebViewCompat.addDocumentStartJavaScript(
                webView, compiledScript!!, allowedOrigins
            )
        } catch (e: Exception) {
            diagnostics.setError("addDocumentStartJavaScript threw: ${e.message}. Gamepad injection DISABLED.")
            Log.w(TAG, "AndroidX addDocumentStartJavaScript threw — gamepad injection DISABLED", e)
            return
        }

        this.scriptHandler = handler

        // Verify the AndroidX API actually works by checking for the runtime marker
        // after the first page load on the trusted origin.
        val originalClient = webView.webViewClient
        val verifierClient = object : WebViewClient() {
            private var verified = false
            private var verificationScheduled = false

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (verified || verificationScheduled) return
                if (url != null && !isTrustedUrl(url, rommOrigin)) return
                verificationScheduled = true

                // Bounded timeout for marker verification.
                val verifyHandler = Handler(Looper.getMainLooper())
                verifyHandler.postDelayed({
                    view?.evaluateJavascript(
                        "(function(){return window.__rommGamepadOverride?'true':'false';})();") { result ->
                        val worked = result == "\"true\""
                        if (worked) {
                            verified = true
                            isVerified = true
                            isActive = true
                            injectionSucceeded()
                        } else {
                            // Marker not set — document-start script did NOT execute.
                            failInjection("AndroidX DOCUMENT_START_SCRIPT marker verification FAILED (override=$result). Gamepad injection DISABLED.")
                        }
                        // CRITICAL: Always restore the original WebViewClient after verification,
                        // regardless of success or failure. Otherwise navigation callbacks
                        // (shouldOverrideUrlLoading, onReceivedSslError) are permanently lost.
                        @Suppress("UNNECESSARY_SAFE_CALL")
                        view?.webViewClient = originalClient
                    }
                }, MARKER_VERIFY_TIMEOUT_MS)
            }
        }
        webView.webViewClient = verifierClient

        // Safety net: if verification never fires (e.g., page stalls), restore original
        // client after a generous timeout to prevent permanent WebViewClient replacement.
        mainHandler?.postDelayed({
            webView.webViewClient = originalClient
        }, MARKER_VERIFY_TIMEOUT_MS + 2_000)
    }

    /** Called when document-start registration and runtime marker both succeed. */
    private fun injectionSucceeded() {
        Log.d(TAG, "Document-start script verified for origin: $originString")
        diagnostics.setScriptInjected(true, originString)
        pushToWebViewImmediately()
    }

    /** Called on any failure path; disables injection visibly. */
    private fun failInjection(message: String) {
        Log.w(TAG, message)
        diagnostics.setError(message)
        isActive = false
        isVerified = false
        // Clean up the handler so we don't leak it.
        try {
            scriptHandler?.remove()
        } catch (_: Throwable) { /* ignore */ }
        scriptHandler = null
    }

    /**
     * Update the current slots state. Called from ControllerEventRouter's StateFlow observer.
     * Thread-safe: updates can come from any thread; pushes are always on Main.
     */
    fun setSlots(slots: List<ControllerSlot>) {
        if (slots.size != 4) {
            diagnostics.setInvalidConfiguration("setSlots received ${slots.size} slots; expected exactly 4.")
            Log.w(TAG, "setSlots called with ${slots.size} slots; expected 4")
            return
        }
        this.currentSlots = slots
        pushToWebViewImmediately()
    }

    /**
     * Pause state pushes. Script remains installed. Called on navigation away / pause.
     */
    fun pause() {
        isActive = false
    }

    /**
     * Resume state pushes. Called when returning to the WebView screen.
     */
    fun resume() {
        if (allowedOrigin != null && isVerified) {
            isActive = true
            pushToWebViewImmediately()
        }
    }

    /**
     * Dispose the bridge: neutralize all slots, stop updates, remove script via ScriptHandler.remove().
     * Called on WebView destruction / screen transition away.
     */
    fun dispose() {
        isActive = false
        isVerified = false

        if (Looper.myLooper() != Looper.getMainLooper()) {
            val handler = mainHandler
            if (handler != null) {
                handler.post { disposeOnUiThread() }
            }
            return
        }

        disposeOnUiThread()
    }

    private fun disposeOnUiThread() {
        currentSlots = List(4) { index ->
            ControllerSlot(playerNumber = index + 1).disconnect()
        }

        pushToWebViewSync()

        val handler = this.scriptHandler
        if (handler != null) {
            try {
                handler.remove()
                Log.d(TAG, "Document-start script removed via ScriptHandler.remove()")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove document-start script", e)
            }
            this.scriptHandler = null
        }

        this.webView = null
        this.allowedOrigin = null
        this.mainHandler = null
        this.currentSlots = emptyList()
        this.compiledScript = null
        this.originString = null
    }

    private fun pushToWebViewImmediately() {
        if (!isActive) return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            pushToWebView()
        } else {
            mainHandler?.post { pushToWebView() }
        }
    }

    private fun pushToWebViewSync() {
        if (!isActive) return
        pushToWebView()
    }

    /**
     * Push serialized slot state to the injected script via evaluateJavascript.
     * This is the ONLY use of evaluateJavascript for STATE updates — it calls
     * __rommUpdateGamepads on the already-installed document-start wrapper.
     */
    private fun pushToWebView() {
        val webView = this.webView ?: return
        val rommOrigin = this.allowedOrigin ?: return

        val currentUrl = webView.url
        if (!isTrustedUrl(currentUrl, rommOrigin)) {
            Log.w(TAG, "Current URL not trusted for gamepad updates: $currentUrl")
            return
        }

        val json = GamepadSerializer.serializeSlots(currentSlots)
            ?: run {
                diagnostics.setSerializationError("Gamepad serialization failed (payload too large or invalid slot data).")
                Log.w(TAG, "Gamepad serialization failed (payload too large or wrong slot count)")
                return
            }

        val escapedJson = json.replace("\\", "\\\\")
                              .replace("'", "\\'")
                              .replace("\n", "\\n")
                              .replace("\r", "\\r")
                              .replace("\t", "\\t")

        val jsCall = "(function(){try{return window.__rommUpdateGamepads('$escapedJson')?'ok':'rejected';}catch(e){return 'error';}})();"

        webView.evaluateJavascript(jsCall) { result ->
            if (result == "\"ok\"") {
                diagnostics.recordUpdate()
            } else {
                diagnostics.setSerializationError("JavaScript rejected the gamepad state update.")
                Log.w(TAG, "JS gamepad update failed: $result")
            }
        }
    }

    private fun isTrustedUrl(url: String?, rommOrigin: RommOrigin): Boolean {
        if (url.isNullOrEmpty()) return false
        val parsed = RommOrigin.parseUrl(url) ?: return false
        return rommOrigin.containsUri(parsed)
    }

    private fun buildOriginString(origin: RommOrigin): String {
        val ep = origin.effectivePort
        val portPart = if (ep != origin.scheme.defaultPort()) ":$ep" else ""
        return "${origin.scheme}://${origin.host}$portPart"
    }
}

private fun String.defaultPort(): Int = when (this.lowercase()) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

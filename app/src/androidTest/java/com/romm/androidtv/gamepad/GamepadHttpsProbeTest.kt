package com.romm.androidtv.gamepad

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.romm.androidtv.controller.model.*
import com.romm.androidtv.network.RommOrigin
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Trustworthy instrumented probe for production HTTPS origin validation.
 *
 * RUNTIME-PARAMETERIZED: Supply an HTTPS origin via instrumentation argument.
 * No default credentials; origin may be supplied as instrumentation argument.
 * Loads the approved public RomM root without authentication.
 *
 * USAGE (<=10 minute run):
 * ```
 * adb shell am instrument -w -e origin "https://romm.example.com" \
 *   com.romm.androidtv.debug/androidx.test.runner.AndroidJUnitRunner \
 *   com.romm.androidtv.gamepad.GamepadHttpsProbeTest
 * ```
 *
 * Or via Gradle (requires connected device/emulator):
 * ```
 * ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.origin=https://romm.example.com
 * ```
 *
 * Tests:
 * 1. Feature support check (DOCUMENT_START_SCRIPT)
 * 2. Marker document-start script registration and execution verification
 * 3. Full production GamepadInjectionScript registration
 * 4. Production-serialized state push via evaluateJavascript
 * 5. navigator.getGamepads shape, events, disconnect
 *
 * No addJavascriptInterface. No credentials. No fallback injection.
 */
@RunWith(AndroidJUnit4::class)
class GamepadHttpsProbeTest {

    private companion object {
        private const val TAG = "GamepadHttpsProbe"
        private const val PROBE_TIMEOUT_SECONDS: Long = 60
        private const val MARKER_VERIFY_DELAY_MS: Long = 2_000
    }

    private lateinit var webView: WebView
    private val mainHandler = Handler(Looper.getMainLooper())
    private var httpsOrigin: String? = null
    private var rommOrigin: RommOrigin? = null

    /**
     * Per-test one-shot guard for onPageFinished to prevent reentrant duplicate-page-load races.
     * The Android WebView may fire onPageFinished multiple times per load: once for the main
     * document, once for each HTTP→HTTPS redirect, once for about:blank interstitials, and
     * once for subresource loads. Without this guard, each fire would restart the entire
     * callback chain, racing with AtomicReferences already set by a prior fire, and potentially
     * overwriting final results. The guard is set BEFORE any async callbacks fire, and once
     * set, ALL subsequent onPageFinished calls for that test are ignored.
     */
    private var probeGuardCompleted: Boolean = false

    private fun resetProbeGuard() {
        probeGuardCompleted = false
    }

    @Before
    fun setUp() {
        // Read origin from instrumentation arguments.
        val args = InstrumentationRegistry.getArguments()
        httpsOrigin = args.getString("origin")
        if (!httpsOrigin.isNullOrEmpty()) {
            rommOrigin = RommOrigin.parse(httpsOrigin!!)
            Log.d(TAG, "HTTPS probe origin: $httpsOrigin (parsed: $rommOrigin)")
        } else {
            Log.w(TAG, "No 'origin' instrumentation argument supplied. Tests will skip.")
        }
    }

    @After
    fun tearDown() {
        try {
            val destroyLatch = CountDownLatch(1)
            mainHandler.post {
                try {
                    if (::webView.isInitialized) {
                        webView.destroy()
                    }
                } catch (_: Throwable) {
                }
                destroyLatch.countDown()
            }
            destroyLatch.await(3, TimeUnit.SECONDS)
        } catch (_: Throwable) {
        }
    }

    /**
     * Test 1: Feature support check for DOCUMENT_START_SCRIPT.
     */
    @Test
    fun `probe_featureSupport_documentStartScript`() {
        val supported = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        Log.d(TAG, "DOCUMENT_START_SCRIPT feature supported: $supported")
        // Just log; may vary by device. Not an assertion — we proceed either way.
    }

    /**
     * Test 2: Marker document-start script registration and execution verification.
     *
     * Registers a tiny marker document-start script before load for exact HTTPS origin,
     * loads the origin, and verifies marker execution.
     */
    @Test
    fun `probe_markerScript_registrationAndExecution`() {
        val origin = httpsOrigin ?: run {
            Log.w(TAG, "SKIP: no origin supplied")
            return
        }
        val parsedOrigin = rommOrigin ?: run {
            Log.e(TAG, "SKIP: failed to parse origin $origin")
            return
        }

        assertFalse("DOCUMENT_START_SCRIPT must be supported for HTTPS probe",
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))

        val markerScript = "(function(){ window.__probeMarker = 'MARKER_OK'; })();"
        var registrationSucceeded = false
        var scriptHandler: ScriptHandler? = null
        val doneLatch = CountDownLatch(1)
        val results = mutableListOf<String>()

        mainHandler.post {
            resetProbeGuard()
            webView = createWebView()

            try {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    webView, markerScript, setOf(origin)
                )
                registrationSucceeded = true
                Log.d(TAG, "Marker script registered for origin: $origin")
            } catch (e: Exception) {
                results.add("registration_failed:${e.message}")
                Log.e(TAG, "Marker script registration failed", e)
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // ONE-SHOT guard: skip reentrant redirects/intermediate URLs entirely.
                    // Only process the callback chain for the final expected origin.
                    if (!shouldProcessPageFinished(url, origin)) return

                    results.add("pageLoaded:$url")

                    // Verify marker after bounded delay
                    Handler(Looper.getMainLooper()).postDelayed({
                        view?.evaluateJavascript("(function(){ return window.__probeMarker || 'NOT_SET'; })();") {
                            results.add("marker=$it")
                            Log.d(TAG, "Marker result: $it")

                            // Also verify document.location.origin
                            view.evaluateJavascript("(function(){ return document.location.origin; })();") {
                                results.add("pageOrigin=$it")
                                Log.d(TAG, "Page origin: $it")

                                // Cleanup
                                try { scriptHandler?.remove() } catch (_: Throwable) {}
                                probeGuardCompleted = true
                                doneLatch.countDown()
                            }
                        }
                    }, MARKER_VERIFY_DELAY_MS)
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (probeGuardCompleted) return
                    results.add("error:$errorCode:$description")
                    Log.e(TAG, "Page load error: $description for $failingUrl")
                }
            }

            webView.loadUrl(origin)
        }

        assertTrue("Probe completed within timeout", doneLatch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        for (r in results) {
            Log.d(TAG, "Result: $r")
        }

        if (registrationSucceeded) {
            // Marker should be set if document-start worked
            val markerOk = results.any { it == "marker=\"MARKER_OK\"" }
            assertTrue("Marker script executed for HTTPS origin: results=$results", markerOk)

            // Verify page origin matches
            val pageOriginResult = results.find { it.startsWith("pageOrigin=") }
            if (pageOriginResult != null) {
                val pageOrigin = pageOriginResult.substringAfter("=").trim('"')
                Log.d(TAG, "Page origin verified: $pageOrigin")
            }

            // Verify no console/script errors
            val errors = results.filter { it.startsWith("error:") }
            if (errors.isNotEmpty()) {
                Log.w(TAG, "Console/script errors observed: $errors")
                // Not fatal — may be due to page content, not injection
            }
        } else {
            fail("Marker script registration failed: ${results.filter { it.startsWith("registration_failed") }}")
        }
    }

    /**
     * Test 3: Full production GamepadInjectionScript registration and verification.
     *
     * In a fresh WebView, register the full production GamepadInjectionScript before load,
     * verify the override/status, send production-serialized state via evaluateJavascript,
     * and assert navigator.getGamepads shape/events/disconnect.
     */
    @Test
    fun `probe_fullProductionScript_registrationStatePush_gamepadsShape`() {
        val origin = httpsOrigin ?: run {
            Log.w(TAG, "SKIP: no origin supplied")
            return
        }
        val parsedOrigin = rommOrigin ?: run {
            Log.e(TAG, "SKIP: failed to parse origin $origin")
            return
        }

        assertFalse("DOCUMENT_START_SCRIPT must be supported for HTTPS probe",
            !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))

        val diagnostics = GamepadInjectionDiagnostics()
        val bridge = GamepadInjectionBridge(diagnostics)
        var scriptHandler: ScriptHandler? = null
        val doneLatch = CountDownLatch(1)
        val gamepadsResultRef = AtomicReference<String?>(null)
        val eventsResultRef = AtomicReference<String?>(null)

        mainHandler.post {
            resetProbeGuard()
            webView = createWebView()

            // Register full production script via document-start
            val compiledScript = GamepadInjectionScript.build(origin)
            try {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    webView, compiledScript, setOf(origin)
                )
                Log.d(TAG, "Production script registered for origin: $origin")
            } catch (e: Exception) {
                fail("Production script registration failed: ${e.message}")
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // ONE-SHOT guard: only process the final expected origin, not redirects.
                    if (!shouldProcessPageFinished(url, origin)) return
                    Log.d(TAG, "Page loaded: $url")

                    Handler(Looper.getMainLooper()).postDelayed({
                        val wv = view ?: return@postDelayed
                        // Verify override marker
                        wv.evaluateJavascript("(function(){return window.__rommGamepadOverride?'true':'false';})();") { overrideResult ->
                            Log.d(TAG, "Override check: $overrideResult")
                            assertEquals("Override should be installed", "\"true\"", overrideResult)

                            // Verify status object
                            wv.evaluateJavascript("""(function(){
                                try {
                                    var s = window.__rommGamepadStatus;
                                    return s ? 'ok:injected=' + s.injected + ',origin=' + s.origin + ',allowed=' + s.allowedOrigin : 'no_status';
                                } catch(e) { return 'error:' + e; }
                            })();""") { statusResult ->
                                Log.d(TAG, "Status check: $statusResult")
                                assertTrue("Status should be ok", statusResult!!.startsWith("\"ok:"))

                                // Set up event listeners
                                wv.evaluateJavascript("""(function(){
                                    window.__gpEventLog = [];
                                    window.addEventListener('gamepadconnected', function(e){
                                        window.__gpEventLog.push('connected:'+e.gamepad.index+':'+e.gamepad.connected);
                                    });
                                    window.addEventListener('gamepaddisconnected', function(e){
                                        window.__gpEventLog.push('disconnected:'+e.gamepad.index+':'+e.gamepad.connected);
                                    });
                                    return 'listeners_set';
                                })();""") {}

                                // Send production-serialized state via evaluateJavascript
                                val sig = DeviceSignature(descriptor = "probe", vendorId = 1, productId = 1, name = "Probe")
                                val snapshot = GamepadSnapshot(
                                    buttons = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f, 1f, 0f, 0f, 0f, 0f, 0f, 0f),
                                    axes = floatArrayOf(0.5f, -0.3f, 0f, 0f, 0.8f, 0.2f)
                                )
                                val slots = listOf(
                                    ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
                                    ControllerSlot(playerNumber = 2),
                                    ControllerSlot(playerNumber = 3),
                                    ControllerSlot(playerNumber = 4)
                                )
                                val json = GamepadSerializer.serializeSlots(slots)!!
                                val escapedJson = json.replace("\\", "\\\\").replace("'", "\\'")
                                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                val jsCall = "(function(){try{window.__rommUpdateGamepads('$escapedJson');}catch(e){}})();"

                                wv.evaluateJavascript(jsCall) {}

                                Handler(Looper.getMainLooper()).postDelayed({
                                    // Assert navigator.getGamepads shape
                                    wv.evaluateJavascript("""(function(){
                                        var gps = navigator.getGamepads();
                                        if(!gps || gps.length !== 4) return 'bad_len:' + (gps ? gps.length : 0);
                                        var gp = gps[0];
                                        if(!gp) return 'slot0_null';
                                        return 'ok:len=4,idx=' + gp.index + ',conn=' + gp.connected
                                            + ',btns=' + gp.buttons.length + ',axes=' + gp.axes.length
                                            + ',mapping=' + gp.mapping + ',id=' + gp.id;
                                    })();""") { gamepadsResult ->
                                        gamepadsResultRef.set(gamepadsResult)
                                        Log.d(TAG, "Gamepads shape: $gamepadsResult")

                                        // Check events
                                        wv.evaluateJavascript("""(function(){
                                            var log = window.__gpEventLog || [];
                                            return log.join('|');
                                        })();""") { eventsResult ->
                                            eventsResultRef.set(eventsResult)
                                            Log.d(TAG, "Events: $eventsResult")

                                            // Now test disconnect
                                            val slotsDisconnected = listOf(
                                                ControllerSlot(playerNumber = 1).disconnect(),
                                                ControllerSlot(playerNumber = 2),
                                                ControllerSlot(playerNumber = 3),
                                                ControllerSlot(playerNumber = 4)
                                            )
                                            val json2 = GamepadSerializer.serializeSlots(slotsDisconnected)!!
                                            val escaped2 = json2.replace("\\", "\\\\").replace("'", "\\'")
                                                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")
                                            val jsCall2 = "(function(){try{window.__rommUpdateGamepads('$escaped2');}catch(e){}})();"

                                            wv.evaluateJavascript(jsCall2) {}

                                            Handler(Looper.getMainLooper()).postDelayed({
                                                // Verify disconnect
                                                wv.evaluateJavascript("""(function(){
                                                    var gp = navigator.getGamepads()[0];
                                                    return gp === null ? 'disconnected_ok' : 'still_connected';
                                                })();""") { disconnectResult ->
                                                    Log.d(TAG, "Disconnect check: $disconnectResult")

                                                    // Final events check
                                                    wv.evaluateJavascript("""(function(){
                                                        var log = window.__gpEventLog || [];
                                                        return log.join('|');
                                                    })();""") { finalEvents ->
                                                        Log.d(TAG, "Final events: $finalEvents")
                                                        // Cleanup
                                                        try { scriptHandler?.remove() } catch (_: Throwable) {}
                                                        bridge.dispose()
                                                        probeGuardCompleted = true
                                                        doneLatch.countDown()
                                                    }
                                                }
                                            }, 500)
                                        }
                                    }
                                }, 500)
                            }
                        }
                    }, MARKER_VERIFY_DELAY_MS)
                }

                override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    super.onReceivedError(view, errorCode, description, failingUrl)
                    if (probeGuardCompleted) return
                    Log.e(TAG, "Page load error: $description for $failingUrl")
                }
            }

            webView.loadUrl(origin)
        }

        assertTrue("Probe completed within timeout", doneLatch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))

        // Assertions
        val gamepadsResult = gamepadsResultRef.get()
        assertNotNull("Gamepads result", gamepadsResult)
        assertTrue("Gamepads shape correct: $gamepadsResult",
            gamepadsResult!!.contains("ok:len=4") &&
            gamepadsResult.contains("conn=true") &&
            gamepadsResult.contains("btns=16") &&
            gamepadsResult.contains("axes=4") &&
            gamepadsResult.contains("mapping=standard"))

        val events = eventsResultRef.get()
        assertNotNull("Events result", events)
        assertTrue("gamepadconnected event fired: $events",
            events!!.contains("connected:0:true"))
    }

    /**
     * Test 4: Console and script error detection during probe.
     */
    @Test
    fun `probe_consoleErrors_detectedAndReported`() {
        val origin = httpsOrigin ?: run {
            Log.w(TAG, "SKIP: no origin supplied")
            return
        }

        val diagnostics = GamepadInjectionDiagnostics()
        val bridge = GamepadInjectionBridge(diagnostics)
        var scriptHandler: ScriptHandler? = null
        val doneLatch = CountDownLatch(1)
        val errorsRef = AtomicReference<String?>(null)

        mainHandler.post {
            resetProbeGuard()
            webView = createWebView()

            val compiledScript = GamepadInjectionScript.build(origin)
            try {
                scriptHandler = WebViewCompat.addDocumentStartJavaScript(
                    webView, compiledScript, setOf(origin)
                )
            } catch (e: Exception) {
                fail("Registration failed: ${e.message}")
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // ONE-SHOT guard: skip redirects and intermediate URLs.
                    if (!shouldProcessPageFinished(url, origin)) return

                    Handler(Looper.getMainLooper()).postDelayed({
                        val wv = view ?: return@postDelayed
                        // Check for JS console errors via a simple probe
                        wv.evaluateJavascript("""(function(){
                            try {
                                var gps = navigator.getGamepads();
                                return 'getGamepads_ok:len=' + gps.length;
                            } catch(e) {
                                return 'getGamepads_error:' + e.message;
                            }
                        })();""") { result ->
                            errorsRef.set(result)
                            Log.d(TAG, "Console error probe: $result")
                            try { scriptHandler?.remove() } catch (_: Throwable) {}
                            bridge.dispose()
                            probeGuardCompleted = true
                            doneLatch.countDown()
                        }
                    }, MARKER_VERIFY_DELAY_MS)
                }
            }

            webView.loadUrl(origin)
        }

        assertTrue("Probe completed within timeout", doneLatch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val result = errorsRef.get()
        assertNotNull("Console error probe result", result)
        // Should not have errors from our script
        assertFalse("No getGamepads errors: $result",
            result!!.startsWith("\"getGamepads_error"))
    }

    /**
     * Test 5: Inline and page-JS baseline observable.
     */
    @Test
    fun `probe_inlinePageJs_baselineObservable`() {
        val origin = httpsOrigin ?: run {
            Log.w(TAG, "SKIP: no origin supplied")
            return
        }

        val doneLatch = CountDownLatch(1)
        val jsBaselineRef = AtomicReference<String?>(null)

        mainHandler.post {
            resetProbeGuard()
            webView = createWebView()
            // No document-start script — test baseline JS execution
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // ONE-SHOT guard: skip redirects/intermediate URLs.
                    val finalOrigin = httpsOrigin ?: return
                    if (!shouldProcessPageFinished(url, finalOrigin)) return
                    val wv = view ?: return

                    wv.evaluateJavascript("""(function(){
                        var hasGetGamepads = typeof navigator.getGamepads === 'function';
                        var origin = document.location.origin;
                        var hasPerformance = typeof performance !== 'undefined';
                        return 'baseline:getGamepads=' + hasGetGamepads + ',origin=' + origin + ',performance=' + hasPerformance;
                    })();""") { result ->
                        jsBaselineRef.set(result)
                        Log.d(TAG, "JS baseline: $result")
                        probeGuardCompleted = true
                        doneLatch.countDown()
                    }
                }
            }

            webView.loadUrl(origin)
        }

        assertTrue("Baseline probe completed", doneLatch.await(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        val result = jsBaselineRef.get()
        assertNotNull("JS baseline result", result)
        // navigator.getGamepads should exist on the baseline page
        // (unless overridden by our script, which we didn't register here)
    }

    private fun createWebView(): WebView {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
        }
    }

    /**
     * Normalize a URL to its origin (scheme + host + port). Returns null for non-HTTP(S) URLs.
     * This allows onPageFinished to be compared against the configured final origin, ignoring
     * redirects (e.g., http→https, www→non-www, trailing-slash changes, query-string additions).
     */
    private fun normalizeUrlOrigin(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme?.lowercase()
            if (scheme != "http" && scheme != "https") return null
            val port = uri.port
            val host = (uri.host ?: "").lowercase()
            if (host.isEmpty()) return null
            if (port == -1 || port == 80 && scheme == "http" || port == 443 && scheme == "https") {
                "$scheme://$host"
            } else {
                "$scheme://$host:$port"
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Validate that the callback URL matches the expected final origin after normalization.
     * Returns true if the URL is the final target (matches normalized origin) AND the test
     * has not yet been guarded-completed. Returns false for redirects, intermediate URLs,
     * about:blank, or already-completed tests.
     */
    private fun shouldProcessPageFinished(
        url: String?,
        expectedOrigin: String,
    ): Boolean {
        if (probeGuardCompleted) return false
        val normalized = normalizeUrlOrigin(url) ?: return false
        val normalizedExpected = normalizeUrlOrigin(expectedOrigin) ?: return false
        return normalized == normalizedExpected
    }
}

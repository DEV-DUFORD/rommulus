@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.diagnostic

import com.romm.androidtv.model.DiagnosticReport
import com.romm.androidtv.model.DiagnosticResult
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter

/**
 * Parses the raw JSON string returned by the diagnostic page JavaScript
 * into a [DiagnosticReport].
 *
 * Uses Moshi for robust JSON parsing — handles edge cases like trailing commas,
 * whitespace, and unknown fields gracefully.
 */
object DiagnosticResultParser {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val jsonAdapter = moshi.adapter<DiagnosticJson>()

    // Keys expected in the JSON payload
    private const val KEY_JAVASCRIPT = "javascript"
    private const val KEY_WEBASSEMBLY = "webAssembly"
    private const val KEY_WEBGL = "webGl"
    private const val KEY_WEBGL2 = "webGl2"
    private const val KEY_INDEXEDDB = "indexedDb"
    private const val KEY_WORKER = "worker"
    private const val KEY_GAMEPADS = "gamepads"
    private const val KEY_SAB = "sharedArrayBuffer"
    private const val KEY_CROSS_ORIGIN_ISOLATED = "crossOriginIsolated"
    private const val KEY_AUDIO = "audio"
    private const val KEY_FULLSCREEN = "fullscreen"
    private const val KEY_LOCAL_STORAGE = "localStorage"
    private const val KEY_BLOB_URLS = "blobUrls"

    /**
     * Parse raw JSON using Moshi.
     * Returns null on malformed input.
     */
    fun parse(raw: String): DiagnosticReport? {
        val json = try {
            jsonAdapter.fromJson(raw.trim())
        } catch (_: Exception) {
            return null
        } ?: return null

        val javascript = parseBooleanResult(json.javascript, "JavaScript")
        val webAssembly = parseBooleanResult(json.webAssembly, "WebAssembly")
        val indexedDb = parseBooleanResult(json.indexedDb, "IndexedDB")
        val worker = parseBooleanResult(json.worker, "Worker")
        val gamepads = parseBooleanResult(json.gamepads, "navigator.getGamepads")
        val audio = parseBooleanResult(json.audio, "AudioContext")
        val fullscreen = parseBooleanResult(json.fullscreen, "Fullscreen")
        val localStorage = parseBooleanResult(json.localStorage, "LocalStorage")
        val blobUrls = parseBooleanResult(json.blobUrls, "Blob URLs")

        // WebGL: may be a version string ("2.0") or boolean
        val webGl = parseWebGlResult(json.webGl, false) ?: return null
        val webGl2 = parseWebGlResult(json.webGl2, true) ?: return null

        // SAB and crossOriginIsolated: treat failure as EXPECTED_FAIL
        val sab = parseSABResult(json.sharedArrayBuffer)
        val coi = parseCOIResult(json.crossOriginIsolated)

        return DiagnosticReport(
            javascript = javascript,
            webAssembly = webAssembly,
            webGl = webGl,
            webGl2 = webGl2,
            indexedDb = indexedDb,
            worker = worker,
            gamepads = gamepads,
            sharedArrayBuffer = sab,
            crossOriginIsolated = coi,
            audio = audio,
            fullscreen = fullscreen,
            localStorage = localStorage,
            blobUrls = blobUrls
        )
    }

    // ---- Moshi data class for diagnostic JSON ----

    @JsonClass(generateAdapter = false)
    private data class DiagnosticJson(
        val javascript: Boolean? = null,
        val webAssembly: Boolean? = null,
        val webGl: Any? = null,
        val webGl2: Any? = null,
        val indexedDb: Boolean? = null,
        val worker: Boolean? = null,
        val gamepads: Boolean? = null,
        val sharedArrayBuffer: Boolean? = null,
        val crossOriginIsolated: Boolean? = null,
        val audio: Boolean? = null,
        val fullscreen: Boolean? = null,
        val localStorage: Boolean? = null,
        val blobUrls: Boolean? = null
    )

    // ---- Result builders ----

    private fun parseBooleanResult(value: Boolean?, displayName: String): DiagnosticResult {
        val passed = value == true
        return DiagnosticResult(
            name = displayName,
            status = if (passed) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL
        )
    }

    private fun parseWebGlResult(value: Any?, isGl2: Boolean): DiagnosticResult? {
        val name = if (isGl2) "WebGL2" else "WebGL"

        return when (value) {
            null -> null
            is Boolean -> DiagnosticResult(
                name = name,
                status = if (value) DiagnosticResult.Status.PASS else DiagnosticResult.Status.FAIL
            )
            is String -> DiagnosticResult(
                name = name,
                status = DiagnosticResult.Status.PASS,
                detail = "version $value"
            )
            else -> null
        }
    }

    private fun parseSABResult(value: Boolean?): DiagnosticResult {
        val passed = value == true
        return DiagnosticResult(
            name = "SharedArrayBuffer",
            status = when {
                passed -> DiagnosticResult.Status.PASS
                else -> DiagnosticResult.Status.EXPECTED_FAIL
            },
            detail = if (passed) "Available" else "Expected failure outside COOP/COEP origin"
        )
    }

    private fun parseCOIResult(value: Boolean?): DiagnosticResult {
        val passed = value == true
        return DiagnosticResult(
            name = "crossOriginIsolated",
            status = when {
                passed -> DiagnosticResult.Status.PASS
                else -> DiagnosticResult.Status.EXPECTED_FAIL
            },
            detail = if (passed) "Isolated" else "Expected failure outside COOP/COEP origin"
        )
    }
}

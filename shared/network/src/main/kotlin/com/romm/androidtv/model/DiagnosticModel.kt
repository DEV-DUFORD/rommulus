package com.romm.androidtv.model

/**
 * Result of a single WebView capability test.
 */
data class DiagnosticResult(
    val name: String,
    val status: Status,
    val detail: String? = null
) {
    enum class Status {
        PASS,
        FAIL,
        EXPECTED_FAIL
    }
}

/**
 * Aggregated diagnostic report from the WebView.
 *
 * Phase 0 extended diagnostics include AudioContext, Fullscreen,
 * LocalStorage, and Blob URL support — all required by EmulatorJS.
 */
data class DiagnosticReport(
    val javascript: DiagnosticResult,
    val webAssembly: DiagnosticResult,
    val webGl: DiagnosticResult,
    val webGl2: DiagnosticResult,
    val indexedDb: DiagnosticResult,
    val worker: DiagnosticResult,
    val gamepads: DiagnosticResult,
    val sharedArrayBuffer: DiagnosticResult,
    val crossOriginIsolated: DiagnosticResult,
    val audio: DiagnosticResult,
    val fullscreen: DiagnosticResult,
    val localStorage: DiagnosticResult,
    val blobUrls: DiagnosticResult
)

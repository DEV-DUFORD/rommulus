package com.romm.androidtv.diagnostic

import com.romm.androidtv.model.DiagnosticResult
import com.romm.androidtv.network.RommOrigin
import java.io.IOException

/**
 * Server-side diagnostic that probes COOP/COEP headers on RomM's
 * EmulatorJS route (`/rom/:id/ejs`) using native HTTP calls.
 *
 * This is a Phase 0 diagnostics helper — not generic, origin-restricted,
 * and read-only. It does not alter or upload any server content.
 */
object ServerHeaderDiagnostics {

    /**
     * Probes the EmulatorJS route for COOP/COEP headers.
     * Returns diagnostic results for each header check.
     *
     * @param origin The RomM origin (e.g., "https://romm.example.com")
     * @param romId A known ROM ID to test against (any valid ID works;
     *              we only care about headers, not content)
     * @return List of diagnostic results
     */
    fun probeEmulatorHeaders(origin: String, romId: Int): List<DiagnosticResult> {
        val rommOrigin = RommOrigin.parse(origin)
            ?: return listOf(DiagnosticResult(
                name = "COOP/COEP Probe",
                status = DiagnosticResult.Status.FAIL,
                detail = "Invalid origin"
            ))

        val url = "${rommOrigin.toUrl()}/rom/$romId/ejs"

        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .head() // HEAD request — read-only, no body transfer
                .build()

            val client = okhttp3.OkHttpClient.Builder().build()
            client.newCall(request).execute().use { response ->
                val coep = response.header("Cross-Origin-Embedder-Policy")
                val coop = response.header("Cross-Origin-Opener-Policy")

                listOf(
                    DiagnosticResult(
                        name = "COEP Header",
                        status = if (coep == "require-corp") {
                            DiagnosticResult.Status.PASS
                        } else {
                            DiagnosticResult.Status.FAIL
                        },
                        detail = coep ?: "(absent)"
                    ),
                    DiagnosticResult(
                        name = "COOP Header",
                        status = if (coop == "same-origin") {
                            DiagnosticResult.Status.PASS
                        } else {
                            DiagnosticResult.Status.FAIL
                        },
                        detail = coop ?: "(absent)"
                    ),
                    DiagnosticResult(
                        name = "HTTP Status",
                        status = if (response.code in 200..399) {
                            DiagnosticResult.Status.PASS
                        } else {
                            DiagnosticResult.Status.FAIL
                        },
                        detail = "${response.code} ${response.message}"
                    )
                )
            }
        } catch (e: IOException) {
            listOf(DiagnosticResult(
                name = "COOP/COEP Probe",
                status = DiagnosticResult.Status.FAIL,
                detail = "Network error: ${e.message}"
            ))
        }
    }

    /**
     * Probes a standard (non-emulator) route for comparison.
     * Confirms COOP/COEP are NOT present on non-emulator routes.
     */
    fun probeStandardRoute(origin: String): List<DiagnosticResult> {
        val rommOrigin = RommOrigin.parse(origin)
            ?: return listOf(DiagnosticResult(
                name = "Standard Route COOP/COEP",
                status = DiagnosticResult.Status.FAIL,
                detail = "Invalid origin"
            ))

        val url = rommOrigin.toUrl()

        return try {
            val request = okhttp3.Request.Builder()
                .url(url)
                .head()
                .build()

            val client = okhttp3.OkHttpClient.Builder().build()
            client.newCall(request).execute().use { response ->
                val coep = response.header("Cross-Origin-Embedder-Policy")
                val coop = response.header("Cross-Origin-Opener-Policy")

                // On standard routes, COOP/COEP should be absent (empty string from nginx)
                val coepAbsent = coep == null || coep.isEmpty()
                val coopAbsent = coop == null || coop.isEmpty()

                listOf(
                    DiagnosticResult(
                        name = "Standard Route COEP",
                        status = if (coepAbsent) {
                            DiagnosticResult.Status.PASS
                        } else {
                            DiagnosticResult.Status.EXPECTED_FAIL
                        },
                        detail = coep ?: "(absent) — correct for non-emulator route"
                    ),
                    DiagnosticResult(
                        name = "Standard Route COOP",
                        status = if (coopAbsent) {
                            DiagnosticResult.Status.PASS
                        } else {
                            DiagnosticResult.Status.EXPECTED_FAIL
                        },
                        detail = coop ?: "(absent) — correct for non-emulator route"
                    )
                )
            }
        } catch (e: IOException) {
            listOf(DiagnosticResult(
                name = "Standard Route Probe",
                status = DiagnosticResult.Status.FAIL,
                detail = "Network error: ${e.message}"
            ))
        }
    }
}

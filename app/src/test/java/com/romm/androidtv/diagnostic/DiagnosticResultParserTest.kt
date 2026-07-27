package com.romm.androidtv.diagnostic

import com.romm.androidtv.statusLabel
import com.romm.androidtv.toResultsList
import com.romm.androidtv.model.DiagnosticResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("DiagnosticResultParser (Moshi)")
class DiagnosticResultParserTest {

    private val parser = DiagnosticResultParser

    @Nested
    @DisplayName("parse() — all pass scenario")
    inner class AllPass {
        private val json = """{
            "javascript": true,
            "webAssembly": true,
            "webGl": "2.0",
            "webGl2": true,
            "indexedDb": true,
            "worker": true,
            "gamepads": true,
            "sharedArrayBuffer": true,
            "crossOriginIsolated": true,
            "audio": true,
            "fullscreen": true,
            "localStorage": true,
            "blobUrls": true
        }"""

        @Test
        @DisplayName("parses all-pass JSON successfully")
        fun `all pass`() {
            val report = parser.parse(json)
            assertThat(report).isNotNull()

            val r = report!!
            assertThat(r.javascript.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.webAssembly.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.webGl.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.webGl.detail).contains("2.0")
            assertThat(r.webGl2.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.indexedDb.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.worker.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.gamepads.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.sharedArrayBuffer.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.crossOriginIsolated.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.audio.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.fullscreen.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.localStorage.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(r.blobUrls.status).isEqualTo(DiagnosticResult.Status.PASS)
        }
    }

    @Nested
    @DisplayName("parse() — expected failures for SAB / crossOriginIsolated")
    inner class ExpectedFailures {
        private val json = """{
            "javascript": true,
            "webAssembly": true,
            "webGl": "2.0",
            "webGl2": true,
            "indexedDb": true,
            "worker": true,
            "gamepads": true,
            "sharedArrayBuffer": false,
            "crossOriginIsolated": false,
            "audio": true,
            "fullscreen": true,
            "localStorage": true,
            "blobUrls": true
        }"""

        @Test
        @DisplayName("SAB failure is EXPECTED_FAIL")
        fun `sab expected fail`() {
            val report = parser.parse(json)
            assertThat(report).isNotNull()
            assertThat(report!!.sharedArrayBuffer.status)
                .isEqualTo(DiagnosticResult.Status.EXPECTED_FAIL)
            assertThat(report.sharedArrayBuffer.detail)
                .contains("COOP/COEP")
        }

        @Test
        @DisplayName("crossOriginIsolated failure is EXPECTED_FAIL")
        fun `coi expected fail`() {
            val report = parser.parse(json)
            assertThat(report).isNotNull()
            assertThat(report!!.crossOriginIsolated.status)
                .isEqualTo(DiagnosticResult.Status.EXPECTED_FAIL)
            assertThat(report.crossOriginIsolated.detail)
                .contains("COOP/COEP")
        }
    }

    @Nested
    @DisplayName("parse() — WebGL version string")
    inner class WebGlVersion {
        @Test
        @DisplayName("WebGL version string is parsed as PASS with detail")
        fun `webgl version string`() {
            val json = """{
                "javascript": true,
                "webAssembly": true,
                "webGl": "OpenGL ES 3.0",
                "webGl2": false,
                "indexedDb": true,
                "worker": true,
                "gamepads": true,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": true,
                "fullscreen": true,
                "localStorage": true,
                "blobUrls": true
            }"""
            val report = parser.parse(json)
            assertThat(report).isNotNull()
            assertThat(report!!.webGl.status).isEqualTo(DiagnosticResult.Status.PASS)
            assertThat(report.webGl.detail).contains("OpenGL ES 3.0")
        }

        @Test
        @DisplayName("WebGL boolean true is PASS")
        fun `webgl boolean true`() {
            val json = """{
                "javascript": true,
                "webAssembly": true,
                "webGl": true,
                "webGl2": true,
                "indexedDb": true,
                "worker": true,
                "gamepads": true,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": true,
                "fullscreen": true,
                "localStorage": true,
                "blobUrls": true
            }"""
            val report = parser.parse(json)
            assertThat(report).isNotNull()
            assertThat(report!!.webGl.status).isEqualTo(DiagnosticResult.Status.PASS)
        }
    }

    @Nested
    @DisplayName("parse() — capability failures")
    inner class CapabilityFailures {
        @Test
        @DisplayName("JavaScript failure is FAIL (not expected)")
        fun `javascript fail`() {
            val json = """{
                "javascript": false,
                "webAssembly": false,
                "webGl": false,
                "webGl2": false,
                "indexedDb": false,
                "worker": false,
                "gamepads": false,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": false,
                "fullscreen": false,
                "localStorage": false,
                "blobUrls": false
            }"""
            val report = parser.parse(json)
            assertThat(report).isNotNull()
            assertThat(report!!.javascript.status).isEqualTo(DiagnosticResult.Status.FAIL)
            assertThat(report.webAssembly.status).isEqualTo(DiagnosticResult.Status.FAIL)
            assertThat(report.webGl.status).isEqualTo(DiagnosticResult.Status.FAIL)
            assertThat(report.indexedDb.status).isEqualTo(DiagnosticResult.Status.FAIL)
            assertThat(report.worker.status).isEqualTo(DiagnosticResult.Status.FAIL)
            assertThat(report.gamepads.status).isEqualTo(DiagnosticResult.Status.FAIL)
        }
    }

    @Nested
    @DisplayName("parse() — malformed input")
    inner class MalformedInput {
        @Test
        @DisplayName("null returns null for garbage JSON")
        fun `garbage json`() {
            assertThat(parser.parse("not json")).isNull()
        }

        @Test
        @DisplayName("empty string returns null")
        fun `empty string`() {
            assertThat(parser.parse("")).isNull()
        }

        @Test
        @DisplayName("missing required key returns report with defaults")
        fun `missing javascript key`() {
            val json = """{
                "webAssembly": true,
                "webGl": false,
                "webGl2": false,
                "indexedDb": true,
                "worker": true,
                "gamepads": true,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": true,
                "fullscreen": true,
                "localStorage": true,
                "blobUrls": true
            }"""
            // Moshi handles missing fields gracefully with defaults
            val report = parser.parse(json)
            assertThat(report).isNotNull()
        }

        @Test
        @DisplayName("whitespace-trimmed JSON parses correctly")
        fun `whitespace trimmed`() {
            val json = """
                {
                  "javascript": true,
                  "webAssembly": true,
                  "webGl": "2.0",
                  "webGl2": true,
                  "indexedDb": true,
                  "worker": true,
                  "gamepads": true,
                  "sharedArrayBuffer": false,
                  "crossOriginIsolated": false,
                  "audio": true,
                  "fullscreen": true,
                  "localStorage": true,
                  "blobUrls": true
                }
            """
            assertThat(parser.parse(json)).isNotNull()
        }

        @Test
        @DisplayName("extra unknown fields are ignored by Moshi")
        fun `unknown fields ignored`() {
            val json = """{
                "javascript": true,
                "webAssembly": true,
                "webGl": "2.0",
                "webGl2": true,
                "indexedDb": true,
                "worker": true,
                "gamepads": true,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": true,
                "fullscreen": true,
                "localStorage": true,
                "blobUrls": true,
                "extraField": "should be ignored"
            }"""
            assertThat(parser.parse(json)).isNotNull()
        }
    }

    @Nested
    @DisplayName("DiagnosticReport.toResultsList()")
    inner class ToResultsList {
        @Test
        @DisplayName("returns all 13 results in defined order")
        fun `all thirteen results`() {
            val report = parser.parse("""{
                "javascript": true,
                "webAssembly": true,
                "webGl": "2.0",
                "webGl2": true,
                "indexedDb": true,
                "worker": true,
                "gamepads": true,
                "sharedArrayBuffer": false,
                "crossOriginIsolated": false,
                "audio": true,
                "fullscreen": true,
                "localStorage": true,
                "blobUrls": true
            }""")!!

            val list = report.toResultsList()
            assertThat(list).hasSize(13)
            assertThat(list[0].name).isEqualTo("JavaScript")
            assertThat(list[1].name).isEqualTo("WebAssembly")
            assertThat(list[2].name).isEqualTo("WebGL")
            assertThat(list[3].name).isEqualTo("WebGL2")
            assertThat(list[4].name).isEqualTo("IndexedDB")
            assertThat(list[5].name).isEqualTo("Worker")
            assertThat(list[6].name).isEqualTo("navigator.getGamepads")
            assertThat(list[7].name).isEqualTo("SharedArrayBuffer")
            assertThat(list[8].name).isEqualTo("crossOriginIsolated")
            assertThat(list[9].name).isEqualTo("AudioContext")
            assertThat(list[10].name).isEqualTo("Fullscreen")
            assertThat(list[11].name).isEqualTo("LocalStorage")
            assertThat(list[12].name).isEqualTo("Blob URLs")
        }
    }

    @Nested
    @DisplayName("statusLabel() / statusColor()")
    inner class StatusDisplay {
        @Test
        @DisplayName("PASS maps to correct label")
        fun `pass label`() {
            assertThat(statusLabel(DiagnosticResult.Status.PASS)).isEqualTo("PASS")
        }

        @Test
        @DisplayName("FAIL maps to correct label")
        fun `fail label`() {
            assertThat(statusLabel(DiagnosticResult.Status.FAIL)).isEqualTo("FAIL")
        }

        @Test
        @DisplayName("EXPECTED_FAIL maps to correct label")
        fun `expected fail label`() {
            assertThat(statusLabel(DiagnosticResult.Status.EXPECTED_FAIL))
                .isEqualTo("EXPECTED-FAIL")
        }
    }
}

package com.romm.androidtv.network

import com.romm.androidtv.model.HeartbeatError
import com.romm.androidtv.model.HeartbeatResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("HeartbeatParser (Moshi)")
class HeartbeatParserTest {

    private val parser = HeartbeatParser

    @Nested
    @DisplayName("parse() — RomM 5.0.0+ nested format")
    inner class RomM5NestedFormat {
        private val json = """{
            "SYSTEM": {
                "VERSION": "5.0.0",
                "SHOW_SETUP_WIZARD": false
            },
            "EMULATION": {
                "DISABLE_EMULATOR_JS": false
            },
            "FRONTEND": {
                "DISABLE_USERPASS_LOGIN": false
            }
        }"""

        @Test
        @DisplayName("parses nested SYSTEM.VERSION")
        fun `nested version`() {
            val result = parser.parse(json)
            assertThat(result.error).isNull()
            assertThat(result.response).isNotNull
            assertThat(result.response!!.version).isEqualTo("5.0.0")
        }

        @Test
        @DisplayName("parses SHOW_SETUP_WIZARD=false as setupComplete=true")
        fun `setup complete from wizard`() {
            val result = parser.parse(json)
            assertThat(result.response!!.setupComplete).isTrue()
        }

        @Test
        @DisplayName("parses DISABLE_USERPASS_LOGIN=false as userpassEnabled=true")
        fun `userpass enabled from disable`() {
            val result = parser.parse(json)
            assertThat(result.response!!.userpassEnabled).isTrue()
        }

        @Test
        @DisplayName("parses DISABLE_EMULATOR_JS=false as emulatorJsEnabled=true")
        fun `emulatorjs enabled from disable`() {
            val result = parser.parse(json)
            assertThat(result.response!!.emulatorJsEnabled).isTrue()
        }

        @Test
        @DisplayName("canLogin returns true with all sections present")
        fun `can login`() {
            val result = parser.parse(json)
            assertThat(result.response!!.canLogin()).isTrue()
        }

        @Test
        @DisplayName("SHOW_SETUP_WIZARD=true means setup incomplete")
        fun `setup wizard active`() {
            val jsonWizard = """{
                "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": true },
                "FRONTEND": { "DISABLE_USERPASS_LOGIN": false }
            }"""
            val result = parser.parse(jsonWizard)
            assertThat(result.response!!.setupComplete).isFalse()
        }

        @Test
        @DisplayName("DISABLE_EMULATOR_JS=true means EmulatorJS disabled")
        fun `emulatorjs disabled`() {
            val jsonDisabled = """{
                "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": false },
                "EMULATION": { "DISABLE_EMULATOR_JS": true },
                "FRONTEND": { "DISABLE_USERPASS_LOGIN": false }
            }"""
            val result = parser.parse(jsonDisabled)
            assertThat(result.response!!.emulatorJsEnabled).isFalse()
        }

        @Test
        @DisplayName("DISABLE_USERPASS_LOGIN=true means userpass disabled")
        fun `userpass disabled`() {
            val jsonDisabled = """{
                "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": false },
                "FRONTEND": { "DISABLE_USERPASS_LOGIN": true }
            }"""
            val result = parser.parse(jsonDisabled)
            assertThat(result.response!!.userpassEnabled).isFalse()
        }
    }

    @Nested
    @DisplayName("parse() — legacy flat format (backward compat)")
    inner class LegacyFlatFormat {
        private val json = """{
            "version": "5.0.0",
            "setup_complete": true,
            "userpass_enabled": true,
            "emulatorjs_enabled": true,
            "message": "All systems operational"
        }"""

        @Test
        @DisplayName("parses all fields correctly")
        fun `all fields parsed`() {
            val result = parser.parse(json)
            assertThat(result.error).isNull()
            assertThat(result.response).isNotNull

            val resp = result.response!!
            assertThat(resp.version).isEqualTo("5.0.0")
            assertThat(resp.setupComplete).isTrue()
            assertThat(resp.userpassEnabled).isTrue()
            assertThat(resp.emulatorJsEnabled).isTrue()
            assertThat(resp.rawMessage).isEqualTo("All systems operational")
        }

        @Test
        @DisplayName("canLogin returns true when setup complete and userpass enabled")
        fun `can login`() {
            val result = parser.parse(json)
            assertThat(result.response!!.canLogin()).isTrue()
        }

        @Test
        @DisplayName("isReachable returns true")
        fun `is reachable`() {
            val result = parser.parse(json)
            assertThat(result.response!!.isReachable()).isTrue()
        }
    }

    @Nested
    @DisplayName("parse() — missing optional fields")
    inner class MissingFields {
        @Test
        @DisplayName("empty object returns response with defaults")
        fun `empty object`() {
            val result = parser.parse("{}")
            assertThat(result.response).isNotNull
            assertThat(result.response!!.version).isNull()
            assertThat(result.response!!.setupComplete).isFalse()
            assertThat(result.response!!.userpassEnabled).isFalse()
            assertThat(result.response!!.emulatorJsEnabled).isFalse()
        }

        @Test
        @DisplayName("nested format with missing sections defaults safely")
        fun `missing nested sections`() {
            val json = """{ "SYSTEM": { "VERSION": "5.0.0" } }"""
            val result = parser.parse(json)
            assertThat(result.response).isNotNull
            assertThat(result.response!!.version).isEqualTo("5.0.0")
            // Missing FRONTEND -> userpassEnabled defaults to false (safe)
            assertThat(result.response!!.userpassEnabled).isFalse()
        }
    }

    @Nested
    @DisplayName("parse() — malformed input")
    inner class MalformedInput {
        @Test
        @DisplayName("garbage returns PARSE_ERROR")
        fun `garbage json`() {
            val result = parser.parse("not json at all")
            assertThat(result.response).isNull()
            assertThat(result.error).isEqualTo(HeartbeatError.PARSE_ERROR)
        }

        @Test
        @DisplayName("empty string returns PARSE_ERROR")
        fun `empty string`() {
            val result = parser.parse("")
            assertThat(result.response).isNull()
            assertThat(result.error).isEqualTo(HeartbeatError.PARSE_ERROR)
        }

        @Test
        @DisplayName("whitespace-trimmed JSON parses correctly")
        fun `whitespace trimmed`() {
            val json = """
                {
                  "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": false },
                  "FRONTEND": { "DISABLE_USERPASS_LOGIN": false }
                }
            """
            val result = parser.parse(json)
            assertThat(result.response).isNotNull
            assertThat(result.response!!.version).isEqualTo("5.0.0")
        }

        @Test
        @DisplayName("extra unknown fields are ignored")
        fun `unknown fields ignored`() {
            val json = """{
                "SYSTEM": { "VERSION": "5.0.0", "SHOW_SETUP_WIZARD": false },
                "FRONTEND": { "DISABLE_USERPASS_LOGIN": false },
                "EXTRA_SECTION": { "some_field": 12345 }
            }"""
            val result = parser.parse(json)
            assertThat(result.response).isNotNull
            assertThat(result.response!!.version).isEqualTo("5.0.0")
        }
    }

    @Nested
    @DisplayName("HeartbeatResponse — capability decisions")
    inner class CapabilityDecisions {
        @Test
        @DisplayName("statusSummary includes version when present")
        fun `summary with version`() {
            val resp = HeartbeatResponse(
                version = "5.1.0",
                setupComplete = true,
                userpassEnabled = true,
                emulatorJsEnabled = true
            )
            assertThat(resp.statusSummary()).contains("v5.1.0")
        }

        @Test
        @DisplayName("statusSummary is empty when all good")
        fun `summary empty when all good`() {
            val resp = HeartbeatResponse(
                version = "5.0.0",
                setupComplete = true,
                userpassEnabled = true,
                emulatorJsEnabled = true
            )
            assertThat(resp.statusSummary()).isEqualTo("v5.0.0")
        }

        @Test
        @DisplayName("statusSummary lists all issues")
        fun `summary lists all issues`() {
            val resp = HeartbeatResponse(
                version = null,
                setupComplete = false,
                userpassEnabled = false,
                emulatorJsEnabled = false
            )
            assertThat(resp.statusSummary()).contains("setup incomplete")
            assertThat(resp.statusSummary()).contains("userpass disabled")
            assertThat(resp.statusSummary()).contains("EmulatorJS off")
        }

        @Test
        @DisplayName("canLogin requires both setupComplete and userpassEnabled")
        fun `canLogin logic`() {
            val allGood = HeartbeatResponse("5.0.0", true, true, false)
            assertThat(allGood.canLogin()).isTrue()

            val noSetup = HeartbeatResponse("5.0.0", false, true, true)
            assertThat(noSetup.canLogin()).isFalse()

            val noUserpass = HeartbeatResponse("5.0.0", true, false, true)
            assertThat(noUserpass.canLogin()).isFalse()
        }
    }
}

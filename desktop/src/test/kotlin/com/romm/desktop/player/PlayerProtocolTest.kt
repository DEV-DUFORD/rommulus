package com.romm.desktop.player

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.description.TextDescription
import org.junit.jupiter.api.Test

/**
 * Strict v1 protocol tests (plans/LINUX_X64.md §12.2/§12.3). The Kotlin parser must match the
 * C++ strictness in native/player/src/protocol.cpp: unknown fields are rejected so a secret can
 * never ride along, expectedSaveSize is 64-bit, and only protocolVersion 1 is accepted.
 */
class PlayerProtocolTest {

    private fun sampleRequest(): PlayerRequest = PlayerRequest(
        sessionId = "11111111-2222-3333-4444-555555555555",
        coreId = "test_core",
        coreBuildRevision = "pinned-sha",
        corePath = "/trusted/cores/libtest_core.so",
        contentPath = "/cache/roms/game.gba",
        contentHash = "abc123",
        systemDir = "/data/firmware/gba",
        savePath = "/data/saves/game/autosave.srm",
        candidateSavePath = "/state/journals/x/candidate.srm",
        resultPath = "/state/journals/x/result.json",
        expectedSaveSize = 32768L,
        video = VideoSettings(fullscreen = true, integerScaling = false, scanlines = true),
    )

    private fun sampleResult(): PlayerResult = PlayerResult(
        sessionId = "11111111-2222-3333-4444-555555555555",
        exitKind = PlayerExitKind.COMPLETED,
        checkpointWritten = true,
        candidateSavePath = "/state/journals/x/candidate.srm",
        saveHash = "cafebabe",
        saveSize = 32768L,
        frames = 12345L,
        audioUnderrunFrames = 0L,
        audioOverrunFrames = 0L,
        errorCode = null,
        errorMessage = null,
    )

    // ------------------------------------------------------------------ round trips

    @Test
    fun `request round-trips through serialize and parse`() {
        val original = sampleRequest()
        val parsed = PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original))
        assertThat(parsed.isSuccess).isTrue()
        assertThat(parsed.getOrNull()).isEqualTo(original)
    }

    @Test
    fun `request with null expectedSaveSize round-trips`() {
        val original = sampleRequest().copy(expectedSaveSize = null)
        val json = PlayerProtocol.serializeRequest(original)
        assertThat(json).contains("\"expectedSaveSize\": null")
        assertThat(PlayerProtocol.parseRequest(json).getOrNull()).isEqualTo(original)
    }

    @Test
    fun `result round-trips with all nullable fields set`() {
        val original = sampleResult().copy(errorCode = "E_CORE", errorMessage = "boom")
        assertThat(PlayerProtocol.parseResult(PlayerProtocol.serializeResult(original)).getOrNull())
            .isEqualTo(original)
    }

    @Test
    fun `result round-trips with nulls`() {
        val original = sampleResult().copy(saveHash = null, saveSize = null)
        val json = PlayerProtocol.serializeResult(original)
        assertThat(json).contains("\"saveHash\": null").contains("\"saveSize\": null")
        assertThat(PlayerProtocol.parseResult(json).getOrNull()).isEqualTo(original)
    }

    // ------------------------------------------------------------------ 64-bit sizes

    @Test
    fun `expectedSaveSize above Int MAX_VALUE is preserved as Long`() {
        val request = sampleRequest().copy(expectedSaveSize = 3_000_000_000L) // > Int.MAX_VALUE
        val json = PlayerProtocol.serializeRequest(request)
        assertThat(json).contains("3000000000")
        assertThat(PlayerProtocol.parseRequest(json).getOrNull()?.expectedSaveSize)
            .isEqualTo(3_000_000_000L)
    }

    @Test
    fun `saveSize above Int MAX_VALUE is preserved as Long`() {
        val result = sampleResult().copy(saveSize = 5_000_000_000L)
        assertThat(PlayerProtocol.parseResult(PlayerProtocol.serializeResult(result)).getOrNull()?.saveSize)
            .isEqualTo(5_000_000_000L)
    }

    // ------------------------------------------------------------------ strictness

    @Test
    fun `unknown credential fields are rejected in requests`() {
        for (field in listOf("token", "origin", "username")) {
            val json = PlayerProtocol.serializeRequest(sampleRequest())
                .substringBeforeLast("}") + ",\n  \"$field\": \"smuggled\"\n}"
            val parsed = PlayerProtocol.parseRequest(json)
            assertThat(parsed.isFailure).describedAs(TextDescription("field $field must be rejected")).isTrue()
            assertThat(parsed.exceptionOrNull()?.message).contains("unknown field")
        }
    }

    @Test
    fun `unknown credential fields are rejected in results`() {
        val json = PlayerProtocol.serializeResult(sampleResult())
            .substringBeforeLast("}") + ",\n  \"token\": \"smuggled\"\n}"
        assertThat(PlayerProtocol.parseResult(json).isFailure).isTrue()
    }

    @Test
    fun `negative expectedSaveSize is rejected`() {
        val json = PlayerProtocol.serializeRequest(sampleRequest())
            .replace("\"expectedSaveSize\": 32768", "\"expectedSaveSize\": -5")
        assertThat(PlayerProtocol.parseRequest(json).isFailure).isTrue()
    }

    @Test
    fun `non-integer protocolVersion is rejected`() {
        val json = PlayerProtocol.serializeRequest(sampleRequest())
            .replace("\"protocolVersion\": 1,", "\"protocolVersion\": 1.5,")
        assertThat(PlayerProtocol.parseRequest(json).isFailure).isTrue()
    }

    @Test
    fun `unsupported protocolVersion is rejected`() {
        val requestJson = PlayerProtocol.serializeRequest(sampleRequest())
            .replace("\"protocolVersion\": 1,", "\"protocolVersion\": 2,")
        assertThat(PlayerProtocol.parseRequest(requestJson).isFailure).isTrue()

        val resultJson = PlayerProtocol.serializeResult(sampleResult())
            .replace("\"protocolVersion\": 1,", "\"protocolVersion\": 2,")
        assertThat(PlayerProtocol.parseResult(resultJson).isFailure).isTrue()
    }

    @Test
    fun `missing required field is rejected`() {
        val json = PlayerProtocol.serializeRequest(sampleRequest())
            .replace("\n  \"coreId\": \"test_core\",", "")
        val parsed = PlayerProtocol.parseRequest(json)
        assertThat(parsed.isFailure).isTrue()
        assertThat(parsed.exceptionOrNull()?.message).contains("missing required field: coreId")
    }

    @Test
    fun `video must be an object with exactly the three boolean fields`() {
        val notAnObject = PlayerProtocol.serializeRequest(sampleRequest())
            .replace(Regex("\"video\": \\{[^}]*\\}", RegexOption.DOT_MATCHES_ALL), "\"video\": []")
        assertThat(PlayerProtocol.parseRequest(notAnObject).isFailure).isTrue()

        val missingScanlines = """
            {"protocolVersion":1,"sessionId":"s","coreId":"c","coreBuildRevision":"r",
             "corePath":"/c","contentPath":"/g","contentHash":"","systemDir":"/s",
             "savePath":"/sp","candidateSavePath":"/cs","resultPath":"/rp",
             "expectedSaveSize":null,
             "video":{"fullscreen":true,"integerScaling":false}}
        """.trimIndent()
        assertThat(PlayerProtocol.parseRequest(missingScanlines).isFailure).isTrue()

        val extraVideoField = """
            {"protocolVersion":1,"sessionId":"s","coreId":"c","coreBuildRevision":"r",
             "corePath":"/c","contentPath":"/g","contentHash":"","systemDir":"/s",
             "savePath":"/sp","candidateSavePath":"/cs","resultPath":"/rp",
             "expectedSaveSize":null,
             "video":{"fullscreen":true,"integerScaling":false,"scanlines":false,"stereo":true}}
        """.trimIndent()
        assertThat(PlayerProtocol.parseRequest(extraVideoField).isFailure).isTrue()
    }

    @Test
    fun `malformed json is rejected`() {
        assertThat(PlayerProtocol.parseRequest("{ not json").isFailure).isTrue()
        assertThat(PlayerProtocol.parseResult("").isFailure).isTrue()
    }

    @Test
    fun `top level array is rejected`() {
        assertThat(PlayerProtocol.parseRequest("[1,2,3]").isFailure).isTrue()
        assertThat(PlayerProtocol.parseResult("[1,2,3]").isFailure).isTrue()
    }

    @Test
    fun `result rejects unknown exitKind`() {
        val json = PlayerProtocol.serializeResult(sampleResult())
            .replace("\"exitKind\": \"completed\"", "\"exitKind\": \"exploded\"")
        val parsed = PlayerProtocol.parseResult(json)
        assertThat(parsed.isFailure).isTrue()
        assertThat(parsed.exceptionOrNull()?.message).contains("unknown exitKind")
    }

    @Test
    fun `all five exit kinds parse from their wire names`() {
        val expected = mapOf(
            "completed" to PlayerExitKind.COMPLETED,
            "user_cancelled_before_start" to PlayerExitKind.USER_CANCELLED_BEFORE_START,
            "core_requested_shutdown" to PlayerExitKind.CORE_REQUESTED_SHUTDOWN,
            "launch_failed" to PlayerExitKind.LAUNCH_FAILED,
            "runtime_failed" to PlayerExitKind.RUNTIME_FAILED,
        )
        for ((wire, kind) in expected) {
            val json = PlayerProtocol.serializeResult(sampleResult()).replace(
                "\"exitKind\": \"completed\"",
                "\"exitKind\": \"$wire\"",
            )
            assertThat(PlayerProtocol.parseResult(json).getOrNull()?.exitKind)
                .describedAs(TextDescription("wire name $wire")).isEqualTo(kind)
        }
    }

    @Test
    fun `nullable string fields accept null and reject wrong types`() {
        val badType = PlayerProtocol.serializeResult(sampleResult())
            .replace("\"saveHash\": \"cafebabe\"", "\"saveHash\": 42")
        assertThat(PlayerProtocol.parseResult(badType).isFailure).isTrue()

        val explicitNull = PlayerProtocol.serializeResult(sampleResult())
            .replace("\"saveHash\": \"cafebabe\"", "\"saveHash\": null")
        assertThat(PlayerProtocol.parseResult(explicitNull).getOrNull()?.saveHash).isNull()
    }
}

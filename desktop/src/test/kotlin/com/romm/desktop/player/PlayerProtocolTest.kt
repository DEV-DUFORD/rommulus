package com.romm.desktop.player

import com.romm.androidtv.library.RommTheme
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.description.TextDescription
import org.junit.jupiter.api.Test

/**
 * Strict v2 protocol tests (plans/LINUX_X64.md §12.2/§12.3). The Kotlin parser must match the
 * C++ strictness in native/player/src/protocol.cpp: unknown fields are rejected so a secret can
 * never ride along, expectedSaveSize is 64-bit, only protocolVersion 2 is accepted, and the
 * optional controllerBindings field round-trips with its full strict sub-schema.
 */
class PlayerProtocolTest {

    /** One device covering all four entry shapes (button / axis / axis_direction / unbound). */
    private fun sampleControllerBindings(): ControllerBindings = ControllerBindings(
        devices = listOf(
            ControllerBindingDevice(
                guid = "036d04ca010000000000000000000000",
                identity = ControllerBindingIdentity(vendorId = 0x046d, productId = 0x01ca, descriptor = "vid:046d-pid:01ca"),
                bindings = listOf(
                    PlayerSlotBinding("a", PlayerBindingType.BUTTON, button = "south"),
                    PlayerSlotBinding("b", PlayerBindingType.AXIS_DIRECTION, axis = "left_x", polarity = -1),
                    PlayerSlotBinding("x", PlayerBindingType.BUTTON, button = "west"),
                    PlayerSlotBinding("y", PlayerBindingType.UNBOUND),
                    PlayerSlotBinding("select", PlayerBindingType.AXIS, axis = "left_x"),
                    PlayerSlotBinding("start", PlayerBindingType.BUTTON, button = "start"),
                    PlayerSlotBinding("left_shoulder", PlayerBindingType.AXIS_DIRECTION, axis = "left_trigger", polarity = 1),
                    PlayerSlotBinding("right_shoulder", PlayerBindingType.BUTTON, button = "right_shoulder"),
                    PlayerSlotBinding("dpad_up", PlayerBindingType.BUTTON, button = "dpad_up"),
                    PlayerSlotBinding("dpad_down", PlayerBindingType.BUTTON, button = "dpad_down"),
                    PlayerSlotBinding("dpad_left", PlayerBindingType.AXIS_DIRECTION, axis = "left_x", polarity = -1),
                    PlayerSlotBinding("dpad_right", PlayerBindingType.BUTTON, button = "dpad_right"),
                ),
            ),
        ),
    )

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
        video = VideoSettings(fullscreen = true, integerScaling = false, scanlines = true, sharpFilter = true),
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
        video = VideoSettings(integerScaling = true, scanlines = true, sharpFilter = false),
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
    fun `controller slots round-trip with explicit empty players`() {
        val original = sampleRequest().copy(
            controllerSlots = listOf("Steam Deck", null, "Wireless Controller", null),
        )
        val json = PlayerProtocol.serializeRequest(original)

        assertThat(json).contains(
            "\"controllerSlots\": [",
            "\"Steam Deck\"",
            "\"Wireless Controller\"",
        )
        assertThat(PlayerProtocol.parseRequest(json).getOrNull()).isEqualTo(original)
    }

    @Test
    fun `software renderer override round-trips and rejects unknown values`() {
        val original = sampleRequest().copy(rendererOverride = RendererOverride.SOFTWARE_HW)
        val json = PlayerProtocol.serializeRequest(original)
        assertThat(PlayerProtocol.parseRequest(json).getOrNull()).isEqualTo(original)
        assertThat(
            PlayerProtocol.parseRequest(
                json.replace("\"software_hw\"", "\"unknown_renderer\""),
            ).isFailure,
        ).isTrue()
    }

    @Test
    fun `theme round-trips and rejects unknown values`() {
        RommTheme.entries.forEach { theme ->
            val original = sampleRequest().copy(theme = theme)
            val json = PlayerProtocol.serializeRequest(original)
            assertThat(json).contains("\"theme\": \"${theme.name}\"")
            assertThat(PlayerProtocol.parseRequest(json).getOrNull()).isEqualTo(original)
        }
        val json = PlayerProtocol.serializeRequest(sampleRequest())
        assertThat(
            PlayerProtocol.parseRequest(
                json.replace("\"RomMulus\"", "\"unknown_theme\""),
            ).isFailure,
        ).isTrue()
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

    @Test
    fun `legacy v2 result without video settings remains readable`() {
        val original = sampleResult().copy(video = null)
        val json = PlayerProtocol.serializeResult(original)
        assertThat(json).doesNotContain("\"video\"")
        assertThat(PlayerProtocol.parseResult(json).getOrNull()).isEqualTo(original)
    }

    // ------------------------------------------------------------------ v2 controllerBindings

    @Test
    fun `request with controllerBindings round-trips`() {
        val original = sampleRequest().copy(controllerBindings = sampleControllerBindings())
        val parsed = PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original))
        assertThat(parsed.isSuccess).isTrue()
        assertThat(parsed.getOrNull()).isEqualTo(original)
    }

    @Test
    fun `pause menu bindings round-trip inside controller bindings`() {
        val original = sampleRequest().copy(
            controllerBindings = sampleControllerBindings().copy(
                pauseMenuBindings = listOf(
                    PlayerSlotBinding(
                        "primary",
                        PlayerBindingType.BUTTON,
                        button = "left_stick",
                    ),
                    PlayerSlotBinding(
                        "secondary",
                        PlayerBindingType.BUTTON,
                        button = "back",
                    ),
                ),
            ),
        )

        assertThat(
            PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original)).getOrNull(),
        ).isEqualTo(original)
    }

    @Test
    fun `secondary controller bindings round-trip`() {
        val base = sampleControllerBindings()
        val device = base.devices.single()
        val secondary = device.bindings.map { binding ->
            if (binding.slot == "a") {
                PlayerSlotBinding("a", PlayerBindingType.BUTTON, button = "north")
            } else {
                PlayerSlotBinding(binding.slot, PlayerBindingType.UNBOUND)
            }
        }
        val original = sampleRequest().copy(
            controllerBindings = ControllerBindings(
                listOf(device.copy(secondaryBindings = secondary)),
            ),
        )
        assertThat(
            PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original)).getOrNull(),
        ).isEqualTo(original)
    }

    @Test
    fun `controllerBindings is omitted from the wire when null and parses back as null`() {
        val json = PlayerProtocol.serializeRequest(sampleRequest())
        assertThat(json).doesNotContain("controllerBindings")
        assertThat(PlayerProtocol.parseRequest(json).getOrNull()?.controllerBindings).isNull()
    }

    @Test
    fun `keyboardBindings round-trip with nullable primary and secondary keys`() {
        val keyboard = KeyboardBindings(
            com.romm.desktop.controller.keyboard.KEYBOARD_TARGETS.mapIndexed { index, target ->
                KeyboardBindingEntry(
                    target = target,
                    primaryScancode = index.takeIf { index % 2 == 0 },
                    secondaryScancode = (100 + index).takeIf { index % 3 == 0 },
                )
            },
        )
        val original = sampleRequest().copy(keyboardBindings = keyboard)

        assertThat(
            PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original)).getOrNull(),
        ).isEqualTo(original)
    }

    @Test
    fun `keyboardBindings rejects missing or reordered targets`() {
        val keyboard = KeyboardBindings(
            com.romm.desktop.controller.keyboard.KEYBOARD_TARGETS.drop(1).map {
                KeyboardBindingEntry(it, null, null)
            },
        )

        val parsed = PlayerProtocol.parseRequest(
            PlayerProtocol.serializeRequest(sampleRequest().copy(keyboardBindings = keyboard)),
        )

        assertThat(parsed.isFailure).isTrue()
    }

    @Test
    fun `request with empty devices array round-trips`() {
        val original = sampleRequest().copy(controllerBindings = ControllerBindings(devices = emptyList()))
        val parsed = PlayerProtocol.parseRequest(PlayerProtocol.serializeRequest(original))
        assertThat(parsed.getOrNull()).isEqualTo(original)
    }

    @Test
    fun `controllerBindings rejects unknown fields at every nesting level`() {
        val base = PlayerProtocol.serializeRequest(sampleRequest().copy(controllerBindings = sampleControllerBindings()))

        // Unknown identity sub-field (descriptor is the last identity field).
        val unknownIdentityField = base.replace(
            "\"descriptor\": \"vid:046d-pid:01ca\"",
            "\"descriptor\": \"vid:046d-pid:01ca\", \"mac\": \"aa:bb\"",
        )
        assertThat(PlayerProtocol.parseRequest(unknownIdentityField).isFailure).isTrue()

        // Unknown device-level field.
        val unknownDeviceField = base.replace(
            "\"guid\": \"036d04ca010000000000000000000000\"",
            "\"guid\": \"036d04ca010000000000000000000000\", \"serial\": \"x\"",
        )
        assertThat(PlayerProtocol.parseRequest(unknownDeviceField).isFailure).isTrue()

        // Unknown binding-entry field.
        val unknownBindingField = base.replace("\"slot\": \"a\"", "\"slot\": \"a\", \"mod\": true")
        assertThat(PlayerProtocol.parseRequest(unknownBindingField).isFailure).isTrue()
    }

    @Test
    fun `controllerBindings rejects malformed binding entries`() {
        val base = PlayerProtocol.serializeRequest(sampleRequest().copy(controllerBindings = sampleControllerBindings()))

        // Unknown slot / button / axis names.
        assertThat(PlayerProtocol.parseRequest(base.replace("\"slot\": \"a\"", "\"slot\": \"z\"")).isFailure).isTrue()
        assertThat(PlayerProtocol.parseRequest(base.replace("\"button\": \"south\"", "\"button\": \"trigger\"")).isFailure).isTrue()
        assertThat(PlayerProtocol.parseRequest(base.replace("\"axis\": \"left_x\"", "\"axis\": \"hat_z\"")).isFailure).isTrue()

        // Polarity out of range / missing (slot b is the first axis_direction entry).
        assertThat(PlayerProtocol.parseRequest(base.replace("\"polarity\": -1", "\"polarity\": 2")).isFailure).isTrue()
        val missingPolarity = base.replace(Regex(",\\s*\"polarity\": -1"), "")
        assertThat(PlayerProtocol.parseRequest(missingPolarity).isFailure).isTrue()

        // Type-specific field sets: unbound must not carry button; button must not carry axis.
        // (The serializer is multi-line, so the patterns tolerate whitespace between fields.)
        val unboundWithButton = base.replace(
            Regex("\"slot\": \"y\",\\s*\"type\": \"unbound\""),
            "\"slot\": \"y\", \"type\": \"unbound\", \"button\": \"south\"",
        )
        assertThat(PlayerProtocol.parseRequest(unboundWithButton).isFailure).isTrue()
        val buttonWithAxis = base.replace(
            Regex("\"slot\": \"a\",\\s*\"type\": \"button\",\\s*\"button\": \"south\""),
            "\"slot\": \"a\", \"type\": \"button\", \"button\": \"south\", \"axis\": \"left_x\"",
        )
        assertThat(PlayerProtocol.parseRequest(buttonWithAxis).isFailure).isTrue()

        // Unknown type.
        assertThat(PlayerProtocol.parseRequest(base.replace("\"type\": \"unbound\"", "\"type\": \"hat\"")).isFailure).isTrue()

        // Wrong identity types.
        val negativeVendor = base.replace("\"vendorId\": 1133", "\"vendorId\": -1")
        assertThat(PlayerProtocol.parseRequest(negativeVendor).isFailure).isTrue()
        val stringProductId = base.replace("\"productId\": 458", "\"productId\": \"cafe\"")
        assertThat(PlayerProtocol.parseRequest(stringProductId).isFailure).isTrue()
    }

    @Test
    fun `controllerBindings requires all twelve slots in order`() {
        val base = PlayerProtocol.serializeRequest(sampleRequest().copy(controllerBindings = sampleControllerBindings()))

        // Drop the last entry (11 total, prefix still in order).
        val withoutLast = base.replace(Regex(",\\s*\\{[^{}]*\"slot\": \"dpad_right\"[^{}]*\\}"), "")
        assertThat(PlayerProtocol.parseRequest(withoutLast).isFailure).isTrue()

        // Out of order: rename the first entry's slot so it no longer leads the sequence.
        val swapped = base.replace(
            Regex("\"slot\": \"a\",\\s*\"type\": \"button\",\\s*\"button\": \"south\""),
            "\"slot\": \"b\", \"type\": \"button\", \"button\": \"south\"",
        )
        assertThat(PlayerProtocol.parseRequest(swapped).isFailure).isTrue()

        // Duplicate a slot (12 entries, one repeated, one missing).
        val duplicated = base.replace(
            Regex("\"slot\": \"dpad_right\",\\s*\"type\": \"button\",\\s*\"button\": \"dpad_right\""),
            "\"slot\": \"a\", \"type\": \"button\", \"button\": \"south\"",
        )
        assertThat(PlayerProtocol.parseRequest(duplicated).isFailure).isTrue()

        // Missing the inner required "devices" array.
        val noDevices = base.substringBefore("\"controllerBindings\"").trimEnd().removeSuffix(",") +
            "\n  \"controllerBindings\": {}\n}"
        assertThat(PlayerProtocol.parseRequest(noDevices).isFailure).isTrue()
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
            .replace("\"protocolVersion\": 2,", "\"protocolVersion\": 1.5,")
        assertThat(PlayerProtocol.parseRequest(json).isFailure).isTrue()
    }

    @Test
    fun `unsupported protocolVersion is rejected`() {
        // v1 requests are rejected now that the protocol is at v2 (and so is anything else).
        for (version in listOf(0, 1, 3, 99)) {
            val requestJson = PlayerProtocol.serializeRequest(sampleRequest())
                .replace("\"protocolVersion\": 2,", "\"protocolVersion\": $version,")
            assertThat(PlayerProtocol.parseRequest(requestJson).isFailure)
                .describedAs(TextDescription("request version $version must be rejected")).isTrue()

            val resultJson = PlayerProtocol.serializeResult(sampleResult())
                .replace("\"protocolVersion\": 2,", "\"protocolVersion\": $version,")
            assertThat(PlayerProtocol.parseResult(resultJson).isFailure)
                .describedAs(TextDescription("result version $version must be rejected")).isTrue()
        }
    }

    @Test
    fun `v1 request without controllerBindings is still rejected on the version alone`() {
        // A genuine v1 wire document (no controllerBindings, version 1) fails on the version.
        val json = PlayerProtocol.serializeRequest(sampleRequest())
            .replace("\"protocolVersion\": 2,", "\"protocolVersion\": 1,")
        val parsed = PlayerProtocol.parseRequest(json)
        assertThat(parsed.isFailure).isTrue()
        assertThat(parsed.exceptionOrNull()?.message).contains("unsupported protocolVersion: 1")
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
    fun `video must be an object with exactly the four boolean fields`() {
        val notAnObject = PlayerProtocol.serializeRequest(sampleRequest())
            .replace(Regex("\"video\": \\{[^}]*\\}", RegexOption.DOT_MATCHES_ALL), "\"video\": []")
        assertThat(PlayerProtocol.parseRequest(notAnObject).isFailure).isTrue()

        val missingScanlines = """
            {"protocolVersion":2,"sessionId":"s","coreId":"c","coreBuildRevision":"r",
             "corePath":"/c","contentPath":"/g","contentHash":"","systemDir":"/s",
             "savePath":"/sp","candidateSavePath":"/cs","resultPath":"/rp",
             "expectedSaveSize":null,
             "video":{"fullscreen":true,"integerScaling":false,"sharpFilter":false}}
        """.trimIndent()
        assertThat(PlayerProtocol.parseRequest(missingScanlines).isFailure).isTrue()

        val missingSharpFilter = """
            {"protocolVersion":2,"sessionId":"s","coreId":"c","coreBuildRevision":"r",
             "corePath":"/c","contentPath":"/g","contentHash":"","systemDir":"/s",
             "savePath":"/sp","candidateSavePath":"/cs","resultPath":"/rp",
             "expectedSaveSize":null,
             "video":{"fullscreen":true,"integerScaling":false,"scanlines":false}}
        """.trimIndent()
        assertThat(PlayerProtocol.parseRequest(missingSharpFilter).isFailure).isTrue()

        val extraVideoField = """
            {"protocolVersion":2,"sessionId":"s","coreId":"c","coreBuildRevision":"r",
             "corePath":"/c","contentPath":"/g","contentHash":"","systemDir":"/s",
             "savePath":"/sp","candidateSavePath":"/cs","resultPath":"/rp",
             "expectedSaveSize":null,
             "video":{"fullscreen":true,"integerScaling":false,"scanlines":false,"sharpFilter":false,"stereo":true}}
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

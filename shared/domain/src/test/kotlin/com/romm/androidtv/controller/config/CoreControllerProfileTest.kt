package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CoreControllerProfile — init validation")
class CoreControllerProfileTest {

    private val sampleArtwork = ControllerArtwork(
        resourceName = "controller_outline_test",
        source = "Test source",
        license = "MIT",
        licenseAssetPath = null,
        viewBoxWidth = 100f,
        viewBoxHeight = 100f,
    )

    private val sampleRegion = ControllerHighlightRegion(
        id = "test_region",
        shape = HighlightShape.RECT,
        x = 0.1f,
        y = 0.1f,
        width = 0.2f,
        height = 0.2f,
    )

    private fun sampleDescriptor(controlId: CoreControlId) = CoreControlDescriptor(
        id = controlId,
        label = "Test Control",
        target = com.romm.androidtv.controller.model.LogicalControl.BUTTON_A,
        inputKind = InputKind.BUTTON,
        highlightRegion = sampleRegion,
    )

    @Test
    fun `playerCount of 0 throws`() {
        assertThatThrownBy {
            CoreControllerProfile(
                coreId = "test_core",
                consoleName = "Test Console",
                consoleSubtitle = null,
                playerCount = 0,
                artwork = sampleArtwork,
                controls = emptyList(),
                defaults = emptyMap(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `playerCount of 5 throws (exceeds SLOT_COUNT)`() {
        assertThatThrownBy {
            CoreControllerProfile(
                coreId = "test_core",
                consoleName = "Test Console",
                consoleSubtitle = null,
                playerCount = 5,
                artwork = sampleArtwork,
                controls = emptyList(),
                defaults = mapOf(0 to PlayerControllerConfig(), 1 to PlayerControllerConfig(), 2 to PlayerControllerConfig(), 3 to PlayerControllerConfig(), 4 to PlayerControllerConfig()),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `missing player default key throws`() {
        assertThatThrownBy {
            CoreControllerProfile(
                coreId = "test_core",
                consoleName = "Test Console",
                consoleSubtitle = null,
                playerCount = 2,
                artwork = sampleArtwork,
                controls = emptyList(),
                defaults = mapOf(0 to PlayerControllerConfig()), // missing player 1
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `extra player default key throws`() {
        assertThatThrownBy {
            CoreControllerProfile(
                coreId = "test_core",
                consoleName = "Test Console",
                consoleSubtitle = null,
                playerCount = 1,
                artwork = sampleArtwork,
                controls = emptyList(),
                defaults = mapOf(0 to PlayerControllerConfig(), 1 to PlayerControllerConfig()),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `duplicate control IDs throw`() {
        val descriptor = sampleDescriptor(CoreControlId.BUTTON_A)
        assertThatThrownBy {
            CoreControllerProfile(
                coreId = "test_core",
                consoleName = "Test Console",
                consoleSubtitle = null,
                playerCount = 1,
                artwork = sampleArtwork,
                controls = listOf(descriptor, descriptor),
                defaults = mapOf(0 to PlayerControllerConfig()),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `valid single-player profile constructs without error`() {
        val profile = CoreControllerProfile(
            coreId = "test_core",
            consoleName = "Test Console",
            consoleSubtitle = null,
            playerCount = 1,
            artwork = sampleArtwork,
            controls = listOf(sampleDescriptor(CoreControlId.BUTTON_A)),
            defaults = mapOf(0 to PlayerControllerConfig()),
        )
        assertThat(profile.playerCount).isEqualTo(1)
    }

    @Test
    fun `valid four-player profile constructs without error`() {
        val profile = CoreControllerProfile(
            coreId = "test_core",
            consoleName = "Test Console",
            consoleSubtitle = null,
            playerCount = 4,
            artwork = sampleArtwork,
            controls = listOf(sampleDescriptor(CoreControlId.BUTTON_A)),
            defaults = mapOf(
                0 to PlayerControllerConfig(),
                1 to PlayerControllerConfig(),
                2 to PlayerControllerConfig(),
                3 to PlayerControllerConfig(),
            ),
        )
        assertThat(profile.playerCount).isEqualTo(4)
    }

    @Test
    fun `highlight region bounds validation x negative throws`() {
        assertThatThrownBy {
            ControllerHighlightRegion(
                id = "bad",
                shape = HighlightShape.RECT,
                x = -0.1f,
                y = 0.1f,
                width = 0.2f,
                height = 0.2f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `highlight region bounds validation y greater than 1 throws`() {
        assertThatThrownBy {
            ControllerHighlightRegion(
                id = "bad",
                shape = HighlightShape.RECT,
                x = 0.1f,
                y = 1.1f,
                width = 0.2f,
                height = 0.2f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `highlight region bounds validation x plus width exceeds 1 throws`() {
        assertThatThrownBy {
            ControllerHighlightRegion(
                id = "bad",
                shape = HighlightShape.RECT,
                x = 0.9f,
                y = 0.1f,
                width = 0.2f,
                height = 0.2f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `highlight region bounds validation zero width throws`() {
        assertThatThrownBy {
            ControllerHighlightRegion(
                id = "bad",
                shape = HighlightShape.RECT,
                x = 0.1f,
                y = 0.1f,
                width = 0f,
                height = 0.2f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `highlight region bounds validation negative height throws`() {
        assertThatThrownBy {
            ControllerHighlightRegion(
                id = "bad",
                shape = HighlightShape.RECT,
                x = 0.1f,
                y = 0.1f,
                width = 0.2f,
                height = -0.1f,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }
}

package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.emulation.nativehost.RetroInputDescriptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DescriptorProfileValidator — pure validate()")
class DescriptorProfileValidatorTest {

    private val artwork = ControllerArtwork(
        resourceName = "controller_outline_test",
        source = "Test source",
        license = "MIT",
        licenseAssetPath = null,
        viewBoxWidth = 100f,
        viewBoxHeight = 100f,
    )

    private fun control(
        id: CoreControlId,
        label: String,
        target: LogicalControl,
    ) = CoreControlDescriptor(
        id = id,
        label = label,
        target = target,
        inputKind = InputKind.BUTTON,
        highlightRegion = ControllerHighlightRegion("$id-region", HighlightShape.CIRCLE, 0f, 0f, 0.1f, 0.1f),
    )

    private fun profile(controls: List<CoreControlDescriptor>): CoreControllerProfile {
        val defaults = (0 until 1).associateWith {
            PlayerControllerConfig(emptyMap())
        }
        return CoreControllerProfile(
            coreId = "test_core",
            consoleName = "Test Console",
            consoleSubtitle = null,
            playerCount = 1,
            artwork = artwork,
            controls = controls,
            defaults = defaults,
        )
    }

    // RetroPad descriptors the snes9x-style mapping would advertise for port 0
    // (device=JOYPAD(1), index=0, id=A=8/B=0/...).
    private fun joypadDescriptor(port: Int, id: Int, description: String) =
        RetroInputDescriptor(port = port, device = 1, index = 0, id = id, description = description)

    @Test
    fun `matching descriptors produce no warnings`() {
        val controls = listOf(
            control(CoreControlId.D_PAD_UP, "D-Pad Up", LogicalControl.DPAD_UP),
            control(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A),
        )
        val descriptors = listOf(
            joypadDescriptor(0, DescriptorProfileValidator.retroTarget(LogicalControl.DPAD_UP).id, "D-Pad Up"),
            joypadDescriptor(0, DescriptorProfileValidator.retroTarget(LogicalControl.BUTTON_A).id, "A"),
        )

        val warnings = DescriptorProfileValidator.validate("test_core", descriptors, profile(controls))

        assertThat(warnings).isEmpty()
    }

    @Test
    fun `profile target with no matching descriptor produces a warning`() {
        val controls = listOf(control(CoreControlId.BUTTON_B, "B", LogicalControl.BUTTON_B))
        // Advertise A instead of B, mimicking a vendored core update drifting targets.
        val descriptors = listOf(joypadDescriptor(0, 8, "A"))

        val warnings = DescriptorProfileValidator.validate("test_core", descriptors, profile(controls))

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("test_core").contains("button_b").contains("id=0")
    }

    @Test
    fun `empty descriptor list is handled gracefully with a single note`() {
        val controls = listOf(control(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A))

        val warnings = DescriptorProfileValidator.validate("test_core", emptyList(), profile(controls))

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("no native input descriptors available")
    }

    @Test
    fun `descriptors outside the active port range are ignored`() {
        val controls = listOf(control(CoreControlId.BUTTON_A, "A", LogicalControl.BUTTON_A))
        // Single-player profile (playerCount = 1); descriptor is for port 2 only.
        val descriptors = listOf(joypadDescriptor(2, 8, "A"))

        val warnings = DescriptorProfileValidator.validate("test_core", descriptors, profile(controls))

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("ports 0..0")
    }

    @Test
    fun `analog target without an advertised analog descriptor warns`() {
        val controls = listOf(control(CoreControlId.LEFT_STICK_X, "Left Stick X", LogicalControl.AXIS_LX))
        val descriptors = listOf(joypadDescriptor(0, 8, "A"))

        val warnings = DescriptorProfileValidator.validate("test_core", descriptors, profile(controls))

        assertThat(warnings).hasSize(1)
        assertThat(warnings.single()).contains("device=5")
    }
}

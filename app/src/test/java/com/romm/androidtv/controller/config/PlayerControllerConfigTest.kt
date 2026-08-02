package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PlayerControllerConfig — binding access")
class PlayerControllerConfigTest {

    @Test
    fun `empty config returns null for get`() {
        val config = PlayerControllerConfig()
        assertThat(config[CoreControlId.BUTTON_A]).isNull()
    }

    @Test
    fun `get returns binding for present key`() {
        val binding = PhysicalBinding.Key(23)
        val config = PlayerControllerConfig(
            bindings = mapOf(CoreControlId.BUTTON_A to binding)
        )
        assertThat(config[CoreControlId.BUTTON_A]).isEqualTo(binding)
    }

    @Test
    fun `get returns null for absent key`() {
        val config = PlayerControllerConfig(
            bindings = mapOf(CoreControlId.BUTTON_B to PhysicalBinding.Key(24))
        )
        assertThat(config[CoreControlId.BUTTON_A]).isNull()
    }

    @Test
    fun `data class equality works`() {
        val a = PlayerControllerConfig(mapOf(CoreControlId.BUTTON_A to PhysicalBinding.Key(23)))
        val b = PlayerControllerConfig(mapOf(CoreControlId.BUTTON_A to PhysicalBinding.Key(23)))
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `MAX_PLAYERS equals ControllerSlot SLOT_COUNT`() {
        assertThat(PlayerControllerConfig.MAX_PLAYERS).isEqualTo(4)
    }
}

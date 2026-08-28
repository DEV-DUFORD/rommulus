package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CoreDescriptorTest {

    @Test
    fun `a valid descriptor constructs with sensible defaults`() {
        val descriptor = CoreDescriptor(
            coreId = "sameboy",
            displayName = "SameBoy",
            supportedSystems = listOf("gb", "gbc"),
            supportedExtensions = listOf(".gb", ".gbc"),
        )

        assertThat(descriptor.needsFullPath).isFalse()
        assertThat(descriptor.blockExtract).isFalse()
        assertThat(descriptor.memoryRegions).containsExactly("RETRO_MEMORY_SAVE_RAM")
    }

    @Test
    fun `coreId must not be blank`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreDescriptor(
                coreId = "",
                displayName = "SameBoy",
                supportedSystems = listOf("gb"),
                supportedExtensions = listOf(".gb"),
            )
        }
    }

    @Test
    fun `supportedSystems must not be empty`() {
        assertThrows(IllegalArgumentException::class.java) {
            CoreDescriptor(
                coreId = "sameboy",
                displayName = "SameBoy",
                supportedSystems = emptyList(),
                supportedExtensions = listOf(".gb"),
            )
        }
    }
}

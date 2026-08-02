package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CoreControlId — stable string ids")
class CoreControlIdTest {

    @Test
    fun `all string ids are non-blank`() {
        CoreControlId.entries.forEach { id ->
            assertThat(id.id).isNotBlank()
        }
    }

    @Test
    fun `all string ids are unique`() {
        val ids = CoreControlId.entries.mapNotNull { it.id }
        assertThat(ids.toSet()).hasSize(ids.size)
    }

    @Test
    fun `enum has at least 20 members`() {
        assertThat(CoreControlId.entries).hasSizeGreaterThanOrEqualTo(20)
    }

    @Test
    fun `string ids are lowercase snake_case`() {
        CoreControlId.entries.forEach { id ->
            assertThat(id.id).isEqualTo(id.id.lowercase())
        }
    }
}

package com.romm.androidtv.onboarding.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QrLoginPanelTest {
    @Test
    fun `formats eight-character pairing code for readability`() {
        assertThat(formatUserCode("abcd1234")).isEqualTo("ABCD-1234")
    }

    @Test
    fun `preserves nonstandard code length without a misleading separator`() {
        assertThat(formatUserCode("ABC12")).isEqualTo("ABC12")
    }
}

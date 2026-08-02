package com.romm.androidtv.emulation.process

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EmulationInputRoutingPolicyTest {
    @Test
    fun `gameplay receives input only while no blocking overlay is visible`() {
        assertThat(shouldRouteGameplayInput(true, false, false)).isTrue()
        assertThat(shouldRouteGameplayInput(true, true, false)).isFalse()
        assertThat(shouldRouteGameplayInput(true, false, true)).isFalse()
        assertThat(shouldRouteGameplayInput(false, false, false)).isFalse()
    }
}

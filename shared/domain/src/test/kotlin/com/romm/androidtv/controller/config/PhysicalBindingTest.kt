package com.romm.androidtv.controller.config

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("PhysicalBinding — construction and equality")
class PhysicalBindingTest {

    @Nested
    @DisplayName("Key variant")
    inner class KeyTests {
        @Test
        fun `constructs with any keyCode`() {
            val binding = PhysicalBinding.Key(23)
            assertThat(binding.keyCode).isEqualTo(23)
        }

        @Test
        fun `data class equality works`() {
            assertThat(PhysicalBinding.Key(10)).isEqualTo(PhysicalBinding.Key(10))
            assertThat(PhysicalBinding.Key(10)).isNotEqualTo(PhysicalBinding.Key(11))
        }
    }

    @Nested
    @DisplayName("Axis variant")
    inner class AxisTests {
        @Test
        fun `constructs with any axis constant`() {
            val binding = PhysicalBinding.Axis(0)
            assertThat(binding.axis).isEqualTo(0)
        }

        @Test
        fun `data class equality works`() {
            assertThat(PhysicalBinding.Axis(8)).isEqualTo(PhysicalBinding.Axis(8))
            assertThat(PhysicalBinding.Axis(8)).isNotEqualTo(PhysicalBinding.Axis(9))
        }
    }

    @Nested
    @DisplayName("AxisDirection variant")
    inner class AxisDirectionTests {
        @Test
        fun `accepts polarity -1`() {
            val binding = PhysicalBinding.AxisDirection(0, -1)
            assertThat(binding.polarity).isEqualTo(-1)
        }

        @Test
        fun `accepts polarity +1`() {
            val binding = PhysicalBinding.AxisDirection(0, 1)
            assertThat(binding.polarity).isEqualTo(1)
        }

        @Test
        fun `rejects polarity 0`() {
            assertThatThrownBy {
                PhysicalBinding.AxisDirection(0, 0)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `rejects polarity 2`() {
            assertThatThrownBy {
                PhysicalBinding.AxisDirection(0, 2)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }

        @Test
        fun `data class equality works`() {
            assertThat(PhysicalBinding.AxisDirection(0, -1))
                .isEqualTo(PhysicalBinding.AxisDirection(0, -1))
            assertThat(PhysicalBinding.AxisDirection(0, -1))
                .isNotEqualTo(PhysicalBinding.AxisDirection(0, 1))
        }
    }

    @Nested
    @DisplayName("Cross-variant inequality")
    inner class CrossVariantTests {
        @Test
        fun `different variants are never equal`() {
            assertThat(PhysicalBinding.Key(10))
                .isNotEqualTo(PhysicalBinding.Axis(10))
            assertThat(PhysicalBinding.Axis(0))
                .isNotEqualTo(PhysicalBinding.AxisDirection(0, 1))
        }
    }
}

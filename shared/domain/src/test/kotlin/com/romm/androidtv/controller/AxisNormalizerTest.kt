package com.romm.androidtv.controller

import com.romm.androidtv.controller.util.AxisNormalizer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for AxisNormalizer.
 * Validates correct handling of MotionRange.flat as a single threshold,
 * not a min/max pair. This is the primary defect that was fixed.
 */
@DisplayName("AxisNormalizer — hardware deadzone and normalization")
class AxisNormalizerTest {

    @Nested
    @DisplayName("Flat region (hardware deadzone)")
    inner class FlatRegionTests {
        @Test
        @DisplayName("flat=0.1 zeroes values in [-0.1, +0.1]")
        fun `flat zeroes within range`() {
            assertThat(AxisNormalizer.normalize(0f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(0.05f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(-0.05f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(0.1f, -1f, 1f, 0.1f)).isZero()
            assertThat(AxisNormalizer.normalize(-0.1f, -1f, 1f, 0.1f)).isZero()
        }

        @Test
        @DisplayName("flat=0.1 passes values outside [-0.1, +0.1]")
        fun `flat passes outside range`() {
            val pos = AxisNormalizer.normalize(0.11f, -1f, 1f, 0.1f)
            assertThat(pos).isGreaterThan(0f)
            val neg = AxisNormalizer.normalize(-0.11f, -1f, 1f, 0.1f)
            assertThat(neg).isLessThan(0f)
        }

        @Test
        @DisplayName("flat=0 passes all values through")
        fun `zero flat passes all`() {
            assertThat(AxisNormalizer.normalize(0f, -1f, 1f, 0f)).isZero()
            assertThat(AxisNormalizer.normalize(0.001f, -1f, 1f, 0f))
                .isCloseTo(0.001f, org.assertj.core.data.Offset.offset(0.0001f))
            assertThat(AxisNormalizer.normalize(-0.001f, -1f, 1f, 0f))
                .isCloseTo(-0.001f, org.assertj.core.data.Offset.offset(0.0001f))
        }

        @Test
        @DisplayName("large flat covers most of the range")
        fun `large flat`() {
            // flat=0.9: only values > 0.9 or < -0.9 pass through
            assertThat(AxisNormalizer.normalize(0.5f, -1f, 1f, 0.9f)).isZero()
            assertThat(AxisNormalizer.normalize(0.95f, -1f, 1f, 0.9f)).isGreaterThan(0f)
        }
    }

    @Nested
    @DisplayName("Range normalization")
    inner class RangeNormalizationTests {
        @Test
        @DisplayName("standard [-1, +1] range maps directly")
        fun `standard range`() {
            assertThat(AxisNormalizer.normalize(-1f, -1f, 1f, 0f)).isEqualTo(-1f)
            assertThat(AxisNormalizer.normalize(0f, -1f, 1f, 0f)).isZero()
            assertThat(AxisNormalizer.normalize(1f, -1f, 1f, 0f)).isEqualTo(1f)
        }

        @Test
        @DisplayName("[0, 255] range normalizes correctly")
        fun `byte range`() {
            assertThat(AxisNormalizer.normalize(0f, 0f, 255f, 0f)).isEqualTo(-1f)
            assertThat(AxisNormalizer.normalize(127.5f, 0f, 255f, 0f))
                .isCloseTo(0f, org.assertj.core.data.Offset.offset(0.01f))
            assertThat(AxisNormalizer.normalize(255f, 0f, 255f, 0f)).isEqualTo(1f)
        }

        @Test
        @DisplayName("unsigned flat is applied around the real center")
        fun `unsigned centered flat`() {
            assertThat(AxisNormalizer.normalize(32768f, 0f, 65535f, 4096f)).isZero()
            assertThat(AxisNormalizer.normalize(35000f, 0f, 65535f, 4096f)).isZero()
            assertThat(AxisNormalizer.normalize(45000f, 0f, 65535f, 4096f)).isGreaterThan(0f)
        }

        @Test
        @DisplayName("[32768, 32767] signed 16-bit range normalizes")
        fun `signed 16 bit range`() {
            assertThat(AxisNormalizer.normalize(-32768f, -32768f, 32767f, 0f)).isEqualTo(-1f)
            assertThat(AxisNormalizer.normalize(0f, -32768f, 32767f, 0f)).isCloseTo(
                -0.000015f, org.assertj.core.data.Offset.offset(0.0001f)
            )
            assertThat(AxisNormalizer.normalize(32767f, -32768f, 32767f, 0f)).isEqualTo(1f)
        }

        @Nested
        @DisplayName("Trigger normalization")
        inner class TriggerTests {
            @Test
            fun `minus one to one trigger range maps to zero through one`() {
                assertThat(AxisNormalizer.normalizeTrigger(-1f, -1f, 1f, 0f)).isZero()
                assertThat(AxisNormalizer.normalizeTrigger(0f, -1f, 1f, 0f)).isEqualTo(0.5f)
                assertThat(AxisNormalizer.normalizeTrigger(1f, -1f, 1f, 0f)).isEqualTo(1f)
            }
        }

        @Test
        @DisplayName("out-of-range values are clamped to [-1, +1]")
        fun `clamp out of range`() {
            assertThat(AxisNormalizer.normalize(2f, -1f, 1f, 0f)).isEqualTo(1f)
            assertThat(AxisNormalizer.normalize(-2f, -1f, 1f, 0f)).isEqualTo(-1f)
        }
    }

    @Nested
    @DisplayName("Fallback normalization")
    inner class FallbackTests {
        @Test
        @DisplayName("fallback clamps to [-1, +1]")
        fun `fallback clamps`() {
            assertThat(AxisNormalizer.normalizeFallback(0.5f)).isEqualTo(0.5f)
            assertThat(AxisNormalizer.normalizeFallback(2.0f)).isEqualTo(1f)
            assertThat(AxisNormalizer.normalizeFallback(-3.0f)).isEqualTo(-1f)
        }
    }

    @Nested
    @DisplayName("Combined flat + normalization")
    inner class CombinedTests {
        @Test
        @DisplayName("flat applied before normalization, result still in [-1, +1]")
        fun `flat then normalize`() {
            // Device range [-1000, 1000], flat=50
            // Value 60 is just outside flat region
            val result = AxisNormalizer.normalize(60f, -1000f, 1000f, 50f)
            assertThat(result).isGreaterThan(0f)
            assertThat(result).isLessThanOrEqualTo(1f)
        }

        @Test
        @DisplayName("value just inside flat returns exactly 0")
        fun `inside flat is zero`() {
            assertThat(AxisNormalizer.normalize(49f, -1000f, 1000f, 50f)).isZero()
            assertThat(AxisNormalizer.normalize(-49f, -1000f, 1000f, 50f)).isZero()
        }
    }
}

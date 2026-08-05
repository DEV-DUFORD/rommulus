package com.romm.androidtv.emulation.video

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure-JVM geometry tests for [calculateScanlineBands].
 *
 * These tests exercise the algorithm directly — no Compose runtime, no Canvas —
 * so they run on plain JUnit 5 / AssertJ without needing the Android test harness.
 */
@DisplayName("ScanlineGeometryTest — pure band geometry")
class ScanlineGeometryTest {

    @Test
    fun `zero canvas height returns no bands`() {
        val bands = calculateScanlineBands(0f, 240)
        assertThat(bands).isEmpty()
    }

    @Test
    fun `zero core height returns no bands`() {
        val bands = calculateScanlineBands(480f, 0)
        assertThat(bands).isEmpty()
    }

    @Test
    fun `negative canvas height returns no bands`() {
        val bands = calculateScanlineBands(-100f, 240)
        assertThat(bands).isEmpty()
    }

    @Test
    fun `negative core height returns no bands`() {
        val bands = calculateScanlineBands(480f, -1)
        assertThat(bands).isEmpty()
    }

    @Test
    fun `bands remain inside canvas bounds`() {
        val bands = calculateScanlineBands(480f, 240)
        for (band in bands) {
            assertThat(band.topPx).isGreaterThanOrEqualTo(0f)
            assertThat(band.topPx + band.heightPx).isLessThanOrEqualTo(480f)
        }
    }

    @Test
    fun `bands are sorted by topPx ascending`() {
        val bands = calculateScanlineBands(480f, 240)
        for (i in 1 until bands.size) {
            assertThat(bands[i].topPx).isGreaterThanOrEqualTo(bands[i - 1].topPx)
        }
    }

    @Test
    fun `bands are non-overlapping`() {
        val bands = calculateScanlineBands(480f, 240)
        for (i in 0 until bands.size - 1) {
            val currentBottom = bands[i].topPx + bands[i].heightPx
            val nextTop = bands[i + 1].topPx
            assertThat(nextTop).isGreaterThanOrEqualTo(currentBottom)
        }
    }

    @Test
    fun `normal 240-line output produces 120 bands at 480 canvas height`() {
        // 480 output pixels, 240 source rows -> every 2nd source row -> 120 bands.
        val bands = calculateScanlineBands(480f, 240)
        assertThat(bands).hasSize(120)
    }

    @Test
    fun `fractional scaling produces stable pixel-aligned bands`() {
        // Sub-pixel source row height: ensure the bands are stable and pixel-aligned.
        val bands = calculateScanlineBands(500f, 240)
        for (band in bands) {
            // After pixel rounding the top should be a whole number.
            assertThat(band.topPx).isEqualTo(kotlin.math.round(band.topPx).toInt().toFloat())
        }
        // Should still produce coreHeight/2 bands (every second source row).
        assertThat(bands).hasSize(120)
    }

    @Test
    fun `band thickness never drops below one physical pixel`() {
        val cases = listOf(480f to 240, 720f to 240, 1080f to 240, 480f to 480, 480f to 720)
        for ((height, core) in cases) {
            val bands = calculateScanlineBands(height, core)
            for (band in bands) {
                assertThat(band.heightPx)
                    .withFailMessage("band at topPx=%f too thin: %f", band.topPx, band.heightPx)
                    .isGreaterThanOrEqualTo(1f)
            }
        }
    }

    @Test
    fun `band thickness never exceeds three physical pixels`() {
        val cases = listOf(480f to 240, 720f to 240, 1080f to 240, 480f to 480, 480f to 720)
        for ((height, core) in cases) {
            val bands = calculateScanlineBands(height, core)
            for (band in bands) {
                assertThat(band.heightPx)
                    .withFailMessage("band at topPx=%f too thick: %f", band.topPx, band.heightPx)
                    .isLessThanOrEqualTo(3f)
            }
        }
    }

    @Test
    fun `downscaled source uses one-pixel fallback per two output pixels`() {
        // canvasHeight = 100, coreHeight = 400 -> sourceRowHeight = 0.25 < 1f.
        val bands = calculateScanlineBands(100f, 400)
        // Every 2 output pixels -> one 1px band -> 50 bands.
        assertThat(bands).hasSize(50)
        for (band in bands) {
            assertThat(band.heightPx).isEqualTo(1f)
        }
    }

    @Test
    fun `identical inputs return identical bands`() {
        val a = calculateScanlineBands(480f, 240)
        val b = calculateScanlineBands(480f, 240)
        assertThat(b).isEqualTo(a)
        // Reference equality not guaranteed by data class, but value equality is.
        for (i in a.indices) {
            assertThat(b[i].topPx).isEqualTo(a[i].topPx)
            assertThat(b[i].heightPx).isEqualTo(a[i].heightPx)
        }
    }

    @Test
    fun `edge-case 480 canvas with 480 source rows produces 240 bands`() {
        // 480 output pixels, 480 source rows -> every 2nd row -> 240 bands.
        val bands = calculateScanlineBands(480f, 480)
        assertThat(bands).hasSize(240)
    }

    @Test
    fun `single source row case produces one band (row 0 is even)`() {
        // coreHeight = 1: row 0 (even) is the only row, so one band is produced.
        val bands = calculateScanlineBands(100f, 1)
        assertThat(bands).hasSize(1)
        // bandTop = (100 - 3)/2 = 48.5; kotlin.math.round() uses banker's rounding -> 48.
        assertThat(bands[0].topPx).isEqualTo(48f)
        assertThat(bands[0].heightPx).isEqualTo(3f)
    }

    @Test
    fun `very small canvas with huge core uses fallback`() {
        // canvas 10, core 1000 -> sourceRowHeight = 0.01 < 1f -> fallback.
        val bands = calculateScanlineBands(10f, 1000)
        assertThat(bands).hasSize(5)
        for (band in bands) {
            assertThat(band.heightPx).isEqualTo(1f)
        }
    }
}

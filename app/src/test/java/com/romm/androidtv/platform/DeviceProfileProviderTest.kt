package com.romm.androidtv.platform

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DeviceProfileProviderTest {

    // ── Width classification ──

    @Test
    fun `classifyWidth returns COMPACT below 600 dp`() {
        assertThat(classifyWidth(0.dp)).isEqualTo(WindowWidthClass.COMPACT)
        assertThat(classifyWidth(299.dp)).isEqualTo(WindowWidthClass.COMPACT)
        assertThat(classifyWidth(599.dp)).isEqualTo(WindowWidthClass.COMPACT)
    }

    @Test
    fun `classifyWidth returns MEDIUM from 600 to 839 dp`() {
        assertThat(classifyWidth(600.dp)).isEqualTo(WindowWidthClass.MEDIUM)
        assertThat(classifyWidth(720.dp)).isEqualTo(WindowWidthClass.MEDIUM)
        assertThat(classifyWidth(839.dp)).isEqualTo(WindowWidthClass.MEDIUM)
    }

    @Test
    fun `classifyWidth returns EXPANDED at 840 and above`() {
        assertThat(classifyWidth(840.dp)).isEqualTo(WindowWidthClass.EXPANDED)
        assertThat(classifyWidth(1080.dp)).isEqualTo(WindowWidthClass.EXPANDED)
        assertThat(classifyWidth(1920.dp)).isEqualTo(WindowWidthClass.EXPANDED)
    }

    // ── Height classification ──

    @Test
    fun `classifyHeight returns COMPACT below 480 dp`() {
        assertThat(classifyHeight(0.dp)).isEqualTo(WindowHeightClass.COMPACT)
        assertThat(classifyHeight(239.dp)).isEqualTo(WindowHeightClass.COMPACT)
        assertThat(classifyHeight(479.dp)).isEqualTo(WindowHeightClass.COMPACT)
    }

    @Test
    fun `classifyHeight returns MEDIUM from 480 to 899 dp`() {
        assertThat(classifyHeight(480.dp)).isEqualTo(WindowHeightClass.MEDIUM)
        assertThat(classifyHeight(640.dp)).isEqualTo(WindowHeightClass.MEDIUM)
        assertThat(classifyHeight(899.dp)).isEqualTo(WindowHeightClass.MEDIUM)
    }

    @Test
    fun `classifyHeight returns EXPANDED at 900 and above`() {
        assertThat(classifyHeight(900.dp)).isEqualTo(WindowHeightClass.EXPANDED)
        assertThat(classifyHeight(1280.dp)).isEqualTo(WindowHeightClass.EXPANDED)
        assertThat(classifyHeight(2160.dp)).isEqualTo(WindowHeightClass.EXPANDED)
    }
}

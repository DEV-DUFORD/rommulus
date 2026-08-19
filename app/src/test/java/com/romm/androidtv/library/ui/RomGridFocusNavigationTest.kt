package com.romm.androidtv.library.ui

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RomGridFocusNavigationTest {

    @Test
    fun `moving down retains the card column`() {
        assertThat(
            positionalGridNeighbor(
                currentIndex = 6,
                columnCount = 4,
                itemCount = 20,
                moveDown = true,
            ),
        ).isEqualTo(10)
    }

    @Test
    fun `moving up retains the card column`() {
        assertThat(
            positionalGridNeighbor(
                currentIndex = 14,
                columnCount = 4,
                itemCount = 20,
                moveDown = false,
            ),
        ).isEqualTo(10)
    }

    @Test
    fun `moving beyond a grid edge leaves focus navigation to Compose`() {
        assertThat(positionalGridNeighbor(2, 4, 20, moveDown = false)).isNull()
        assertThat(positionalGridNeighbor(18, 4, 20, moveDown = true)).isNull()
    }
}

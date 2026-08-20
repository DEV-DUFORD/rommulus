package com.romm.androidtv.controller

import com.romm.androidtv.controller.model.PauseMenuCombination
import com.romm.androidtv.controller.model.PhysicalControl
import com.romm.androidtv.controller.router.isPauseMenuCombinationPressed
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PauseMenuCombinationTest {
    @Test
    fun `requires both configured button inputs`() {
        val combination = PauseMenuCombination(
            PhysicalControl.Key(android.view.KeyEvent.KEYCODE_BUTTON_SELECT),
            PhysicalControl.Key(android.view.KeyEvent.KEYCODE_BUTTON_START),
        )

        assertThat(
            isPauseMenuCombinationPressed(
                combination,
                setOf(android.view.KeyEvent.KEYCODE_BUTTON_SELECT),
                emptyMap(),
            ),
        ).isFalse()
        assertThat(
            isPauseMenuCombinationPressed(
                combination,
                setOf(
                    android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
                    android.view.KeyEvent.KEYCODE_BUTTON_START,
                ),
                emptyMap(),
            ),
        ).isTrue()
    }

    @Test
    fun `supports trigger half-axis as either pause input`() {
        val combination = PauseMenuCombination(
            PhysicalControl.AxisDirection(android.view.MotionEvent.AXIS_LTRIGGER, 1),
            PhysicalControl.Key(android.view.KeyEvent.KEYCODE_BUTTON_A),
        )

        assertThat(
            isPauseMenuCombinationPressed(
                combination,
                setOf(android.view.KeyEvent.KEYCODE_BUTTON_A),
                mapOf(android.view.MotionEvent.AXIS_LTRIGGER to 0.49f),
            ),
        ).isFalse()
        assertThat(
            isPauseMenuCombinationPressed(
                combination,
                setOf(android.view.KeyEvent.KEYCODE_BUTTON_A),
                mapOf(android.view.MotionEvent.AXIS_LTRIGGER to 0.8f),
            ),
        ).isTrue()
    }
}

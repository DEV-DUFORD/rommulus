package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.GamepadSnapshot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TouchInputMergerTest {

    private fun snap(buttons: FloatArray? = null, axes: FloatArray? = null): GamepadSnapshot =
        GamepadSnapshot(
            buttons = buttons ?: FloatArray(16),
            axes = axes ?: FloatArray(6)
        )

    private fun btnAt(index: Int, value: Float = 1f): FloatArray {
        val a = FloatArray(16)
        a[index] = value
        return a
    }

    private fun axisAt(index: Int, value: Float): FloatArray {
        val a = FloatArray(6)
        a[index] = value
        return a
    }

    // ── Button OR ──

    @Test
    fun `merge buttons both pressed yields pressed`() {
        val physical = snap(buttons = btnAt(0))
        val touch = snap(buttons = btnAt(0))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.buttons[0]).isEqualTo(1f)
    }

    @Test
    fun `merge buttons physical pressed alone yields pressed`() {
        val physical = snap(buttons = btnAt(0))
        val touch = snap()
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.buttons[0]).isEqualTo(1f)
    }

    @Test
    fun `merge buttons touch pressed alone yields pressed`() {
        val physical = snap()
        val touch = snap(buttons = btnAt(0))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.buttons[0]).isEqualTo(1f)
    }

    @Test
    fun `merge buttons both released yields released`() {
        val physical = snap()
        val touch = snap()
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.buttons[0]).isEqualTo(0f)
    }

    // ── Axis precedence ──

    @Test
    fun `merge axes touch above deadzone overrides physical`() {
        val physical = snap(axes = axisAt(0, 0.8f))
        val touch = snap(axes = axisAt(0, 0.9f))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.axes[0]).isEqualTo(0.9f)
    }

    @Test
    fun `merge axes touch within deadzone preserves physical`() {
        val physical = snap(axes = axisAt(0, 0.8f))
        val touch = snap(axes = axisAt(0, 0.15f))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.axes[0]).isEqualTo(0.8f)
    }

    @Test
    fun `merge axes touch exactly at deadzone preserves physical`() {
        val physical = snap(axes = axisAt(0, 0.5f))
        val touch = snap(axes = axisAt(0, TouchInputMerger.AXIS_DEADZONE))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.axes[0]).isEqualTo(0.5f)
    }

    @Test
    fun `merge axes touch zero preserves physical`() {
        val physical = snap(axes = axisAt(0, -0.7f))
        val touch = snap()
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.axes[0]).isEqualTo(-0.7f)
    }

    @Test
    fun `merge axes negative touch above deadzone overrides physical`() {
        val physical = snap(axes = axisAt(0, 0.3f))
        val touch = snap(axes = axisAt(0, -0.9f))
        val merged = TouchInputMerger.merge(physical, touch)
        assertThat(merged.axes[0]).isEqualTo(-0.9f)
    }
}

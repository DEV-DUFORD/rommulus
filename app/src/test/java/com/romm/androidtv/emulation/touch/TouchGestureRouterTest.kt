package com.romm.androidtv.emulation.touch

import com.romm.androidtv.controller.model.LogicalControl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TouchGestureRouterTest {

    private val dpad = TouchHitRegion.Dpad(
        bounds = TouchBounds(100f, 100f, 100f, 100f),
        up = LogicalControl.DPAD_UP,
        down = LogicalControl.DPAD_DOWN,
        left = LogicalControl.DPAD_LEFT,
        right = LogicalControl.DPAD_RIGHT,
    )

    @Test
    fun `dragging around dpad transitions through diagonal without lifting`() {
        val left = resolveTouchGestureFrame(listOf(dpad), listOf(TouchPoint(55f, 100f)))
        val diagonal = resolveTouchGestureFrame(listOf(dpad), listOf(TouchPoint(65f, 135f)))
        val down = resolveTouchGestureFrame(listOf(dpad), listOf(TouchPoint(100f, 145f)))

        assertThat(left.buttons).containsExactly(LogicalControl.DPAD_LEFT)
        assertThat(diagonal.buttons).containsExactlyInAnyOrder(
            LogicalControl.DPAD_LEFT,
            LogicalControl.DPAD_DOWN,
        )
        assertThat(down.buttons).containsExactly(LogicalControl.DPAD_DOWN)
    }

    @Test
    fun `one thumb between adjacent face buttons presses both`() {
        val run = TouchHitRegion.Button(
            bounds = TouchBounds(60f, 100f, 50f, 50f),
            target = LogicalControl.BUTTON_Y,
            shape = TouchControlShape.CIRCLE,
        )
        val jump = TouchHitRegion.Button(
            bounds = TouchBounds(140f, 100f, 50f, 50f),
            target = LogicalControl.BUTTON_B,
            shape = TouchControlShape.CIRCLE,
        )

        val frame = resolveTouchGestureFrame(
            regions = listOf(run, jump),
            pointers = listOf(TouchPoint(100f, 100f)),
        )

        assertThat(frame.buttons).containsExactlyInAnyOrder(
            LogicalControl.BUTTON_Y,
            LogicalControl.BUTTON_B,
        )
    }

    @Test
    fun `multiple pointers combine dpad and face buttons`() {
        val button = TouchHitRegion.Button(
            bounds = TouchBounds(300f, 100f, 60f, 60f),
            target = LogicalControl.BUTTON_A,
            shape = TouchControlShape.CIRCLE,
        )

        val frame = resolveTouchGestureFrame(
            regions = listOf(dpad, button),
            pointers = listOf(TouchPoint(55f, 100f), TouchPoint(300f, 100f)),
        )

        assertThat(frame.buttons).containsExactlyInAnyOrder(
            LogicalControl.DPAD_LEFT,
            LogicalControl.BUTTON_A,
        )
    }

    @Test
    fun `moving off all controls releases every button`() {
        val frame = resolveTouchGestureFrame(
            regions = listOf(dpad),
            pointers = listOf(TouchPoint(400f, 400f)),
        )

        assertThat(frame.buttons).isEmpty()
        assertThat(frame.axes).isEmpty()
    }

    @Test
    fun `analog stick continuously follows pointer position`() {
        val stick = TouchHitRegion.Stick(
            bounds = TouchBounds(100f, 100f, 100f, 100f),
            xAxis = LogicalControl.AXIS_LX,
            yAxis = LogicalControl.AXIS_LY,
        )

        val frame = resolveTouchGestureFrame(
            regions = listOf(stick),
            pointers = listOf(TouchPoint(125f, 75f)),
        )

        assertThat(frame.axes[LogicalControl.AXIS_LX]).isEqualTo(.5f)
        assertThat(frame.axes[LogicalControl.AXIS_LY]).isEqualTo(-.5f)
    }
}

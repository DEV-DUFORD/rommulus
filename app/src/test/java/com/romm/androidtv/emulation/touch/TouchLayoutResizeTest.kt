package com.romm.androidtv.emulation.touch

import androidx.compose.ui.unit.dp
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TouchLayoutResizeTest {

    @Test
    fun `dpad resize changes rendered width and height proportionally`() {
        val dpad = DefaultTouchLayouts.forCore("snes9x")!!.controls
            .filterIsInstance<TouchControlDefinition.Dpad>()
            .single()
        val viewportWidth = 884.dp
        val viewportHeight = 1104.dp

        val resizedSize = proportionallyResizedSize(dpad, .9f, viewportWidth, viewportHeight)
        val resized = dpad.copy(size = resizedSize)
        val before = renderedSize(dpad, viewportWidth, viewportHeight)
        val after = renderedSize(resized, viewportWidth, viewportHeight)

        assertThat(after.width.value / before.width.value).isEqualTo(.9f)
        assertThat(after.height.value / before.height.value).isEqualTo(.9f)
        assertThat(after.width).isEqualTo(after.height)
    }

    @Test
    fun `repeated dpad resize remains square`() {
        val original = DefaultTouchLayouts.forCore("snes9x")!!.controls
            .filterIsInstance<TouchControlDefinition.Dpad>()
            .single()
        val viewportWidth = 884.dp
        val viewportHeight = 1104.dp
        var current = original

        repeat(5) {
            current = current.copy(
                size = proportionallyResizedSize(current, .9f, viewportWidth, viewportHeight),
            )
            val rendered = renderedSize(current, viewportWidth, viewportHeight)
            assertThat(rendered.width).isEqualTo(rendered.height)
        }
    }

    @Test
    fun `dpad can be reduced below the previous 144dp floor`() {
        val original = DefaultTouchLayouts.forCore("snes9x")!!.controls
            .filterIsInstance<TouchControlDefinition.Dpad>()
            .single()
        val viewportWidth = 884.dp
        val viewportHeight = 1104.dp
        var current = original

        repeat(12) {
            current = current.copy(
                size = proportionallyResizedSize(current, .9f, viewportWidth, viewportHeight),
            )
        }

        assertThat(renderedSize(current, viewportWidth, viewportHeight).width)
            .isLessThan(144.dp)
    }
}

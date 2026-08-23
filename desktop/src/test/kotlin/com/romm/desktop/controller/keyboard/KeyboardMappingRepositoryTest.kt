package com.romm.desktop.controller.keyboard

import androidx.compose.ui.input.key.Key
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.storage.fakes.InMemoryControllerBindingStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyboardMappingRepositoryTest {
    @Test
    fun `compose letter keys map to SDL scancodes`() {
        assertEquals(4, keyboardKeyFor(Key.A)?.scancode)
        assertEquals(29, keyboardKeyFor(Key.Z)?.scancode)
    }

    @Test
    fun `defaults preserve existing keyboard controls`() {
        val repository = KeyboardMappingRepository(InMemoryControllerBindingStore())

        val rows = repository.observe("snes9x").value.associateBy { it.target }

        assertEquals(26, rows.getValue("dpad_up").primaryScancode)
        assertEquals(82, rows.getValue("dpad_up").secondaryScancode)
        assertEquals(40, rows.getValue("a").primaryScancode)
    }

    @Test
    fun `explicitly cleared mapping does not fall back to default`() {
        val repository = KeyboardMappingRepository(InMemoryControllerBindingStore())

        repository.set("snes9x", "a", BindingSlot.PRIMARY, null)

        assertNull(repository.observe("snes9x").value.first { it.target == "a" }.primaryScancode)
        assertNull(repository.launchBindings("snes9x")!!.bindings.first { it.target == "a" }.primaryScancode)
    }

    @Test
    fun `analog cores expose directional keyboard targets`() {
        val repository = KeyboardMappingRepository(InMemoryControllerBindingStore())

        val targets = repository.observe("mupen64plus_next").value.map { it.target }

        assertEquals(
            listOf("left_x_negative", "left_x_positive", "left_y_negative", "left_y_positive"),
            targets.filter { it.startsWith("left_x_") || it.startsWith("left_y_") },
        )
    }

    @Test
    fun `keyboard sidecar round trips into launch table`() {
        val json = buildString {
            append("""{"protocolVersion":1,"bindings":[""")
            KEYBOARD_TARGETS.forEachIndexed { index, target ->
                if (index > 0) append(',')
                append("""{"target":"$target","primaryScancode":null,"secondaryScancode":null}""")
            }
            append("]}")
        }

        val parsed = KeyboardBindingSidecarCodec.parse(json).getOrThrow()

        assertEquals(KEYBOARD_TARGETS, parsed.bindings.map { it.target })
    }
}

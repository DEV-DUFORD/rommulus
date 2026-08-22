package com.romm.desktop.controller

import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.model.NeutralAxis
import net.java.games.input.Component
import net.java.games.input.Controller
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy

class JInputControllerSourceTest {

    @Test
    fun `poll refreshes the controller before reading component data`() {
        var refreshed = false
        val component = component(Component.Identifier.Button.A) { if (refreshed) 1f else 0f }
        val controller = controller(arrayOf(component)) {
            refreshed = true
            true
        }

        val state = LiveJInputController(controller).poll()

        assertThat(refreshed).isTrue()
        assertThat(state.buttons).containsExactly(NeutralKey.BUTTON_A)
    }

    @Test
    fun `POV dpad values map cardinal and diagonal directions`() {
        val expectations = mapOf(
            Component.POV.UP to setOf(NeutralKey.DPAD_UP),
            Component.POV.RIGHT to setOf(NeutralKey.DPAD_RIGHT),
            Component.POV.DOWN to setOf(NeutralKey.DPAD_DOWN),
            Component.POV.LEFT to setOf(NeutralKey.DPAD_LEFT),
            Component.POV.UP_RIGHT to setOf(NeutralKey.DPAD_UP, NeutralKey.DPAD_RIGHT),
        )

        for ((value, expected) in expectations) {
            val pov = component(Component.Identifier.Axis.POV) { value }
            val state = LiveJInputController(controller(arrayOf(pov))).poll()
            assertThat(state.buttons).containsExactlyInAnyOrderElementsOf(expected)
        }
    }

    @Test
    fun `Linux numeric gamepad buttons map to neutral controls`() {
        val state = LiveJInputController(
            controller(
                arrayOf(
                    component(Component.Identifier.Button._0) { 1f },
                    component(Component.Identifier.Button._7) { 1f },
                ),
            ),
        ).poll()

        assertThat(state.buttons).containsExactlyInAnyOrder(
            NeutralKey.BUTTON_A,
            NeutralKey.BUTTON_START,
        )
    }

    @Test
    fun `slider axes are not mistaken for dpad input`() {
        val slider = component(Component.Identifier.Axis.SLIDER) { 1f }

        val state = LiveJInputController(controller(arrayOf(slider))).poll()

        assertThat(state.buttons).isEmpty()
    }

    @Test
    fun `Linux Z and RZ axes normalize as triggers with minus one at rest`() {
        val state = LiveJInputController(
            controller(
                arrayOf(
                    component(Component.Identifier.Axis.Z) { -1f },
                    component(Component.Identifier.Axis.RZ) { 1f },
                ),
            ),
        ).poll()

        assertThat(state.axes[NeutralAxis.LTRIGGER]).isZero()
        assertThat(state.axes[NeutralAxis.RTRIGGER]).isEqualTo(1f)
    }

    @Test
    fun `only gamepads and sticks are eligible controller devices`() {
        assertThat(controller(emptyArray(), type = Controller.Type.GAMEPAD).isGameController()).isTrue()
        assertThat(controller(emptyArray(), type = Controller.Type.STICK).isGameController()).isTrue()
        assertThat(controller(emptyArray(), type = Controller.Type.KEYBOARD).isGameController()).isFalse()
        assertThat(controller(emptyArray(), type = Controller.Type.MOUSE).isGameController()).isFalse()
    }

    private fun component(
        identifier: Component.Identifier,
        pollData: () -> Float,
    ): Component = proxy { method ->
        when (method.name) {
            "getIdentifier" -> identifier
            "getPollData" -> pollData()
            "getDeadZone" -> 0f
            "getName" -> identifier.name
            "isRelative", "isAnalog" -> false
            else -> error("Unexpected Component method: ${method.name}")
        }
    }

    private fun controller(
        components: Array<Component>,
        type: Controller.Type = Controller.Type.GAMEPAD,
        poll: () -> Boolean = { true },
    ): Controller = proxy { method ->
        when (method.name) {
            "getName" -> "Test pad"
            "getType" -> type
            "getComponents" -> components
            "poll" -> poll()
            else -> error("Unexpected Controller method: ${method.name}")
        }
    }

    private inline fun <reified T> proxy(crossinline call: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            call(method)
        } as T
}

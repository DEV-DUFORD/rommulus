package com.romm.desktop.controller

import com.romm.androidtv.controller.model.DeviceSignature
import com.romm.androidtv.controller.model.GamepadSnapshot
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.model.SlotConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.description.TextDescription
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DesktopControllerRouter — Phase 6 desktop controller routing")
class DesktopControllerRouterTest {

    private fun scope(): CoroutineScope = CoroutineScope(Dispatchers.Unconfined)

    // ---------------------------------------------------------------- fakes

    private class FakeJInputSource : JInputSource {
        val controllers = mutableListOf<FakeJInputController>()

        override fun enumerate(): List<JInputController> = controllers.toList()

        fun addController(
            id: String = "pad-${controllers.size + 1}",
            buttons: Set<NeutralKey> = emptySet(),
            axes: Map<NeutralAxis, Float> = emptyMap(),
        ): FakeJInputController {
            val controller = FakeJInputController(id, buttons, axes)
            controllers += controller
            return controller
        }

        fun remove(controller: JInputController) {
            controllers.removeAll { it.id == controller.id }
        }
    }

    private class FakeJInputController(
        override val id: String,
        var buttons: Set<NeutralKey> = emptySet(),
        var axes: Map<NeutralAxis, Float> = emptyMap(),
    ) : JInputController {
        var pollCount: Int = 0

        override val signature: DeviceSignature =
            DeviceSignature(descriptor = "fake:$id", vendorId = 0, productId = 0, name = id)

        override fun poll(): JInputControllerState {
            pollCount++
            return JInputControllerState(buttons, axes)
        }
    }

    // ---------------------------------------------------------------- slot assignment

    @Test
    fun `empty source yields four unassigned slots`() {
        val router = DesktopControllerRouter(FakeJInputSource(), scope())
        router.tick()

        assertThat(router.slots.value).hasSize(4)
        assertThat(router.slots.value).allMatch { it.connectionState == SlotConnectionState.UNASSIGNED }
    }

    @Test
    fun `button press maps to snapshot button and connected slot`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(buttons = setOf(NeutralKey.BUTTON_A))
        router.tick()

        val slot = router.slots.value[0]
        assertThat(slot.connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        assertThat(slot.currentSnapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)
        assertThat(slot.currentSnapshot.isAnyButtonPressed).isTrue()
    }

    @Test
    fun `every neutral key maps to its expected logical button`() {
        val expectations = mapOf(
            NeutralKey.BUTTON_A to LogicalControl.BUTTON_A,
            NeutralKey.BUTTON_B to LogicalControl.BUTTON_B,
            NeutralKey.BUTTON_X to LogicalControl.BUTTON_X,
            NeutralKey.BUTTON_Y to LogicalControl.BUTTON_Y,
            NeutralKey.BUTTON_L1 to LogicalControl.BUTTON_LB,
            NeutralKey.BUTTON_R1 to LogicalControl.BUTTON_RB,
            NeutralKey.BUTTON_SELECT to LogicalControl.BUTTON_SELECT,
            NeutralKey.BUTTON_START to LogicalControl.BUTTON_START,
            NeutralKey.BUTTON_THUMBL to LogicalControl.BUTTON_L3,
            NeutralKey.BUTTON_THUMBR to LogicalControl.BUTTON_R3,
            NeutralKey.DPAD_UP to LogicalControl.DPAD_UP,
            NeutralKey.DPAD_DOWN to LogicalControl.DPAD_DOWN,
            NeutralKey.DPAD_LEFT to LogicalControl.DPAD_LEFT,
            NeutralKey.DPAD_RIGHT to LogicalControl.DPAD_RIGHT,
        )
        for ((key, logical) in expectations) {
            val source = FakeJInputSource()
            val router = DesktopControllerRouter(source, scope())
            source.addController(buttons = setOf(key))
            router.tick()

            val buttons = router.slots.value[0].currentSnapshot.buttons
            // AssertJ's Java `.as(String)` idiom does not parse in Kotlin (`as` is a hard
            // keyword); 3.25.x exposes the description via describedAs(TextDescription).
            assertThat(buttons[logical.index]).describedAs(TextDescription("$key -> ${logical.name}")).isEqualTo(1f)
            assertThat(buttons.count { it > 0f }).describedAs(TextDescription("$key must press exactly one button")).isEqualTo(1)
        }
    }

    @Test
    fun `axis movement maps to logical axes`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(axes = mapOf(NeutralAxis.X to 0.5f, NeutralAxis.Y to -0.25f))
        router.tick()

        val axes = router.slots.value[0].currentSnapshot.axes
        assertThat(axes[LogicalControl.AXIS_LX.index]).isEqualTo(0.5f)
        assertThat(axes[LogicalControl.AXIS_LY.index]).isEqualTo(-0.25f)
    }

    @Test
    fun `axis within dead zone is zeroed`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(axes = mapOf(NeutralAxis.X to 0.1f))
        router.tick()

        assertThat(router.slots.value[0].currentSnapshot.axes[LogicalControl.AXIS_LX.index]).isEqualTo(0f)
    }

    @Test
    fun `trigger axis maps to trigger logical axis`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(axes = mapOf(NeutralAxis.GAS to 0.8f))
        router.tick()

        assertThat(router.slots.value[0].currentSnapshot.axes[LogicalControl.TRIGGER_RIGHT.index]).isEqualTo(0.8f)
    }

    @Test
    fun `second controller takes the next slot`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(id = "pad-1")
        source.addController(id = "pad-2")
        router.tick()

        assertThat(router.slots.value[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        assertThat(router.slots.value[1].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        assertThat(router.slots.value[2].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
        assertThat(router.slots.value[3].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
    }

    @Test
    fun `fifth controller is rejected when all slots are occupied`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        repeat(5) { i -> source.addController(id = "pad-$i") }
        router.tick()

        assertThat(router.slots.value.count { it.connectionState == SlotConnectionState.CONNECTED }).isEqualTo(4)
    }

    @Test
    fun `disconnect immediately produces neutral state`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        val pad = source.addController(buttons = setOf(NeutralKey.BUTTON_A))
        router.tick()
        assertThat(router.slots.value[0].currentSnapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)

        source.remove(pad)
        router.tick()

        val slot = router.slots.value[0]
        assertThat(slot.connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
        assertThat(slot.currentSnapshot).isEqualTo(GamepadSnapshot.EMPTY)
        assertThat(slot.currentSnapshot.isAnyButtonPressed).isFalse()
    }

    @Test
    fun `reconnect restores the same slot with its signature`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        val pad = source.addController(id = "pad-1")
        router.tick()

        source.remove(pad)
        router.tick()
        assertThat(router.slots.value[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)

        source.addController(id = "pad-1") // same descriptor -> same signature
        router.tick()

        val slot = router.slots.value[0]
        assertThat(slot.connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        assertThat(slot.preferredSignature?.descriptor).isEqualTo("fake:pad-1")
        assertThat(router.slots.value[1].connectionState).isEqualTo(SlotConnectionState.UNASSIGNED)
    }

    @Test
    fun `rescan adopts replacement wrapper with same id`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()

        val stale = source.addController(id = "steam-deck")
        router.tick()
        val replacement = FakeJInputController(
            id = "steam-deck",
            buttons = setOf(NeutralKey.DPAD_UP),
        )
        source.controllers[0] = replacement

        router.tick()
        yield()

        collector.cancel()
        assertThat(stale.pollCount).isEqualTo(1)
        assertThat(replacement.pollCount).isEqualTo(1)
        assertThat(actions).containsExactly(FocusAction.Move(FocusAction.Direction.UP))
        assertThat(router.slots.value[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
    }

    @Test
    fun `global rescan refreshes built in and external wrappers without changing slots`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()

        val staleBuiltIn = source.addController(id = "steam-deck")
        val staleExternal = source.addController(id = "external-pad")
        router.tick()

        val builtInReplacement = FakeJInputController(
            id = "steam-deck",
            buttons = setOf(NeutralKey.DPAD_LEFT),
        )
        val externalReplacement = FakeJInputController(id = "external-pad")
        source.controllers[0] = builtInReplacement
        source.controllers[1] = externalReplacement

        router.tick()
        yield()

        collector.cancel()
        assertThat(staleBuiltIn.pollCount).isEqualTo(1)
        assertThat(staleExternal.pollCount).isEqualTo(1)
        assertThat(builtInReplacement.pollCount).isEqualTo(1)
        assertThat(externalReplacement.pollCount).isEqualTo(1)
        assertThat(actions).containsExactly(FocusAction.Move(FocusAction.Direction.LEFT))
        assertThat(router.slots.value.map { it.connectionState }).startsWith(
            SlotConnectionState.CONNECTED,
            SlotConnectionState.CONNECTED,
        )
        assertThat(router.slots.value[0].preferredSignature?.descriptor).isEqualTo("fake:steam-deck")
        assertThat(router.slots.value[1].preferredSignature?.descriptor).isEqualTo("fake:external-pad")
    }

    // ---------------------------------------------------------------- focus actions

    @Test
    fun `dpad presses emit Move focus actions`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }

        yield() // let the collector subscribe before the first tick

        val pad = source.addController()
        pad.buttons = setOf(NeutralKey.DPAD_UP)
        router.tick()
        yield() // let the collector process the delivery
        pad.buttons = setOf(NeutralKey.DPAD_LEFT)
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(
            FocusAction.Move(FocusAction.Direction.UP),
            FocusAction.Move(FocusAction.Direction.LEFT),
        )
    }

    @Test
    fun `A and B emit Activate and Back`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }

        yield() // let the collector subscribe before the first tick

        val pad = source.addController()
        pad.buttons = setOf(NeutralKey.BUTTON_A)
        router.tick()
        yield()
        pad.buttons = setOf(NeutralKey.BUTTON_B)
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(FocusAction.Activate, FocusAction.Back)
    }

    @Test
    fun `focus actions are suppressed while controller capture owns input`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()

        val pad = source.addController()
        router.setFocusActionsEnabled(false)
        pad.buttons = setOf(NeutralKey.BUTTON_B)
        router.tick()
        yield()

        router.setFocusActionsEnabled(true)
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).isEmpty()
    }

    @Test
    fun `held direction repeats after a delay at a steady interval`() = runBlocking {
        val source = FakeJInputSource()
        var now = 1_000L
        val router = DesktopControllerRouter(source, this, clockMillis = { now })
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }

        yield() // let the collector subscribe before the first tick

        source.addController(buttons = setOf(NeutralKey.DPAD_UP))
        router.tick()
        yield()
        now += DesktopControllerRouter.DIRECTION_REPEAT_DELAY_MILLIS - 1
        router.tick()
        now += 1
        router.tick()
        yield()
        now += DesktopControllerRouter.DIRECTION_REPEAT_INTERVAL_MILLIS
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(
            FocusAction.Move(FocusAction.Direction.UP),
            FocusAction.Move(FocusAction.Direction.UP),
            FocusAction.Move(FocusAction.Direction.UP),
        )
    }

    @Test
    fun `held action buttons remain edge triggered`() = runBlocking {
        val source = FakeJInputSource()
        var now = 1_000L
        val router = DesktopControllerRouter(source, this, clockMillis = { now })
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()

        source.addController(buttons = setOf(NeutralKey.BUTTON_A))
        router.tick()
        now += DesktopControllerRouter.DIRECTION_REPEAT_DELAY_MILLIS * 2
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(FocusAction.Activate)
    }

    @Test
    fun `every connected controller drives focus`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }

        yield() // let the collector subscribe before the first tick

        source.addController(id = "pad-1") // slot 0
        val pad2 = source.addController(id = "pad-2") // slot 1
        router.tick()
        yield()

        pad2.buttons = setOf(NeutralKey.DPAD_UP)
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(FocusAction.Move(FocusAction.Direction.UP))
    }

    @Test
    fun `duplicate actions from physical and virtual controllers are coalesced`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()

        source.addController(id = "ps4", buttons = setOf(NeutralKey.DPAD_RIGHT))
        source.addController(id = "steam-virtual", buttons = setOf(NeutralKey.DPAD_RIGHT))
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(
            FocusAction.Move(FocusAction.Direction.RIGHT),
        )
    }

    @Test
    fun `manual assignment moves controller and leaves old slot empty`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(id = "deck")
        source.addController(id = "ps4")
        router.tick()

        assertThat(router.assignController(0, "ps4")).isTrue()

        assertThat(router.assignedControllerNames())
            .containsExactly("ps4", null, null, null)
        assertThat(router.connectedControllers.value.single { it.id == "ps4" }.slotIndex)
            .isEqualTo(0)
        assertThat(router.connectedControllers.value.single { it.id == "deck" }.slotIndex)
            .isNull()
        assertThat(router.slots.value[1].connectionState)
            .isEqualTo(SlotConnectionState.UNASSIGNED)
    }

    @Test
    fun `no controller keeps slot empty on later polls`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(id = "deck")
        router.tick()

        assertThat(router.assignController(0, null)).isTrue()
        router.tick()

        assertThat(router.slots.value[0].connectionState)
            .isEqualTo(SlotConnectionState.UNASSIGNED)
        assertThat(router.connectedControllers.value.single().slotIndex).isNull()
    }

    @Test
    fun `controller removed from gameplay slot still drives UI`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this)
        val actions = mutableListOf<FocusAction>()
        val collector = launch { router.focusActions.collect { actions += it } }
        yield()
        val deck = source.addController(id = "deck")
        source.addController(id = "ps4")
        router.tick()

        router.assignController(0, "ps4")
        deck.buttons = setOf(NeutralKey.DPAD_DOWN)
        router.tick()
        yield()

        collector.cancel()
        assertThat(actions).containsExactly(
            FocusAction.Move(FocusAction.Direction.DOWN),
        )
    }

    // ---------------------------------------------------------------- lifecycle

    @Test
    fun `stop clears all slots to neutral`() {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, scope())
        source.addController(buttons = setOf(NeutralKey.BUTTON_A))
        router.tick()

        router.stop()

        assertThat(router.slots.value).allMatch { it.connectionState != SlotConnectionState.CONNECTED }
        assertThat(router.slots.value).allMatch { it.currentSnapshot == GamepadSnapshot.EMPTY }
    }

    @Test
    fun `start polls the source until stop`() = runBlocking {
        val source = FakeJInputSource()
        val router = DesktopControllerRouter(source, this, pollIntervalMillis = 5)
        source.addController(buttons = setOf(NeutralKey.BUTTON_A))

        router.start()
        delay(50)

        assertThat(router.slots.value[0].connectionState).isEqualTo(SlotConnectionState.CONNECTED)
        assertThat(router.slots.value[0].currentSnapshot.buttons[LogicalControl.BUTTON_A.index]).isEqualTo(1f)

        router.stop()
        assertThat(router.slots.value[0].connectionState).isEqualTo(SlotConnectionState.DISCONNECTED)
    }
}

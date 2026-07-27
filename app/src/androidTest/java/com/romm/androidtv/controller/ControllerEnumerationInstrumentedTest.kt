package com.romm.androidtv.controller

import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romm.androidtv.controller.model.ControllerSlot
import com.romm.androidtv.controller.model.SlotConnectionState
import com.romm.androidtv.controller.router.ControllerEventRouter
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ControllerEnumerationInstrumentedTest {
    private lateinit var router: ControllerEventRouter

    @Before
    fun setup() {
        router = ControllerEventRouter()
        router.setActive(true)
    }

    @Test
    fun slotsFlow_initialState_hasExactlyFourSlots() {
        val slots = router.slotsFlow.value
        assert(slots.size == ControllerSlot.SLOT_COUNT)
        assert(slots.map { it.playerNumber } == listOf(1, 2, 3, 4))
    }

    @Test
    fun deactivate_neutralizesEverySlot() {
        assert(router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
        router.setActive(false)

        assert(router.slotsFlow.value.none { it.connectionState == SlotConnectionState.CONNECTED })
        assert(router.slotsFlow.value.all { !it.currentSnapshot.isAnyButtonPressed })
    }

    @Test
    fun remoteFallback_usesOneOfFourSlots_andSupportsRepeatedCycles() {
        repeat(3) {
            assert(router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            assert(router.slotsFlow.value[0].currentSnapshot.buttons[12] == 1f)
            assert(router.routeTvRemoteKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP))
            assert(router.slotsFlow.value[0].currentSnapshot.buttons[12] == 0f)
        }

        assert(router.slotsFlow.value.size == ControllerSlot.SLOT_COUNT)
    }

    @Test
    fun remoteFallback_mapsSelectToStandardButtonA() {
        assert(router.routeTvRemoteKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
        assert(router.slotsFlow.value[0].currentSnapshot.buttons[0] == 1f)
    }
}

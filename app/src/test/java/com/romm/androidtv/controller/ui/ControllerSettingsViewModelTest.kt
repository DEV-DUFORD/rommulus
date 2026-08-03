package com.romm.androidtv.controller.ui

import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.PhysicalBinding
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure unit tests for the conflict-detection decision logic extracted from
 * [ControllerSettingsViewModel] (no Android/Compose dependencies).
 */
class ControllerSettingsViewModelTest {

    private val target = CoreControlId.BUTTON_A
    private val buttonX = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_X)

    private val bindings = mapOf<CoreControlId, PhysicalBinding>(
        CoreControlId.D_PAD_UP to PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_DPAD_UP),
        CoreControlId.BUTTON_B to PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_B),
    )

    @Test
    fun `no existing binding yields Direct`() {
        val decision = decideApply(target, buttonX, emptyMap())
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `binding already assigned to a different control yields Conflict with that control`() {
        val withConflict = bindings + (CoreControlId.BUTTON_Y to buttonX)
        val decision = decideApply(target, buttonX, withConflict)
        assertThat(decision).isEqualTo(BindingApplyDecision.Conflict(CoreControlId.BUTTON_Y))
    }

    @Test
    fun `target already holding the same binding is ignored`() {
        val decision = decideApply(target, buttonX, mapOf(target to buttonX))
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `different binding on another control yields Direct`() {
        val decision = decideApply(target, buttonX, bindings)
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `first colliding control is reported when several share the binding`() {
        val withMany = mapOf(
            CoreControlId.BUTTON_Y to buttonX,
            CoreControlId.BUTTON_Z to buttonX,
        )
        val decision = decideApply(target, buttonX, withMany)
        assertThat(decision).isEqualTo(BindingApplyDecision.Conflict(CoreControlId.BUTTON_Y))
    }

    @Test
    fun `duplicate controller names are numbered in player order`() {
        val labels = playerControllerLabels(
            devices = listOf(
                ConnectedControllerInfo(18, "Xbox Wireless Controller"),
                ConnectedControllerInfo(19, "Xbox Wireless Controller"),
            ),
            playerCount = 4,
        )

        assertThat(labels).containsExactly(
            "Xbox Wireless Controller #1",
            "Xbox Wireless Controller #2",
            null,
            null,
        )
    }

    @Test
    fun `different controller names remain unchanged`() {
        val labels = playerControllerLabels(
            devices = listOf(
                ConnectedControllerInfo(18, "Xbox Wireless Controller"),
                ConnectedControllerInfo(19, "DualSense Wireless Controller"),
            ),
            playerCount = 2,
        )

        assertThat(labels).containsExactly(
            "Xbox Wireless Controller",
            "DualSense Wireless Controller",
        )
    }
}

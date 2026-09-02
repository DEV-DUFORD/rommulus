package com.romm.androidtv.controller.ui

import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.ControlBindings
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

    private val targetAddress = BindingAddress(target, BindingSlot.PRIMARY)
    private val bindings = mapOf(
        CoreControlId.D_PAD_UP to ControlBindings(
            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_DPAD_UP),
        ),
        CoreControlId.BUTTON_B to ControlBindings(
            primary = PhysicalBinding.Key(android.view.KeyEvent.KEYCODE_BUTTON_B),
        ),
    )

    @Test
    fun `no existing binding yields Direct`() {
        val decision = decideApply(targetAddress, buttonX, emptyMap())
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `binding already assigned to a different control yields Conflict with that control`() {
        val withConflict = bindings + (
            CoreControlId.BUTTON_Y to ControlBindings(primary = buttonX)
        )
        val decision = decideApply(targetAddress, buttonX, withConflict)
        assertThat(decision).isEqualTo(
            BindingApplyDecision.Conflict(
                BindingAddress(CoreControlId.BUTTON_Y, BindingSlot.PRIMARY),
            ),
        )
    }

    @Test
    fun `target already holding the same binding is ignored`() {
        val decision = decideApply(
            targetAddress,
            buttonX,
            mapOf(target to ControlBindings(primary = buttonX)),
        )
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `same control other slot is reported as a conflict`() {
        val decision = decideApply(
            BindingAddress(target, BindingSlot.SECONDARY),
            buttonX,
            mapOf(target to ControlBindings(primary = buttonX)),
        )

        assertThat(decision).isEqualTo(
            BindingApplyDecision.Conflict(
                BindingAddress(target, BindingSlot.PRIMARY),
            ),
        )
    }

    @Test
    fun `different binding on another control yields Direct`() {
        val decision = decideApply(targetAddress, buttonX, bindings)
        assertThat(decision).isEqualTo(BindingApplyDecision.Direct)
    }

    @Test
    fun `first colliding control is reported when several share the binding`() {
        val withMany = mapOf(
            CoreControlId.BUTTON_Y to ControlBindings(primary = buttonX),
            CoreControlId.BUTTON_Z to ControlBindings(primary = buttonX),
        )
        val decision = decideApply(targetAddress, buttonX, withMany)
        assertThat(decision).isEqualTo(
            BindingApplyDecision.Conflict(
                BindingAddress(CoreControlId.BUTTON_Y, BindingSlot.PRIMARY),
            ),
        )
    }

    @Test
    fun `duplicate controller names are numbered in player order`() {
        val labels = playerControllerLabels(
            devices = listOf(
                ConnectedControllerInfo(18, "Xbox Wireless Controller", 0),
                ConnectedControllerInfo(19, "Xbox Wireless Controller", 1),
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
                ConnectedControllerInfo(18, "Xbox Wireless Controller", 0),
                ConnectedControllerInfo(19, "DualSense Wireless Controller", 1),
            ),
            playerCount = 2,
        )

        assertThat(labels).containsExactly(
            "Xbox Wireless Controller",
            "DualSense Wireless Controller",
        )
    }

    @Test
    fun `controller labels follow assigned ports rather than device list order`() {
        val labels = playerControllerLabels(
            devices = listOf(
                ConnectedControllerInfo(18, "Xbox Wireless Controller", 1),
                ConnectedControllerInfo(19, "DualSense Wireless Controller", 0),
                ConnectedControllerInfo(20, "Unassigned Controller", null),
            ),
            playerCount = 4,
        )

        assertThat(labels).containsExactly(
            "DualSense Wireless Controller",
            "Xbox Wireless Controller",
            null,
            null,
        )
    }
}

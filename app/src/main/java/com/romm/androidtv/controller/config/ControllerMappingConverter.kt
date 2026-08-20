package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.AxisDirection
import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.controller.model.NeutralAxis
import com.romm.androidtv.controller.model.NeutralKey
import com.romm.androidtv.controller.model.PauseMenuCombination
import com.romm.androidtv.controller.model.PhysicalControl

/**
 * Converts the persistence-layer per-console-control model ([CoreControllerConfig] /
 * [PlayerControllerConfig] / [PhysicalBinding]) into the runtime per-physical-slot model
 * ([ControllerMapping]) that [com.romm.androidtv.controller.router.ControllerEventRouter.applyMappings]
 * expects.
 *
 * The conversion is driven by the core's [CoreControllerProfile] catalog: each binding is keyed
 * by a console-semantic [CoreControlId], and the profile resolves that id to its RetroPad /
 * [LogicalControl] target. The physical side of each binding determines which [ControllerMapping]
 * map it lands in:
 *  - [PhysicalBinding.Key] -> `buttons[keyCode] = target`
 *  - [PhysicalBinding.Axis] -> `axes[axis] = target`
 *  - [PhysicalBinding.AxisDirection] -> `axisDirections[AxisDirection(axis, polarity)] = target`
 *
 * Player indices not present in [CoreControllerConfig.players] are omitted so the corresponding
 * router slot keeps its existing/default mapping. An empty [CoreControllerConfig.players] produces
 * an empty map, meaning nothing is applied and the router keeps its built-in defaults.
 */
fun CoreControllerConfig.toRouterMappings(profile: CoreControllerProfile): Map<Int, ControllerMapping> {
    val descriptorByControlId = profile.controls.associateBy { it.id }

    return buildMap {
        for ((playerIndex, playerConfig) in players) {
            val buttons = mutableMapOf<NeutralKey, LogicalControl>()
            val axes = mutableMapOf<NeutralAxis, LogicalControl>()
            val axisDirections = mutableMapOf<AxisDirection, LogicalControl>()

            for ((controlId, controlBindings) in playerConfig.bindings) {
                if (controlId.isPauseMenuControl) continue
                val target = descriptorByControlId[controlId]?.target ?: continue
                for ((_, binding) in controlBindings.entries()) {
                    when (binding) {
                        is PhysicalBinding.Key -> NeutralKey.fromPlatform(binding.keyCode)?.let {
                            buttons[it] = target
                        }
                        is PhysicalBinding.Axis -> {
                            val neutralAxis = NeutralAxis.fromPlatform(binding.axis)
                            if (neutralAxis == null) {
                                // Unknown platform axis: skip (cannot be represented neutrally).
                            } else if (target.type == LogicalControl.Type.BUTTON) {
                                axisDirections[AxisDirection(neutralAxis, 1)] = target
                            } else {
                                axes[neutralAxis] = target
                            }
                        }
                        is PhysicalBinding.AxisDirection -> {
                            NeutralAxis.fromPlatform(binding.axis)?.let {
                                axisDirections[AxisDirection(it, binding.polarity)] = target
                            }
                        }
                    }
                }
            }

            put(
                playerIndex,
                ControllerMapping(
                    buttons = buttons,
                    axes = axes,
                    axisConfigs = emptyMap(),
                    axisDirections = axisDirections,
                    pauseMenuCombination = playerConfig.pauseMenuCombination(),
                ),
            )
        }
    }
}

private fun PlayerControllerConfig.pauseMenuCombination(): PauseMenuCombination? {
    val bindings = bindings[CoreControlId.PAUSE_MENU] ?: return null
    val first = bindings.primary.toPhysicalControl() ?: return null
    val second = bindings.secondary.toPhysicalControl() ?: return null
    if (first == second) return null
    return PauseMenuCombination(first, second)
}

private fun PhysicalBinding?.toPhysicalControl(): PhysicalControl? = when (this) {
    is PhysicalBinding.Key -> PhysicalControl.Key(keyCode)
    is PhysicalBinding.Axis -> PhysicalControl.AxisDirection(axis, 1)
    is PhysicalBinding.AxisDirection -> PhysicalControl.AxisDirection(axis, polarity)
    null -> null
}

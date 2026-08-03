package com.romm.androidtv.controller.config

import com.romm.androidtv.controller.model.AxisDirection
import com.romm.androidtv.controller.model.ControllerMapping
import com.romm.androidtv.controller.model.LogicalControl

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
            val buttons = mutableMapOf<Int, LogicalControl>()
            val axes = mutableMapOf<Int, LogicalControl>()
            val axisDirections = mutableMapOf<AxisDirection, LogicalControl>()

            for ((controlId, controlBindings) in playerConfig.bindings) {
                val target = descriptorByControlId[controlId]?.target ?: continue
                for ((_, binding) in controlBindings.entries()) {
                    when (binding) {
                        is PhysicalBinding.Key -> buttons[binding.keyCode] = target
                        is PhysicalBinding.Axis -> {
                            if (target.type == LogicalControl.Type.BUTTON) {
                                axisDirections[AxisDirection(binding.axis, 1)] = target
                            } else {
                                axes[binding.axis] = target
                            }
                        }
                        is PhysicalBinding.AxisDirection ->
                            axisDirections[AxisDirection(binding.axis, binding.polarity)] = target
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
                ),
            )
        }
    }
}

package com.romm.androidtv.controller.config

/**
 * Pure function that merges catalog defaults with persisted overrides.
 *
 * Defaults are evolvable: the catalog may change at any time and the merge picks
 * up the new defaults automatically. Overrides are persisted: only the user's
 * explicit edits are stored. Unknown controls are retained in storage but ignored
 * by the active profile — they never cause a crash or a silent rewrite.
 */
object ControllerConfigMerger {

    /**
     * Merge a [profile]'s defaults with user [overrides] and return the resolved
     * [CoreControllerConfig].
     *
     * For each player index `0` until `profile.playerCount`:
     * 1. Start from `profile.defaults[playerIndex]` (the default [PlayerControllerConfig]).
     * 2. Apply `overrides[playerIndex]`: for each `(controlId, binding)`, if `controlId`
     *    is a **known** control in `profile.controls`, replace that control's binding;
     *    if `controlId` is **not** in `profile.controls` (an unknown or obsolete control
     *    from an older or newer app version), ignore it.
     * 3. If a player index has no defaults entry (defensive; Phase 1 validation should
     *    prevent this), fall back to an empty [PlayerControllerConfig].
     *
     * The resulting [PlayerControllerConfig] covers every control in [profile.controls].
     */
    fun merge(
        profile: CoreControllerProfile,
        overrides: Map<Int, Map<CoreControlId, Map<BindingSlot, PhysicalBinding?>>>,
    ): CoreControllerConfig {
        val knownIds = profile.controls.mapNotNull { it.id }.toSet()

        val players = (0 until profile.playerCount).associateWith { playerIndex ->
            val baseDefaults = profile.defaults[playerIndex] ?: PlayerControllerConfig()
            val playerOverrides = overrides[playerIndex] ?: emptyMap()

            val mergedBindings = baseDefaults.bindings.toMutableMap()
            for ((controlId, slotOverrides) in playerOverrides) {
                if (controlId in knownIds) {
                    var resolved = mergedBindings[controlId] ?: ControlBindings()
                    for ((slot, binding) in slotOverrides) {
                        resolved = resolved.with(slot, binding)
                    }
                    mergedBindings[controlId] = resolved
                }
                // Unknown controlId: silently ignored (retained in storage, not in active profile)
            }

            // Explicit user overrides take precedence over newly introduced catalog defaults.
            // This prevents an older custom stick-direction mapping from being silently stolen
            // when a later app version adds the same physical input as a secondary default.
            val explicitBindings = playerOverrides.flatMap { (controlId, slotOverrides) ->
                slotOverrides.mapNotNull { (slot, binding) ->
                    binding?.let { BindingAddress(controlId, slot) to it }
                }
            }
            for ((controlId, bindings) in mergedBindings.toMap()) {
                var resolved = bindings
                for ((slot, binding) in bindings.entries()) {
                    val address = BindingAddress(controlId, slot)
                    val isExplicit = playerOverrides[controlId]?.containsKey(slot) == true
                    val conflictsWithExplicit = explicitBindings.any { (explicitAddress, explicitBinding) ->
                        explicitAddress != address &&
                            !controlId.isPauseMenuControl &&
                            !explicitAddress.controlId.isPauseMenuControl &&
                            explicitBinding == binding
                    }
                    if (!isExplicit && conflictsWithExplicit) {
                        resolved = resolved.with(slot, null)
                    }
                }
                mergedBindings[controlId] = resolved
            }

            PlayerControllerConfig(mergedBindings)
        }

        return CoreControllerConfig(profile.coreId, players)
    }
}

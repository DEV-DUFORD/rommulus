package com.romm.androidtv.controller.config

import kotlinx.coroutines.flow.Flow

/**
 * Read/write access to persisted per-core controller configuration overrides
 * (CONTROLLER_SETTINGS.md Architecture section 2).
 *
 * Only user **overrides** are persisted; catalog defaults are merged over them at read time
 * (via [ControllerConfigMerger]) so defaults can evolve independently of storage, and reset is
 * simply a delete operation. Every mutation below is atomic from the caller's perspective:
 * conflict swap/replace run inside a Room transaction so the UI never observes a duplicated
 * intermediate mapping.
 *
 * All methods are scoped by [coreId], and player-scoped ones additionally by [playerIndex]
 * (zero-based Libretro port). Unknown control IDs from an older/newer app version are retained
 * in storage but ignored by the active profile — never crashed on or silently rewritten.
 */
interface ControllerConfigRepository {

    /** Emits the merged [CoreControllerConfig] for [coreId], then re-emits on any change. */
    fun observeCore(coreId: String): Flow<CoreControllerConfig>

    /** One-shot read of the merged [CoreControllerConfig] for [coreId]. */
    suspend fun loadCore(coreId: String): CoreControllerConfig

    /**
     * Persist [binding] as the override for ([coreId], [playerIndex], [controlId]),
     * overwriting any existing row for the same primary-key triple. No conflict resolution
     * is performed here; use [swapBindings] or [replaceBinding] for conflict handling.
     */
    suspend fun setBinding(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
        bindingSlot: BindingSlot = BindingSlot.PRIMARY,
    )

    /**
     * Atomically swap the bindings of [controlIdA] and [controlIdB] within one player.
     *
     * Exchanges the two effective slot values, including catalog defaults. A null side is
     * persisted as an explicit unmapped override so a conflicting catalog default does not
     * immediately reappear.
     */
    suspend fun swapBindings(
        coreId: String,
        playerIndex: Int,
        addressA: BindingAddress,
        addressB: BindingAddress,
    )

    /**
     * Atomically replace the binding of [controlId] with [binding] within one player.
     *
     * Because a physical input maps to only one target per player, every other effective slot
     * holding [binding] is persisted as explicitly unmapped before the target is upserted.
     */
    suspend fun replaceBinding(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
        binding: PhysicalBinding,
    )

    /** Delete every override for one player port of [coreId]; defaults are restored on next read. */
    suspend fun resetPlayer(coreId: String, playerIndex: Int)

    /** Delete every override for [coreId] across all players; defaults are restored on next read. */
    suspend fun resetCore(coreId: String)
}

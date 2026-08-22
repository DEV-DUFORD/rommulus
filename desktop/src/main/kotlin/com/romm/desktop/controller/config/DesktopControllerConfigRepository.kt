package com.romm.desktop.controller.config

import com.romm.androidtv.controller.config.BindingAddress
import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.ControllerConfigMerger
import com.romm.androidtv.controller.config.ControllerConfigRepository
import com.romm.androidtv.controller.config.CoreControllerConfig
import com.romm.androidtv.controller.config.CoreControlId
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.PhysicalBinding
import com.romm.androidtv.controller.config.PlayerControllerConfig
import com.romm.androidtv.controller.config.isPauseMenuControl
import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.ControllerBindingRecord
import com.romm.desktop.player.RETRO_PAD_SLOT_NAMES
import com.romm.desktop.player.RetroPadControlMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Desktop [ControllerConfigRepository] backed by the shared neutral [ControllerBindingStore]
 * (production: `SqliteControllerBindingStore`) plus the shared profile catalog defaults.
 *
 * Mirrors the Android `RoomControllerConfigRepository` semantics 1:1 — only user **overrides**
 * are persisted; catalog defaults are merged over them at read time via the shared
 * [ControllerConfigMerger], so reset is simply a delete and defaults can evolve independently
 * of storage. Rows are encoded with [DesktopControllerBindingCodec] (the player's RetroPad
 * ordinal encoding), so launch serialization works unchanged.
 *
 * `observeCore` is backed by a per-core [StateFlow] refreshed after every mutation, since the
 * JDBC store has no query observers; the emitted value is always the freshly merged config.
 */
class DesktopControllerConfigRepository(
    private val store: ControllerBindingStore,
    private val profiles: CoreControllerProfiles = CoreControllerProfiles,
) : ControllerConfigRepository {

    init {
        migrateLegacyPlayerTables()
    }

    /** Per-core observation flows, lazily created on first [observeCore]. */
    private val flows = mutableMapOf<String, MutableStateFlow<CoreControllerConfig>>()

    @Synchronized
    override fun observeCore(coreId: String): Flow<CoreControllerConfig> =
        flows.getOrPut(coreId) {
            MutableStateFlow(resolve(coreId, store.loadForCore(coreId)))
        }.asStateFlow()

    override suspend fun loadCore(coreId: String): CoreControllerConfig =
        resolve(coreId, store.loadForCore(coreId))

    /**
     * Complete effective RetroPad table for launch: profile defaults with stored overrides
     * applied. Launch requests require all 16 slots, not only the persisted override rows.
     */
    fun effectiveLaunchRecords(coreId: String, playerIndex: Int): List<ControllerBindingRecord> {
        val stored = store.loadForCore(coreId)
        if (stored.isEmpty()) return emptyList()
        val player = resolve(coreId, stored).players[playerIndex] ?: return emptyList()
        val storedByAddress = stored
            .filter { it.playerIndex == playerIndex }
            .associateBy { it.controlId to it.bindingSlot }
        return RETRO_PAD_SLOT_NAMES.flatMap { slotName ->
            val controlId = RetroPadControlMapping.coreControlIdForSlot(coreId, slotName)
            BindingSlot.entries.map { slot ->
                storedByAddress[controlId.id to slot.index]?.let { return@map it }
                val binding = player.get(controlId, slot)
                binding?.let {
                    DesktopControllerBindingCodec.encode(coreId, playerIndex, controlId, it, slot.index)
                } ?: DesktopControllerBindingCodec.encodeUnmapped(
                    coreId,
                    playerIndex,
                    BindingAddress(controlId, slot),
                )
            }
        }
    }

    override suspend fun setBinding(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
        bindingSlot: BindingSlot,
    ) {
        store.upsert(
            DesktopControllerBindingCodec.encode(coreId, playerIndex, controlId, binding, bindingSlot.index),
        ).getOrThrow()
        refresh(coreId)
    }

    override suspend fun clearBinding(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        bindingSlot: BindingSlot,
    ) {
        store.upsert(
            DesktopControllerBindingCodec.encodeUnmapped(
                coreId, playerIndex, BindingAddress(controlId, bindingSlot),
            ),
        ).getOrThrow()
        refresh(coreId)
    }

    override suspend fun swapBindings(
        coreId: String,
        playerIndex: Int,
        addressA: BindingAddress,
        addressB: BindingAddress,
    ) {
        // upsertAll commits all-or-nothing in a single store transaction (see
        // SqliteControllerBindingStore), mirroring the Android Room transaction.
        val playerRows = store.loadForPlayer(coreId, playerIndex)
        val effective = resolve(coreId, playerRows).players[playerIndex] ?: PlayerControllerConfig()
        val bindingA = effective.get(addressA.controlId, addressA.slot)
        val bindingB = effective.get(addressB.controlId, addressB.slot)
        store.upsertAll(
            listOf(
                encodeOverride(coreId, playerIndex, addressA, bindingB),
                encodeOverride(coreId, playerIndex, addressB, bindingA),
            ),
        ).getOrThrow()
        refresh(coreId)
    }

    override suspend fun replaceBinding(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
        binding: PhysicalBinding,
    ) {
        val newRecord = DesktopControllerBindingCodec.encode(
            coreId, playerIndex, address.controlId, binding, address.slot.index,
        )
        val playerRows = store.loadForPlayer(coreId, playerIndex)
        val effective = resolve(coreId, playerRows).players[playerIndex] ?: PlayerControllerConfig()
        val updates = mutableListOf<ControllerBindingRecord>()
        for ((controlId, bindings) in effective.bindings) {
            if (controlId.isPauseMenuControl || address.controlId.isPauseMenuControl) continue
            for ((slot, currentBinding) in bindings.entries()) {
                val currentAddress = BindingAddress(controlId, slot)
                if (currentAddress != address && currentBinding == binding) {
                    updates += DesktopControllerBindingCodec.encodeUnmapped(coreId, playerIndex, currentAddress)
                }
            }
        }
        updates += newRecord
        store.upsertAll(updates).getOrThrow()
        refresh(coreId)
    }

    override suspend fun resetPlayer(coreId: String, playerIndex: Int) {
        store.deletePlayer(coreId, playerIndex).getOrThrow()
        refresh(coreId)
    }

    override suspend fun clearPlayerMappings(coreId: String, playerIndex: Int) {
        val profile = profiles.byCoreId(coreId) ?: return
        store.upsertAll(
            profile.controls.flatMap { control ->
                BindingSlot.entries.map { slot ->
                    DesktopControllerBindingCodec.encodeUnmapped(
                        coreId, playerIndex, BindingAddress(control.id, slot),
                    )
                }
            },
        ).getOrThrow()
        refresh(coreId)
    }

    override suspend fun resetCore(coreId: String) {
        store.deleteCore(coreId).getOrThrow()
        refresh(coreId)
    }

    /** Refresh observers after the player ingests controller overrides directly into the store. */
    fun refreshFromStore(coreId: String) {
        refresh(coreId)
    }

    @Synchronized
    private fun refresh(coreId: String) {
        flows[coreId]?.value = resolve(coreId, store.loadForCore(coreId))
    }

    /**
     * Merge the persisted overrides for [coreId] over the catalog defaults.
     *
     * Decodes each record (skipping unknown rows), groups by player, control, and binding slot,
     * and merges the mapped or explicitly-unmapped overrides over the matching profile. An
     * unknown [coreId] yields an empty [CoreControllerConfig] rather than crashing.
     */
    private fun resolve(
        coreId: String,
        records: List<ControllerBindingRecord>,
    ): CoreControllerConfig {
        val overrides = mutableMapOf<
            Int,
            MutableMap<CoreControlId, MutableMap<BindingSlot, PhysicalBinding?>>,
        >()
        for (record in records) {
            val controlId = CoreControlId.entries.firstOrNull { it.id == record.controlId } ?: continue
            val slot = BindingSlot.fromIndex(record.bindingSlot) ?: continue
            val binding = when (val decoded = DesktopControllerBindingCodec.decodeOverride(record)) {
                is DesktopControllerBindingCodec.DecodedOverride.Mapped -> decoded.binding
                DesktopControllerBindingCodec.DecodedOverride.Unmapped -> null
                null -> continue
            }
            overrides
                .getOrPut(record.playerIndex, ::mutableMapOf)
                .getOrPut(controlId, ::mutableMapOf)[slot] = binding
        }

        val profile = profiles.byCoreId(coreId) ?: return CoreControllerConfig(coreId, emptyMap())
        return ControllerConfigMerger.merge(profile, overrides)
    }

    private fun encodeOverride(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
        binding: PhysicalBinding?,
    ): ControllerBindingRecord = binding?.let {
        DesktopControllerBindingCodec.encode(coreId, playerIndex, address.controlId, it, address.slot.index)
    } ?: DesktopControllerBindingCodec.encodeUnmapped(coreId, playerIndex, address)

    /**
     * Preview builds persisted in-game sidecars under raw RetroPad ids. Rewrite complete legacy
     * tables to each profile's console-semantic ids so existing Steam Deck remaps survive upgrade.
     */
    private fun migrateLegacyPlayerTables() {
        val legacyIds = RetroPadControlMapping.SLOT_TO_CONTROL_ID.values.toSet()
        for (profile in profiles.all) {
            val slotToControlId = RETRO_PAD_SLOT_NAMES.associateWith {
                RetroPadControlMapping.coreControlIdForSlot(profile.coreId, it).id
            }
            if (slotToControlId.values.toSet() == legacyIds) continue

            val records = store.loadForCore(profile.coreId)
            if (records.isEmpty()) continue
            var changed = false
            val migrated = records.map { record ->
                val slotName = RetroPadControlMapping.SLOT_TO_CONTROL_ID
                    .entries
                    .firstOrNull { it.value == record.controlId }
                    ?.key
                val replacement = slotName?.let(slotToControlId::get)
                if (replacement != null && replacement != record.controlId) {
                    changed = true
                    record.copy(controlId = replacement)
                } else {
                    record
                }
            }
            if (changed && records.map { it.controlId }.toSet().containsAll(legacyIds)) {
                store.deleteCore(profile.coreId).getOrThrow()
                store.upsertAll(migrated.distinctBy {
                    Triple(it.playerIndex, it.controlId, it.bindingSlot)
                }).getOrThrow()
            }
        }
    }
}

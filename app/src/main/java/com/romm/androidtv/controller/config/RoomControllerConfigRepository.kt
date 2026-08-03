package com.romm.androidtv.controller.config

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [ControllerConfigRepository] (CONTROLLER_SETTINGS.md Architecture section 2).
 *
 * Overrides are read/written directly against the [ControllerBindingDao] and merged over the
 * catalog defaults via [resolve]. Conflict swap/replace operations run inside
 * [transactionRunner] (wired to Room's `withTransaction` in production via
 * [create]) so the UI never observes a duplicated intermediate mapping.
 */
class RoomControllerConfigRepository(
    private val dao: ControllerBindingDao,
    private val profiles: CoreControllerProfiles = CoreControllerProfiles,
    private val transactionRunner: suspend (suspend () -> Unit) -> Unit = { block -> block() },
) : ControllerConfigRepository {

    override fun observeCore(coreId: String): Flow<CoreControllerConfig> =
        dao.observeCore(coreId).map { resolve(coreId, it) }

    override suspend fun loadCore(coreId: String): CoreControllerConfig =
        resolve(coreId, dao.loadForCore(coreId))

    override suspend fun setBinding(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
        bindingSlot: BindingSlot,
    ) {
        dao.upsert(ControllerBindingCodec.encode(coreId, playerIndex, controlId, binding, bindingSlot))
    }

    override suspend fun swapBindings(
        coreId: String,
        playerIndex: Int,
        addressA: BindingAddress,
        addressB: BindingAddress,
    ) {
        transactionRunner {
            val playerRows = dao.loadForPlayer(coreId, playerIndex)
            val effective = resolve(coreId, playerRows).players[playerIndex] ?: PlayerControllerConfig()
            val bindingA = effective.get(addressA.controlId, addressA.slot)
            val bindingB = effective.get(addressB.controlId, addressB.slot)
            dao.upsertAll(
                listOf(
                    encodeOverride(coreId, playerIndex, addressA, bindingB),
                    encodeOverride(coreId, playerIndex, addressB, bindingA),
                ),
            )
        }
    }

    override suspend fun replaceBinding(
        coreId: String,
        playerIndex: Int,
        address: BindingAddress,
        binding: PhysicalBinding,
    ) {
        transactionRunner {
            val newEntity = ControllerBindingCodec.encode(
                coreId,
                playerIndex,
                address.controlId,
                binding,
                address.slot,
            )
            val playerRows = dao.loadForPlayer(coreId, playerIndex)
            val effective = resolve(coreId, playerRows).players[playerIndex] ?: PlayerControllerConfig()
            val updates = mutableListOf<ControllerBindingEntity>()
            for ((controlId, bindings) in effective.bindings) {
                for ((slot, currentBinding) in bindings.entries()) {
                    val currentAddress = BindingAddress(controlId, slot)
                    if (currentAddress != address && currentBinding == binding) {
                        updates += ControllerBindingCodec.encodeUnmapped(
                            coreId,
                            playerIndex,
                            currentAddress,
                        )
                    }
                }
            }
            updates += newEntity
            dao.upsertAll(updates)
        }
    }

    override suspend fun resetPlayer(coreId: String, playerIndex: Int) {
        dao.deletePlayer(coreId, playerIndex)
    }

    override suspend fun resetCore(coreId: String) {
        dao.deleteCore(coreId)
    }

    /**
     * Merge the persisted overrides for [coreId] over the catalog defaults.
     *
     * Decodes each entity (skipping unknown rows), groups by player, control, and binding slot,
     * and merges the mapped or explicitly-unmapped overrides over the matching profile.
     * An unknown [coreId] yields an empty [CoreControllerConfig] rather than crashing.
     */
    private fun resolve(
        coreId: String,
        entities: List<ControllerBindingEntity>,
    ): CoreControllerConfig {
        val overrides = mutableMapOf<
            Int,
            MutableMap<CoreControlId, MutableMap<BindingSlot, PhysicalBinding?>>,
        >()
        for (entity in entities) {
            val controlId = CoreControlId.entries.firstOrNull { it.id == entity.controlId } ?: continue
            val slot = BindingSlot.fromIndex(entity.bindingSlot) ?: continue
            val binding = when (val decoded = ControllerBindingCodec.decodeOverride(entity)) {
                is ControllerBindingCodec.DecodedOverride.Mapped -> decoded.binding
                ControllerBindingCodec.DecodedOverride.Unmapped -> null
                null -> continue
            }
            overrides
                .getOrPut(entity.playerIndex, ::mutableMapOf)
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
    ): ControllerBindingEntity = binding?.let {
        ControllerBindingCodec.encode(coreId, playerIndex, address.controlId, it, address.slot)
    } ?: ControllerBindingCodec.encodeUnmapped(coreId, playerIndex, address)

    companion object {
        /**
         * Production factory wiring the real Room transaction for conflict swap/replace.
         */
        fun create(db: ControllerConfigDatabase): ControllerConfigRepository =
            RoomControllerConfigRepository(
                dao = db.controllerBindingDao(),
                transactionRunner = { block -> db.withTransaction { block() } },
            )
    }
}

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
    ) {
        dao.upsert(ControllerBindingCodec.encode(coreId, playerIndex, controlId, binding))
    }

    override suspend fun swapBindings(
        coreId: String,
        playerIndex: Int,
        controlIdA: CoreControlId,
        controlIdB: CoreControlId,
    ) {
        transactionRunner {
            val playerRows = dao.loadForPlayer(coreId, playerIndex)
            val entityA = playerRows.find { it.controlId == controlIdA.id }
            val entityB = playerRows.find { it.controlId == controlIdB.id }

            when {
                entityA != null && entityB != null -> {
                    val swappedA = entityA.copy(
                        bindingType = entityB.bindingType,
                        inputCode = entityB.inputCode,
                        polarity = entityB.polarity,
                    )
                    val swappedB = entityB.copy(
                        bindingType = entityA.bindingType,
                        inputCode = entityA.inputCode,
                        polarity = entityA.polarity,
                    )
                    dao.upsertAll(listOf(swappedA, swappedB))
                }
                entityA != null -> {
                    // Only A exists: move its binding to B.
                    val moved = entityA.copy(
                        controlId = controlIdB.id,
                    )
                    dao.upsert(moved)
                    dao.delete(coreId, playerIndex, controlIdA.id)
                }
                entityB != null -> {
                    // Only B exists: move its binding to A.
                    val moved = entityB.copy(
                        controlId = controlIdA.id,
                    )
                    dao.upsert(moved)
                    dao.delete(coreId, playerIndex, controlIdB.id)
                }
                // Neither exists: no-op.
            }
        }
    }

    override suspend fun replaceBinding(
        coreId: String,
        playerIndex: Int,
        controlId: CoreControlId,
        binding: PhysicalBinding,
    ) {
        transactionRunner {
            val newEntity = ControllerBindingCodec.encode(coreId, playerIndex, controlId, binding)
            val playerRows = dao.loadForPlayer(coreId, playerIndex)
            // A physical input maps to only one target per player: remove any other control
            // whose persisted binding equals the new one before upserting.
            for (row in playerRows) {
                if (row.controlId != controlId.id && ControllerBindingCodec.decode(row) == binding) {
                    dao.delete(coreId, playerIndex, row.controlId)
                }
            }
            dao.upsert(newEntity)
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
     * Decodes each entity (skipping unknown/undecodable rows), groups by player index into
     * `Map<Int, Map<CoreControlId, PhysicalBinding>>`, and merges over the matching profile.
     * An unknown [coreId] yields an empty [CoreControllerConfig] rather than crashing.
     */
    private fun resolve(
        coreId: String,
        entities: List<ControllerBindingEntity>,
    ): CoreControllerConfig {
        val overrides: MutableMap<Int, MutableMap<CoreControlId, PhysicalBinding>> = mutableMapOf()
        for (entity in entities) {
            val binding = ControllerBindingCodec.decode(entity) ?: continue
            val controlId = CoreControlId.entries.firstOrNull { it.id == entity.controlId } ?: continue
            overrides.getOrPut(entity.playerIndex, ::mutableMapOf)[controlId] = binding
        }

        val profile = profiles.byCoreId(coreId) ?: return CoreControllerConfig(coreId, emptyMap())
        return ControllerConfigMerger.merge(profile, overrides)
    }

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

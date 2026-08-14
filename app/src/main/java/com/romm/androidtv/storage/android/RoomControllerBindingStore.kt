package com.romm.androidtv.storage.android

import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.ControllerBindingDao
import com.romm.androidtv.controller.config.ControllerBindingEntity
import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.BindingSlots
import com.romm.androidtv.storage.records.ControllerBindingRecord

/**
 * Android adapter for [ControllerBindingStore].
 *
 * Thin, delegate-only bridge between the persistence-neutral port and the Room DAO.
 * Does NOT modify any existing Android production files; the DAO and entity are read
 * verbatim.
 */
class RoomControllerBindingStore(
    private val dao: ControllerBindingDao,
) : ControllerBindingStore {

    // ports are synchronous; Room DAO is suspend
    private fun <T> runSuspend(block: suspend () -> T): T =
        kotlinx.coroutines.runBlocking { block() }

    private fun entityToRecord(e: ControllerBindingEntity): ControllerBindingRecord =
        ControllerBindingRecord(
            coreId = e.coreId,
            playerIndex = e.playerIndex,
            controlId = e.controlId,
            bindingSlot = e.bindingSlot,
            bindingType = e.bindingType,
            inputCode = e.inputCode,
            polarity = e.polarity,
            schemaVersion = e.schemaVersion,
        )

    private fun recordToEntity(r: ControllerBindingRecord): ControllerBindingEntity =
        ControllerBindingEntity(
            coreId = r.coreId,
            playerIndex = r.playerIndex,
            controlId = r.controlId,
            bindingSlot = BindingSlot.fromIndex(r.bindingSlot)!!.index,
            bindingType = r.bindingType,
            inputCode = r.inputCode,
            polarity = r.polarity,
            schemaVersion = r.schemaVersion,
        )

    override fun loadForCore(coreId: String): List<ControllerBindingRecord> =
        runSuspend { dao.loadForCore(coreId) }.map { entityToRecord(it) }

    override fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingRecord> =
        runSuspend { dao.loadForPlayer(coreId, playerIndex) }.map { entityToRecord(it) }

    override fun upsert(binding: ControllerBindingRecord): Result<Unit> =
        runCatching { runSuspend { dao.upsert(recordToEntity(binding)) } }

    override fun upsertAll(bindings: List<ControllerBindingRecord>): Result<Unit> =
        runCatching { runSuspend { dao.upsertAll(bindings.map { recordToEntity(it) }) } }

    override fun delete(coreId: String, playerIndex: Int, controlId: String, bindingSlot: Int): Result<Unit> =
        runCatching { runSuspend { dao.delete(coreId, playerIndex, controlId, bindingSlot) } }

    override fun deletePlayer(coreId: String, playerIndex: Int): Result<Unit> =
        runCatching { runSuspend { dao.deletePlayer(coreId, playerIndex) } }

    override fun deleteCore(coreId: String): Result<Unit> =
        runCatching { runSuspend { dao.deleteCore(coreId) } }
}

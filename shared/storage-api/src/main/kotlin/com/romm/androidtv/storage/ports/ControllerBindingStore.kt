package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.ControllerBindingRecord

/** Persistence-neutral store for controller binding overrides. */
interface ControllerBindingStore {
    fun loadForCore(coreId: String): List<ControllerBindingRecord>
    fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingRecord>
    fun upsert(binding: ControllerBindingRecord): Result<Unit>
    fun upsertAll(bindings: List<ControllerBindingRecord>): Result<Unit>
    fun delete(coreId: String, playerIndex: Int, controlId: String, bindingSlot: Int): Result<Unit>
    fun deletePlayer(coreId: String, playerIndex: Int): Result<Unit>
    fun deleteCore(coreId: String): Result<Unit>
}

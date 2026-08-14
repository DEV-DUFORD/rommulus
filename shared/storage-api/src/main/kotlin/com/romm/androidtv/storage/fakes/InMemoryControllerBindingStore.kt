package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.ControllerBindingRecord

/** In-memory controller binding store for tests and desktop dev-loop use. */
class InMemoryControllerBindingStore : ControllerBindingStore {

    private val lock = Any()
    private val bindings: MutableMap<BindingKey, ControllerBindingRecord> = mutableMapOf()

    private data class BindingKey(
        val coreId: String,
        val playerIndex: Int,
        val controlId: String,
        val bindingSlot: Int,
    )

    private fun toKey(b: ControllerBindingRecord): BindingKey =
        BindingKey(b.coreId, b.playerIndex, b.controlId, b.bindingSlot)

    override fun loadForCore(coreId: String): List<ControllerBindingRecord> {
        return synchronized(lock) { bindings.values.filter { it.coreId == coreId }.toList() }
    }

    override fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingRecord> {
        return synchronized(lock) {
            bindings.values.filter { it.coreId == coreId && it.playerIndex == playerIndex }.toList()
        }
    }

    override fun upsert(binding: ControllerBindingRecord): Result<Unit> = runCatching {
        synchronized(lock) { bindings[toKey(binding)] = binding }
    }

    override fun upsertAll(bindings: List<ControllerBindingRecord>): Result<Unit> = runCatching {
        synchronized(lock) {
            bindings.forEach { this.bindings[toKey(it)] = it }
        }
    }

    override fun delete(coreId: String, playerIndex: Int, controlId: String, bindingSlot: Int): Result<Unit> = runCatching {
        synchronized(lock) { bindings.remove(BindingKey(coreId, playerIndex, controlId, bindingSlot)) }
    }

    override fun deletePlayer(coreId: String, playerIndex: Int): Result<Unit> = runCatching {
        synchronized(lock) {
            val toRemove = bindings.entries.filter { it.value.coreId == coreId && it.value.playerIndex == playerIndex }.map { it.key }
            toRemove.forEach { bindings.remove(it) }
        }
    }

    override fun deleteCore(coreId: String): Result<Unit> = runCatching {
        synchronized(lock) {
            val toRemove = bindings.entries.filter { it.value.coreId == coreId }.map { it.key }
            toRemove.forEach { bindings.remove(it) }
        }
    }

    internal fun count(): Int = synchronized(lock) { bindings.size }
}

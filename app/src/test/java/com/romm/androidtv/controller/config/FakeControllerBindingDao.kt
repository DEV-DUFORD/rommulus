package com.romm.androidtv.controller.config

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [ControllerBindingDao] for repository unit tests.
 *
 * All rows live in a single [MutableStateFlow]; [observeCore] maps that store filtered by
 * [coreId] so it re-emits on every mutation, matching Room's invalidation-driven flow. The
 * suspend read/write methods operate on the same store and mirror REPLACE upsert and DELETE.
 */
class FakeControllerBindingDao : ControllerBindingDao {

    private val storage = MutableStateFlow<List<ControllerBindingEntity>>(emptyList())

    /** Current rows, for asserting raw persistence in tests. */
    val rows: List<ControllerBindingEntity>
        get() = storage.value

    override fun observeCore(coreId: String): Flow<List<ControllerBindingEntity>> =
        storage.map { list -> list.filter { it.coreId == coreId } }

    override suspend fun loadForCore(coreId: String): List<ControllerBindingEntity> =
        storage.value.filter { it.coreId == coreId }

    override suspend fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingEntity> =
        storage.value.filter { it.coreId == coreId && it.playerIndex == playerIndex }

    override suspend fun upsert(entity: ControllerBindingEntity) {
        val updated = storage.value.filterNot { it == entity }.toMutableList()
        updated.add(entity)
        storage.value = updated
    }

    override suspend fun upsertAll(entities: List<ControllerBindingEntity>) {
        val updated = storage.value.filterNot { stored -> entities.any { it == stored } }.toMutableList()
        updated.addAll(entities)
        storage.value = updated
    }

    override suspend fun delete(coreId: String, playerIndex: Int, controlId: String) {
        storage.value = storage.value.filterNot {
            it.coreId == coreId && it.playerIndex == playerIndex && it.controlId == controlId
        }
    }

    override suspend fun deletePlayer(coreId: String, playerIndex: Int) {
        storage.value = storage.value.filterNot {
            it.coreId == coreId && it.playerIndex == playerIndex
        }
    }

    override suspend fun deleteCore(coreId: String) {
        storage.value = storage.value.filterNot { it.coreId == coreId }
    }

    /** Remove every row (test setup/teardown helper). */
    fun clear() {
        storage.value = emptyList()
    }
}

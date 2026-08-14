@file:Suppress("unused")

package com.romm.androidtv.storage.contract

import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.*

/** Contract-test suite for [SaveStateStore] implementations. */
class SaveStateStoreContract(private val createStore: () -> SaveStateStore) {

    fun `save replica identity is preserved`() {
        val store = createStore()
        val replica = SaveReplicaRecord(
            serverKey = "srv1", userKey = "usr1", romId = 100L, romHash = "abc", slot = "auto",
            coreId = "snes9x", coreBuildRevision = "r1",
        )
        val idResult = store.upsert(replica)
        require(idResult.isSuccess) { "upsert should succeed" }
        val id = idResult.getOrNull()!!
        require(id > 0) { "id should be positive, was $id" }

        val scope = SaveReplicaScope("srv1", "usr1", 100L, "abc", "auto")
        val found = store.findByScope(scope)
        require(found != null) { "findByScope should return the upserted record" }
        require(found.id == id) { "id mismatch: expected $id, got ${found.id}" }
        require(found.serverKey == replica.serverKey)
        require(found.romId == replica.romId)
    }

    fun `replicas scoped by status`() {
        val store = createStore()
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 1L, romHash = "h1", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.UNSYNCED))
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 2L, romHash = "h2", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.SYNCED))
        store.upsert(SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 3L, romHash = "h3", slot = "a", coreId = "c", coreBuildRevision = "r", syncStatus = SaveSyncStatus.UNSYNCED))

        val unsynced = store.findByStatus("s", "u", SaveSyncStatus.UNSYNCED)
        require(unsynced.size == 2) { "Expected 2 UNSYNCED, got ${unsynced.size}" }

        val synced = store.findByStatus("s", "u", SaveSyncStatus.SYNCED)
        require(synced.size == 1) { "Expected 1 SYNCED, got ${synced.size}" }
    }

    fun `findByScope returns null for absent scope`() {
        val store = createStore()
        val scope = SaveReplicaScope("s", "u", 99L, "h", "a")
        require(store.findByScope(scope) == null) { "Absent scope should return null" }
    }

    fun `markSyncedIfGenerationMatches updates only matching generation`() {
        val store = createStore()
        val now = System.currentTimeMillis()
        val replicaCurrent = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now,
        )
        store.upsert(replicaCurrent)

        val replicaStale = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 2L, romHash = "h2", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now - 1000,
        )
        store.upsert(replicaStale)

        val scopeCurrent = SaveReplicaScope("s", "u", 1L, "h", "a")
        val updated = store.markSyncedIfGenerationMatches(scopeCurrent, now, 42L, "shash", 1024L, now + 100)
        require(updated) { "Should update matching generation" }

        val found = store.findByScope(scopeCurrent)!!
        require(found.syncStatus == SaveSyncStatus.SYNCED)
        require(found.serverHash == "shash")
        require(found.lastError == null)

        // Stale should not be updated.
        val scopeStale = SaveReplicaScope("s", "u", 2L, "h2", "a")
        val staleUpdated = store.markSyncedIfGenerationMatches(scopeStale, now, 42L, "shash", 1024L, now + 100)
        require(!staleUpdated) { "Stale generation should not update" }
    }

    fun `markSynced sets server metadata and SYNCED and clears lastError`() {
        val store = createStore()
        val now = System.currentTimeMillis()
        val replica = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c", coreBuildRevision = "r", localWrittenAtEpochMs = now, syncStatus = SaveSyncStatus.PENDING_UPLOAD, lastError = "some error",
        )
        store.upsert(replica)

        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        store.markSyncedIfGenerationMatches(scope, now, 99L, "svrhash", 2048L, now + 50)

        val found = store.findByScope(scope)!!
        require(found.syncStatus == SaveSyncStatus.SYNCED)
        require(found.rommSaveId == 99L)
        require(found.serverHash == "svrhash")
        require(found.serverSizeBytes == 2048L)
        require(found.serverUpdatedAtEpochMs == now + 50)
        require(found.lastError == null)
    }

    fun `pending op enqueue then findByStatus_Active and findById round-trip`() {
        val store = createStore()
        val now = System.currentTimeMillis()
        val op = PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
            status = PendingOperationStatus.PENDING, createdAtEpochMs = now, updatedAtEpochMs = now,
        )
        val idResult = store.enqueue(op)
        require(idResult.isSuccess)
        val id = idResult.getOrNull()!!

        val foundById = store.findById(id)
        require(foundById != null) { "findById should return the enqueued op" }
        require(foundById.status == PendingOperationStatus.PENDING)

        val pendingOps = store.findByStatus(PendingOperationStatus.PENDING)
        require(pendingOps.isNotEmpty()) { "findByStatus(PENDING) should find the op" }

        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        val active = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        require(active.isNotEmpty()) { "findActiveByScope should find the op" }
    }

    fun `updateStatus transitions state and records error_attempt`() {
        val store = createStore()
        val now = System.currentTimeMillis()
        val op = PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
            createdAtEpochMs = now, updatedAtEpochMs = now,
        )
        val idResult = store.enqueue(op)
        val id = idResult.getOrNull()!!

        val updatedNow = now + 100
        val updateResult = store.updateStatus(id, PendingOperationStatus.RUNNING, 1, "timeout", 504, updatedNow)
        require(updateResult.isSuccess) { "updateStatus should succeed" }

        val found = store.findById(id)!!
        require(found.status == PendingOperationStatus.RUNNING)
        require(found.attemptCount == 1)
        require(found.lastError == "timeout")
        require(found.lastHttpCode == 504)
        require(found.updatedAtEpochMs == updatedNow)
    }

    fun `deleteStaleForScope only removes ops older than generation`() {
        val store = createStore()
        val baseNow = System.currentTimeMillis()
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")

        // Stale op (older generation)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = baseNow - 2000,
            createdAtEpochMs = baseNow - 2000, updatedAtEpochMs = baseNow - 2000,
        ))
        // Current op (newer generation)
        store.enqueue(PendingOperationRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = baseNow,
            createdAtEpochMs = baseNow, updatedAtEpochMs = baseNow,
        ))

        val deleted = store.deleteStaleForScope(scope, PendingOperationType.UPLOAD, baseNow)
        require(deleted == 1) { "Should delete exactly 1 stale op, got $deleted" }

        val remaining = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        require(remaining.size == 1) { "Should have 1 active op remaining" }
        require(remaining[0].localGenerationEpochMs == baseNow)
    }

    fun `inTransaction commit applies both replica and op together, rollback stays completely clean`() {
        val store = createStore()

        // Verify pre-transaction state is clean.
        require(store.replicaCount() == 0)
        require(store.opCount() == 0)

        // Successful transaction: both changes visible after commit.
        val now = System.currentTimeMillis()
        val result = store.inTransaction { tx ->
            tx.upsert(SaveReplicaRecord(
                serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
                coreId = "c", coreBuildRevision = "r",
            ))
            tx.enqueue(PendingOperationRecord(
                serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
                operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
                createdAtEpochMs = now, updatedAtEpochMs = now,
            ))
            "done"
        }
        require(result.isSuccess) { "Transaction should succeed" }
        require(result.getOrNull() == "done")
        require(store.findByScope(SaveReplicaScope("s", "u", 1L, "h", "a")) != null)
        require(store.opCount() == 1)

        // Failed transaction: neither change appears.
        val prevReplicaCount = store.replicaCount()
        val prevOpCount = store.opCount()
        val failResult = store.inTransaction { tx ->
            tx.upsert(SaveReplicaRecord(
                serverKey = "s2", userKey = "u2", romId = 2L, romHash = "h2", slot = "a",
                coreId = "c", coreBuildRevision = "r",
            ))
            throw RuntimeException("deliberate failure")
        }
        require(failResult.isFailure) { "Transaction should fail" }
        require(store.replicaCount() == prevReplicaCount) { "Replicas should be unchanged after failed transaction" }
        require(store.opCount() == prevOpCount) { "Ops should be unchanged after failed transaction" }
    }

    fun `unique scope dedupe, second upsert of same scope replaces, does not duplicate`() {
        val store = createStore()
        val replica1 = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c1", coreBuildRevision = "r1", syncStatus = SaveSyncStatus.UNSYNCED,
        )
        store.upsert(replica1)

        val replica2 = SaveReplicaRecord(
            serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
            coreId = "c2", coreBuildRevision = "r2", syncStatus = SaveSyncStatus.SYNCED,
        )
        store.upsert(replica2)

        val found = store.findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))
        require(found != null)
        require(found.coreId == "c2") { "Should be replaced with second upsert" }
        require(found.syncStatus == SaveSyncStatus.SYNCED)
    }

    // Internal reflection accessors for InMemorySaveStateStore
    private fun SaveStateStore.replicaCount(): Int = when (this) {
        is com.romm.androidtv.storage.fakes.InMemorySaveStateStore -> replicaCount()
        else -> 0
    }

    private fun SaveStateStore.opCount(): Int = when (this) {
        is com.romm.androidtv.storage.fakes.InMemorySaveStateStore -> opCount()
        else -> 0
    }
}

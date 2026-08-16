package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.contract.SaveStateStoreContract
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Wires the shared main-source [SaveStateStoreContract] against [SqliteSaveStateStore]
 * (Phase 5 work item 3 / §14 gate: "run shared store contract tests against desktop adapters").
 *
 * Excluded case (deliberate, documented): `inTransaction_commit_applies_both_replica_and_op_together_rollback_stays_clean`.
 * That contract method depends on PRIVATE helper extensions inside SaveStateStoreContract
 * (`SaveStateStore.replicaCount()` / `SaveStateStore.opCount()`) that are hard-wired to
 * InMemorySaveStateStore and return 0 for every other implementation, so its
 * `require(store.opCount() == 1)` assertion can never pass against any real adapter. The
 * equivalent guarantees — commit applies both rows atomically, rollback discards partial
 * writes, verified with direct SQL row counts — are covered by the focused tests at the
 * bottom of this class. No other contract case is excluded or weakened.
 */
class SqliteSaveStateStoreContractTest {

    @TempDir
    lateinit var tempDir: Path

    private fun newStore(): SqliteSaveStateStore = SqliteSaveStateStore(openDb())

    private fun openDb(): SqliteDatabase =
        SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()

    // ── SaveStateStoreContract (9 of 10 cases; see class KDoc for the exclusion) ──

    @Test
    fun save_replica_identity_is_preserved() =
        SaveStateStoreContract(::newStore).save_replica_identity_is_preserved()

    @Test
    fun replicas_scoped_by_status() =
        SaveStateStoreContract(::newStore).replicas_scoped_by_status()

    @Test
    fun findByScope_returns_null_for_absent_scope() =
        SaveStateStoreContract(::newStore).findByScope_returns_null_for_absent_scope()

    @Test
    fun markSyncedIfGenerationMatches_updates_only_matching_generation() =
        SaveStateStoreContract(::newStore).markSyncedIfGenerationMatches_updates_only_matching_generation()

    @Test
    fun markSynced_sets_server_metadata_and_SYNCED_and_clears_lastError() =
        SaveStateStoreContract(::newStore).markSynced_sets_server_metadata_and_SYNCED_and_clears_lastError()

    @Test
    fun pending_op_enqueue_then_findByStatus_Active_and_findById_round_trip() =
        SaveStateStoreContract(::newStore).pending_op_enqueue_then_findByStatus_Active_and_findById_round_trip()

    @Test
    fun updateStatus_transitions_state_and_records_error_attempt() =
        SaveStateStoreContract(::newStore).updateStatus_transitions_state_and_records_error_attempt()

    @Test
    fun deleteStaleForScope_only_removes_ops_older_than_generation() =
        SaveStateStoreContract(::newStore).deleteStaleForScope_only_removes_ops_older_than_generation()

    @Test
    fun unique_scope_dedupe_second_upsert_of_same_scope_replaces_does_not_duplicate() =
        SaveStateStoreContract(::newStore).unique_scope_dedupe_second_upsert_of_same_scope_replaces_does_not_duplicate()

    // ── Focused replacements for the excluded contract case (row counts via SQL) ──

    private fun replicaCount(db: SqliteDatabase): Long = db.scalarLong("SELECT COUNT(*) FROM save_replicas")!!
    private fun opCount(db: SqliteDatabase): Long = db.scalarLong("SELECT COUNT(*) FROM pending_operations")!!

    @Test
    fun `inTransaction commit applies both replica and op atomically`() {
        val db = openDb()
        val store = SqliteSaveStateStore(db)
        assertThat(replicaCount(db)).isZero()
        assertThat(opCount(db)).isZero()

        val now = System.currentTimeMillis()
        val result = store.inTransaction { tx ->
            tx.upsert(
                SaveReplicaRecord(serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a", coreId = "c", coreBuildRevision = "r"),
            )
            tx.enqueue(
                PendingOperationRecord(
                    serverKey = "s", userKey = "u", romId = 1L, romHash = "h", slot = "a",
                    operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = now,
                    createdAtEpochMs = now, updatedAtEpochMs = now,
                ),
            )
            "done"
        }

        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrNull()).isEqualTo("done")
        assertThat(store.findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))).isNotNull()
        assertThat(replicaCount(db)).isEqualTo(1)
        assertThat(opCount(db)).isEqualTo(1)
    }

    @Test
    fun `inTransaction rollback discards partial writes and keeps the store usable`() {
        val db = openDb()
        val store = SqliteSaveStateStore(db)
        store.upsert(
            SaveReplicaRecord(serverKey = "keep", userKey = "u", romId = 9L, romHash = "h9", slot = "a", coreId = "c", coreBuildRevision = "r"),
        )

        val failed = store.inTransaction { tx ->
            tx.upsert(
                SaveReplicaRecord(serverKey = "s2", userKey = "u2", romId = 2L, romHash = "h2", slot = "a", coreId = "c", coreBuildRevision = "r"),
            )
            tx.enqueue(
                PendingOperationRecord(
                    serverKey = "s2", userKey = "u2", romId = 2L, romHash = "h2", slot = "a",
                    operationType = PendingOperationType.UPLOAD, localGenerationEpochMs = 1L,
                    createdAtEpochMs = 1L, updatedAtEpochMs = 1L,
                ),
            )
            throw RuntimeException("deliberate failure")
        }

        assertThat(failed.isFailure).isTrue()
        assertThat(failed.exceptionOrNull()).hasMessage("deliberate failure")
        // Partial write discarded: neither the new replica nor the op survived.
        assertThat(store.findByScope(SaveReplicaScope("s2", "u2", 2L, "h2", "a"))).isNull()
        assertThat(replicaCount(db)).isEqualTo(1)
        assertThat(opCount(db)).isZero()

        // The store remains usable after the rolled-back transaction.
        assertThat(
            store.upsert(
                SaveReplicaRecord(serverKey = "s3", userKey = "u3", romId = 3L, romHash = "h3", slot = "a", coreId = "c", coreBuildRevision = "r"),
            ).isSuccess,
        ).isTrue()
        assertThat(replicaCount(db)).isEqualTo(2)
    }
}

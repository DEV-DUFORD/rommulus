package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.SaveStateStoreContract
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import org.junit.jupiter.api.Test

class SaveStateStoreContractTest {

    private val contract = SaveStateStoreContract { InMemorySaveStateStore() }

    @Test
    fun `save replica identity is preserved`() = contract.save_replica_identity_is_preserved()

    @Test
    fun `replicas scoped by status`() = contract.replicas_scoped_by_status()

    @Test
    fun `findByScope returns null for absent scope`() = contract.findByScope_returns_null_for_absent_scope()

    @Test
    fun `markSyncedIfGenerationMatches updates only matching generation`() = contract.markSyncedIfGenerationMatches_updates_only_matching_generation()

    @Test
    fun `markSynced sets server metadata and SYNCED and clears lastError`() = contract.markSynced_sets_server_metadata_and_SYNCED_and_clears_lastError()

    @Test
    fun `pending op enqueue then findByStatus_Active and findById round-trip`() = contract.pending_op_enqueue_then_findByStatus_Active_and_findById_round_trip()

    @Test
    fun `updateStatus transitions state and records error_attempt`() = contract.updateStatus_transitions_state_and_records_error_attempt()

    @Test
    fun `deleteStaleForScope only removes ops older than generation`() = contract.deleteStaleForScope_only_removes_ops_older_than_generation()

    @Test
    fun `inTransaction commit applies both replica and op together, rollback stays completely clean`() = contract.inTransaction_commit_applies_both_replica_and_op_together_rollback_stays_clean()

    @Test
    fun `unique scope dedupe, second upsert of same scope replaces, does not duplicate`() = contract.unique_scope_dedupe_second_upsert_of_same_scope_replaces_does_not_duplicate()
}

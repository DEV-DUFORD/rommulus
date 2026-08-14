package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.SaveStateStoreContract
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import org.junit.jupiter.api.Test

class SaveStateStoreContractTest {

    private val contract = SaveStateStoreContract { InMemorySaveStateStore() }

    @Test
    fun `save replica identity is preserved`() = contract.`save replica identity is preserved`()

    @Test
    fun `replicas scoped by status`() = contract.`replicas scoped by status`()

    @Test
    fun `findByScope returns null for absent scope`() = contract.`findByScope returns null for absent scope`()

    @Test
    fun `markSyncedIfGenerationMatches updates only matching generation`() = contract.`markSyncedIfGenerationMatches updates only matching generation`()

    @Test
    fun `markSynced sets server metadata and SYNCED and clears lastError`() = contract.`markSynced sets server metadata and SYNCED and clears lastError`()

    @Test
    fun `pending op enqueue then findByStatus_Active and findById round-trip`() = contract.`pending op enqueue then findByStatus_Active and findById round-trip`()

    @Test
    fun `updateStatus transitions state and records error_attempt`() = contract.`updateStatus transitions state and records error_attempt`()

    @Test
    fun `deleteStaleForScope only removes ops older than generation`() = contract.`deleteStaleForScope only removes ops older than generation`()

    @Test
    fun `inTransaction commit applies both replica and op together, rollback stays completely clean`() = contract.`inTransaction commit applies both replica and op together, rollback stays completely clean`()

    @Test
    fun `unique scope dedupe, second upsert of same scope replaces, does not duplicate`() = contract.`unique scope dedupe, second upsert of same scope replaces, does not duplicate`()
}

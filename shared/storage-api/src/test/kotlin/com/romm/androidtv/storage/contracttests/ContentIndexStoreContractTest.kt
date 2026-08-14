package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.ContentIndexStoreContract
import com.romm.androidtv.storage.fakes.InMemoryContentIndexStore
import org.junit.jupiter.api.Test

class ContentIndexStoreContractTest {

    private val contract = ContentIndexStoreContract { InMemoryContentIndexStore() }

    @Test
    fun `upsert and get`() = contract.`upsert and get`()

    @Test
    fun `remove`() = contract.`remove`()

    @Test
    fun `evictionCandidates LRU order and limit`() = contract.`evictionCandidates LRU order and limit`()

    @Test
    fun `totalSizeBytes sum`() = contract.`totalSizeBytes sum`()

    @Test
    fun `replace updates size sum`() = contract.`replace updates size sum`()
}

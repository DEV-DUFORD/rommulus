package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.ContentIndexStoreContract
import com.romm.androidtv.storage.fakes.InMemoryContentIndexStore
import org.junit.jupiter.api.Test

class ContentIndexStoreContractTest {

    private val contract = ContentIndexStoreContract { InMemoryContentIndexStore() }

    @Test
    fun `upsert and get`() = contract.upsert_and_get()

    @Test
    fun `remove`() = contract.remove()

    @Test
    fun `evictionCandidates LRU order and limit`() = contract.evictionCandidates_LRU_order_and_limit()

    @Test
    fun `totalSizeBytes sum`() = contract.totalSizeBytes_sum()

    @Test
    fun `replace updates size sum`() = contract.replace_updates_size_sum()
}

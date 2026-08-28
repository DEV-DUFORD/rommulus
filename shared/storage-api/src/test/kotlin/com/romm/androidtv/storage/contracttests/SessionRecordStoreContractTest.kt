package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.SessionRecordStoreContract
import com.romm.androidtv.storage.fakes.InMemorySessionRecordStore
import org.junit.jupiter.api.Test

class SessionRecordStoreContractTest {

    private val contract = SessionRecordStoreContract { InMemorySessionRecordStore() }

    @Test
    fun `save and read`() = contract.save_and_read()

    @Test
    fun `clear`() = contract.clear_session()

    @Test
    fun `last write wins`() = contract.last_write_wins()
}

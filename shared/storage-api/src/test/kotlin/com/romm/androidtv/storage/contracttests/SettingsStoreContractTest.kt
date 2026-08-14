package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.SettingsStoreContract
import com.romm.androidtv.storage.fakes.InMemorySettingsStore
import org.junit.jupiter.api.Test

class SettingsStoreContractTest {

    private val contract = SettingsStoreContract { InMemorySettingsStore() }

    @Test
    fun `write default snapshot`() = contract.write_default_snapshot()

    @Test
    fun `write merge`() = contract.write_merge()

    @Test
    fun `clear`() = contract.clear_settings()

    @Test
    fun `snapshot defensive copy`() = contract.snapshot_defensive_copy()

    @Test
    fun `SettingsSnapshot boolean parsing`() = contract.SettingsSnapshot_boolean_parsing()
}

package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.contract.SettingsStoreContract
import com.romm.androidtv.storage.fakes.InMemorySettingsStore
import org.junit.jupiter.api.Test

class SettingsStoreContractTest {

    private val contract = SettingsStoreContract { InMemorySettingsStore() }

    @Test
    fun `write default snapshot`() = contract.`write default snapshot`()

    @Test
    fun `write merge`() = contract.`write merge`()

    @Test
    fun `clear`() = contract.`clear`()

    @Test
    fun `snapshot defensive copy`() = contract.`snapshot defensive copy`()

    @Test
    fun `SettingsSnapshot boolean parsing`() = contract.`SettingsSnapshot boolean parsing`()
}

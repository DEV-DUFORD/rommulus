package com.romm.desktop.storage.secret

import com.romm.androidtv.storage.contract.ClientTokenStoreContract
import org.junit.jupiter.api.Test

/**
 * Runs the shared [ClientTokenStoreContract] against the production
 * [SecretServiceClientTokenStore] (backed by [FakeSecretBackend]) to prove the
 * desktop adapter satisfies the strict fail-closed, normalized-scope contract.
 */
class SecretServiceClientTokenStoreContractTest {

    private val contract = ClientTokenStoreContract { SecretServiceClientTokenStore(FakeSecretBackend()) }

    @Test
    fun `read returns null when nothing stored`() = contract.read_returns_null_when_nothing_stored()

    @Test
    fun `write and read roundtrip`() = contract.write_and_read_roundtrip()

    @Test
    fun `write replaces existing token`() = contract.write_replaces_existing_token()

    @Test
    fun `tokens scoped by origin and username`() = contract.tokens_scoped_by_origin_and_username()

    @Test
    fun `delete removes only matching scope`() = contract.delete_removes_only_matching_scope()

    @Test
    fun `clear all removes everything`() = contract.clear_all_removes_everything()

    @Test
    fun `write blank payload returns failure`() = contract.write_blank_payload_returns_failure()

    @Test
    fun `scope key normalizes origin and username`() = contract.scope_key_normalizes_origin_and_username()
}

package com.romm.desktop.storage

import com.romm.androidtv.auth.TokenPersistResult
import com.romm.androidtv.romm.ClientToken
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.storage.secret.KeyringState
import com.romm.desktop.storage.secret.SecretServiceClientTokenStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Focused tests for [DesktopClientTokenStorage] mapping the network seam onto a
 * storage-api [com.romm.androidtv.storage.ports.ClientTokenStore]: get/set/clear and
 * [TokenPersistResult] outcome mapping, using [FakeSecretBackend] behind
 * [SecretServiceClientTokenStore] (no D-Bus, no real keyring).
 */
class DesktopClientTokenStorageTest {

    private fun storage(backendMode: KeyringState = KeyringState.Available): DesktopClientTokenStorage =
        DesktopClientTokenStorage(SecretServiceClientTokenStore(FakeSecretBackend(mode = backendMode)))

    @Test
    fun `set then get round-trips the raw token`() {
        val storage = storage()
        assertThat(storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-abc")))
            .isEqualTo(TokenPersistResult.Success)

        assertThat(storage.getToken("https://romm.example.com", "player1"))
            .isEqualTo(ClientToken(raw = "tok-abc"))
    }

    @Test
    fun `get returns null for absent scope`() {
        val storage = storage()
        assertThat(storage.getToken("https://romm.example.com", "nobody")).isNull()
    }

    @Test
    fun `tokens are scoped by origin and username`() {
        val storage = storage()
        storage.setToken("https://a.example.com", "u1", ClientToken(raw = "tok-a"))
        storage.setToken("https://b.example.com", "u1", ClientToken(raw = "tok-b"))

        assertThat(storage.getToken("https://a.example.com", "u1")?.raw).isEqualTo("tok-a")
        assertThat(storage.getToken("https://b.example.com", "u1")?.raw).isEqualTo("tok-b")
        assertThat(storage.getToken("https://a.example.com", "u2")).isNull()
    }

    @Test
    fun `set replaces an existing token for the same scope`() {
        val storage = storage()
        storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-old"))
        assertThat(storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-new")))
            .isEqualTo(TokenPersistResult.Success)

        assertThat(storage.getToken("https://romm.example.com", "player1")?.raw).isEqualTo("tok-new")
    }

    @Test
    fun `clear removes the token`() {
        val storage = storage()
        storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-abc"))
        assertThat(storage.getToken("https://romm.example.com", "player1")).isNotNull

        storage.clearToken("https://romm.example.com", "player1")
        assertThat(storage.getToken("https://romm.example.com", "player1")).isNull()
    }

    @Test
    fun `locked keyring maps to TokenPersistResult Failure`() {
        val storage = storage(backendMode = KeyringState.Locked)
        assertThat(storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-abc")))
            .isEqualTo(TokenPersistResult.Failure)
        // Fail-closed read: a locked keyring is "no token".
        assertThat(storage.getToken("https://romm.example.com", "player1")).isNull()
    }

    @Test
    fun `unavailable secret service maps to TokenPersistResult Failure`() {
        val storage = storage(backendMode = KeyringState.Unavailable)
        assertThat(storage.setToken("https://romm.example.com", "player1", ClientToken(raw = "tok-abc")))
            .isEqualTo(TokenPersistResult.Failure)
    }
}

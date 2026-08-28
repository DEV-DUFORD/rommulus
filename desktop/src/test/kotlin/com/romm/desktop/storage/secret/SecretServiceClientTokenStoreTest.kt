package com.romm.desktop.storage.secret

import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SecretServiceClientTokenStoreTest {

    private val backend = FakeSecretBackend()
    private val store = SecretServiceClientTokenStore(backend)

    private fun token(payload: String) = ClientTokenRecord(payload = payload)

    @Test
    fun `write returns success and read round-trips the payload`() {
        val outcome = store.write("https://example.com", "alice", token("secret-1"))
        assertThat(outcome).isEqualTo(TokenPersistOutcome.Success)
        assertThat(store.read("https://example.com", "alice")).isEqualTo(token("secret-1"))
    }

    @Test
    fun `read returns null for absent scope`() {
        assertThat(store.read("https://example.com", "nobody")).isNull()
    }

    @Test
    fun `scopes are isolated by origin`() {
        store.write("https://a.example.com", "alice", token("a"))
        assertThat(store.read("https://b.example.com", "alice")).isNull()
    }

    @Test
    fun `scopes are isolated by username`() {
        store.write("https://example.com", "alice", token("alice-token"))
        assertThat(store.read("https://example.com", "bob")).isNull()
    }

    @Test
    fun `origin and username are normalized to lowercase and trimmed`() {
        store.write("https://Example.COM", "  Alice  ", token("x"))
        assertThat(store.read("https://example.com", "alice")).isEqualTo(token("x"))
    }

    @Test
    fun `second write overwrites the same scope`() {
        store.write("https://example.com", "alice", token("first"))
        store.write("https://example.com", "alice", token("second"))
        assertThat(store.read("https://example.com", "alice")).isEqualTo(token("second"))
    }

    @Test
    fun `delete removes only the matching scope`() {
        store.write("https://example.com", "alice", token("alice-token"))
        store.write("https://example.com", "bob", token("bob-token"))
        store.delete("https://example.com", "alice")
        assertThat(store.read("https://example.com", "alice")).isNull()
        assertThat(store.read("https://example.com", "bob")).isEqualTo(token("bob-token"))
    }

    @Test
    fun `clearAll removes every scope`() {
        store.write("https://example.com", "alice", token("a"))
        store.write("https://example.com", "bob", token("b"))
        store.clearAll()
        assertThat(store.read("https://example.com", "alice")).isNull()
        assertThat(store.read("https://example.com", "bob")).isNull()
    }

    @Test
    fun `locked keyring fails closed`() {
        store.write("https://example.com", "alice", token("stored"))
        backend.mode = KeyringState.Locked
        assertThat(store.keyringState()).isEqualTo(KeyringState.Locked)
        assertThat(store.read("https://example.com", "alice")).isNull()
        val outcome = store.write("https://example.com", "alice", token("new"))
        assertThat(outcome).isInstanceOf(TokenPersistOutcome.Failure::class.java)
        assertThat((outcome as TokenPersistOutcome.Failure).reason).contains("locked")
    }

    @Test
    fun `unavailable service fails closed`() {
        backend.mode = KeyringState.Unavailable
        assertThat(store.read("https://example.com", "alice")).isNull()
        val outcome = store.write("https://example.com", "alice", token("new"))
        assertThat((outcome as TokenPersistOutcome.Failure).reason).contains("unavailable")
    }

    @Test
    fun `denied access fails closed with reason`() {
        backend.mode = KeyringState.Denied("org.freedesktop.secrets not allowed")
        val outcome = store.write("https://example.com", "alice", token("new"))
        val failure = outcome as TokenPersistOutcome.Failure
        assertThat(failure.reason).contains("denied")
        assertThat(failure.reason).contains("org.freedesktop.secrets")
    }

    @Test
    fun `blank payload is rejected`() {
        val outcome = store.write("https://example.com", "alice", token("   "))
        val failure = outcome as TokenPersistOutcome.Failure
        assertThat(failure.reason).contains("blank")
    }
}

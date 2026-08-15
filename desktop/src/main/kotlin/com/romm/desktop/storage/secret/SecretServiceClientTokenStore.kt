package com.romm.desktop.storage.secret

import com.romm.androidtv.storage.ports.ClientTokenStore
import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord

/**
 * Linux Secret Service implementation of [ClientTokenStore], backed by a
 * [SecretBackend]. Mirrors the Android Keystore adapter's fail-closed mapping:
 * any read failure (including a locked/unavailable/denied keyring) is treated
 * as "no token" (null); any write failure is a [TokenPersistOutcome.Failure]
 * with an actionable reason. Scoping is per canonical origin + normalized
 * username, matching the Android adapter's "origin|username" key.
 */
class SecretServiceClientTokenStore(
    private val backend: SecretBackend,
) : ClientTokenStore {

    /** Exposed so the desktop onboarding flow can surface an actionable keyring error. */
    fun keyringState(): KeyringState = backend.state()

    override fun read(origin: String, username: String): ClientTokenRecord? =
        runCatching { backend.retrieve(scopeKey(origin, username)) }
            .getOrNull()
            ?.let { ClientTokenRecord(payload = it, scopeVersion = 2) }

    override fun write(origin: String, username: String, token: ClientTokenRecord): TokenPersistOutcome {
        if (token.payload.isBlank()) {
            return TokenPersistOutcome.Failure("blank token payload")
        }
        return when (val state = backend.state()) {
            is KeyringState.Available -> {
                val ok = runCatching { backend.store(scopeKey(origin, username), token.payload) }
                    .getOrDefault(false)
                if (ok) TokenPersistOutcome.Success else TokenPersistOutcome.Failure("secret store failed")
            }
            is KeyringState.Locked -> TokenPersistOutcome.Failure("keyring locked")
            is KeyringState.Unavailable -> TokenPersistOutcome.Failure("secret service unavailable")
            is KeyringState.Denied -> TokenPersistOutcome.Failure("secret service denied: ${state.reason}")
        }
    }

    override fun delete(origin: String, username: String) {
        runCatching { backend.delete(scopeKey(origin, username)) }
    }

    override fun clearAll() {
        runCatching { backend.deleteAll() }
    }

    private fun scopeKey(origin: String, username: String): String =
        "${origin.trim().lowercase()}|${username.trim().lowercase()}"
}

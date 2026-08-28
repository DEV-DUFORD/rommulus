package com.romm.desktop.storage

import com.romm.androidtv.auth.ClientTokenStorage
import com.romm.androidtv.auth.TokenPersistResult
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.storage.ports.ClientTokenStore
import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord

/**
 * Desktop [ClientTokenStorage] seam adapter (plans/PHASE6.md §5): durable client tokens
 * stay in the platform Secret Service, never in SQLite or settings JSON. Production wiring
 * passes [com.romm.desktop.storage.secret.SecretServiceClientTokenStore] (constructed with a
 * `SecretBackend`); any storage-api [ClientTokenStore] works here, and tests may substitute
 * an in-memory one.
 *
 * Mapping is 1:1 with the Android adapter's fail-closed behavior:
 * - [getToken] returns null on absent scope OR any read failure (locked/unavailable
 *   keyring is "no token", matching `SecretServiceClientTokenStore.read`);
 * - [setToken] maps [TokenPersistOutcome.Success] → [TokenPersistResult.Success] and any
 *   [TokenPersistOutcome.Failure] (blank payload, locked/unavailable/denied keyring) →
 *   [TokenPersistResult.Failure], so onboarding can treat persistence as fatal;
 * - [clearToken] is best-effort (explicit sign-out).
 */
class DesktopClientTokenStorage(
    private val store: ClientTokenStore,
) : ClientTokenStorage {

    override fun getToken(origin: String, username: String): ClientToken? =
        store.read(origin, username)?.let { ClientToken(raw = it.payload) }

    override fun setToken(origin: String, username: String, token: ClientToken): TokenPersistResult =
        when (val outcome = store.write(origin, username, ClientTokenRecord(payload = token.raw))) {
            is TokenPersistOutcome.Success -> TokenPersistResult.Success
            is TokenPersistOutcome.Failure -> TokenPersistResult.Failure
        }

    override fun clearToken(origin: String, username: String) {
        store.delete(origin, username)
    }
}

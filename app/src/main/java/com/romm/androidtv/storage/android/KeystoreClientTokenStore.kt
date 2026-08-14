package com.romm.androidtv.storage.android

import com.romm.androidtv.auth.TokenPersistResult
import com.romm.androidtv.romm.ClientToken
import com.romm.androidtv.storage.ports.ClientTokenStore
import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord

/**
 * Thin adapter: delegates the platform-neutral [ClientTokenStore] port to the
 * Android Keystore-backed [com.romm.androidtv.romm.ClientTokenStore].
 *
 * The delegate is intentionally named [com.romm.androidtv.romm.ClientTokenStore]
 * (the Keystore class in `com.romm.androidtv.romm`), distinct from this adapter
 * and from the port interface [ClientTokenStore] to avoid a name collision.
 *
 * Mapping is 1:1 by field:
 *  - `read`  → `delegate.getToken(origin, username)?.raw` → [ClientTokenRecord] (scopeVersion = 2).
 *  - `write` → `delegate.setToken(origin, username, ClientToken(token.payload))` →
 *    [TokenPersistOutcome.Success] / [TokenPersistOutcome.Failure] from [TokenPersistResult].
 *  - `delete`/`clearAll` → `delegate.clearToken` / `delegate.clearAll`.
 *
 * All fallible calls are wrapped in `runCatching` so a Keystore/prefs throw is
 * surfaced as the port's exact return type (null for `read`, `Failure` for `write`).
 */
class KeystoreClientTokenStore(
    private val delegate: com.romm.androidtv.romm.ClientTokenStore,
) : ClientTokenStore {

    override fun read(origin: String, username: String): ClientTokenRecord? =
        runCatching { delegate.getToken(origin, username) }
            .getOrNull()
            ?.toRecord()

    override fun write(origin: String, username: String, token: ClientTokenRecord): TokenPersistOutcome =
        runCatching {
            delegate.setToken(origin, username, ClientToken(token.payload))
        }.map { result ->
            when (result) {
                is TokenPersistResult.Success -> TokenPersistOutcome.Success
                is TokenPersistResult.Failure ->
                    TokenPersistOutcome.Failure("ClientTokenStore.setToken failed to persist")
            }
        }.getOrElse { e ->
            TokenPersistOutcome.Failure(e.message ?: "ClientTokenStore.setToken threw")
        }

    override fun delete(origin: String, username: String) {
        runCatching { delegate.clearToken(origin, username) }
    }

    override fun clearAll() {
        runCatching { delegate.clearAll() }
    }
}

/**
 * Map a native [ClientToken] to a storage-neutral [ClientTokenRecord].
 *
 * [ClientToken.raw] is the opaque serialized string; the port's scope version is
 * pinned to the record default (2). Factored out for JVM unit-testing.
 */
internal fun ClientToken.toRecord(): ClientTokenRecord =
    ClientTokenRecord(payload = raw)

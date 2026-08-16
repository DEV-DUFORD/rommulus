package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.ClientTokenStore
import com.romm.androidtv.storage.ports.TokenPersistOutcome
import com.romm.androidtv.storage.records.ClientTokenRecord

/**
 * In-memory client token store. FOR TESTS ONLY -- never for production token storage.
 * Tokens are stored as plaintext in a mutable map, keyed by the normalized
 * "origin|username" scope key (trimmed + lowercased), mirroring the desktop
 * SecretServiceClientTokenStore and the Android Keystore adapter. Writes with a
 * blank/whitespace payload fail closed with [TokenPersistOutcome.Failure].
 */
class InMemoryClientTokenStore : ClientTokenStore {

    private val tokens = mutableMapOf<String, ClientTokenRecord>()

    private fun key(origin: String, username: String): String =
        "${origin.trim().lowercase()}|${username.trim().lowercase()}"

    override fun read(origin: String, username: String): ClientTokenRecord? {
        return synchronized(tokens) { tokens[key(origin, username)] }
    }

    override fun write(origin: String, username: String, token: ClientTokenRecord): TokenPersistOutcome {
        return synchronized(tokens) {
            if (token.payload.isBlank()) {
                return TokenPersistOutcome.Failure("blank token payload")
            }
            tokens[key(origin, username)] = token
            TokenPersistOutcome.Success
        }
    }

    override fun delete(origin: String, username: String) {
        synchronized(tokens) { tokens.remove(key(origin, username)) }
    }

    override fun clearAll() {
        synchronized(tokens) { tokens.clear() }
    }
}

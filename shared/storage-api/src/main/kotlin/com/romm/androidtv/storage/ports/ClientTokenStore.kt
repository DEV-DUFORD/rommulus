package com.romm.androidtv.storage.ports

import com.romm.androidtv.storage.records.ClientTokenRecord

/** Outcome of a client-token persistence operation. */
sealed interface TokenPersistOutcome {
    data object Success : TokenPersistOutcome
    data class Failure(val reason: String) : TokenPersistOutcome
}

/** Persistence-neutral store for client tokens, scoped by origin+username. */
interface ClientTokenStore {
    fun read(origin: String, username: String): ClientTokenRecord?
    fun write(origin: String, username: String, token: ClientTokenRecord): TokenPersistOutcome
    fun delete(origin: String, username: String)
    fun clearAll()
}

package com.romm.androidtv.storage.records

/**
 * A storage-neutral representation of a client token.
 *
 * The [payload] field is an opaque serialized string; the store does not parse or
 * interpret it. This mirrors the Android ClientToken (which holds `raw: String`)
 * but adds a versioned scope dimension for future migration safety.
 */
data class ClientTokenRecord(
    val payload: String,
    val scopeVersion: Int = 2,
)

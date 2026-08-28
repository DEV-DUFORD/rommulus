package com.romm.androidtv.storage.records

/**
 * One durable session record, capturing the current authenticated user context.
 */
data class SessionRecord(
    val origin: String,
    val username: String?,
    val verifiedAtEpochMillis: Long,
    val kioskMode: Boolean = false,
)

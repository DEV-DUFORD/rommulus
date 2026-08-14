package com.romm.androidtv.storage.records

/**
 * The kind of durable work a pending operation represents.
 * Mirrors the Android Room enum exactly (PendingOperationEntity.kt).
 */
enum class PendingOperationType {
    UPLOAD,
    /** Post-play negotiated sync: hashed checkpointed SRAM, detected generation change, queued operation. */
    NEGOTIATE_AND_SYNC,
}

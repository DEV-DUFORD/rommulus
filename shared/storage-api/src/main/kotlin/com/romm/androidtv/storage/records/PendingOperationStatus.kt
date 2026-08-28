package com.romm.androidtv.storage.records

/**
 * The state-machine status of one pending operation.
 * Mirrors the Android Room enum exactly (PendingOperationEntity.kt).
 */
enum class PendingOperationStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    RETRYABLE_FAILURE,
    AUTH_REQUIRED,
    CONFLICT,
    PERMANENT_FAILURE,
}

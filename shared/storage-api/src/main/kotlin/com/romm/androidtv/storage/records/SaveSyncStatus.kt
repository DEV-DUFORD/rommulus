package com.romm.androidtv.storage.records

/**
 * Where one save replica currently stands relative to the RomM server.
 * Mirrors the Android Room enum exactly (SaveReplicaEntity.kt).
 */
enum class SaveSyncStatus {
    /** Freshly written locally; not yet negotiated against the server. */
    UNSYNCED,

    /** Local hash matches the last confirmed server round trip. */
    SYNCED,

    /** A local write is queued to upload but hasn't completed yet. */
    PENDING_UPLOAD,

    /** A server-authoritative save has been requested but isn't durably adopted yet. */
    PENDING_DOWNLOAD,

    /** The server negotiated a conflict outcome; automatic replacement is stopped. */
    CONFLICT,

    /** Downloaded save has unknown/incompatible provenance. Preserved on disk but never auto-adopted. */
    QUARANTINED,

    /** Server's save downloaded with validated provenance but unknown SRAM size; core must validate first. */
    AWAITING_CORE_VALIDATION,
}

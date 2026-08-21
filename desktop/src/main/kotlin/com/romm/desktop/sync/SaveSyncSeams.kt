package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.PlaySessionIngestRequest
import com.romm.androidtv.romm.PlaySessionIngestResult
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncCompleteResult
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult

/**
 * Injection seams for [SaveSyncDrainExecutor] (Phase 9, plans/LINUX_X64.md — drain executor
 * sub-unit). Mirrors the Android `SessionReader` / `DeviceIdentityLoader` / `SaveContentStore` /
 * `SaveUploadCaller` seams so the state machine can be unit-tested with fakes and no server.
 *
 * Production implementations (`FileSaveContentGateway`, `RommSyncApiGateway`) are wired into
 * [com.romm.desktop.DesktopAppCoordinator] in a FOLLOW-UP sub-unit; this file keeps the executor
 * free of any session/network/filesystem coupling.
 */

/**
 * The currently authenticated session, as needed by the drain: the canonical server origin and,
 * when known, the username. Mirrors Android's `DurableSession`. Null from [SaveSyncSessionReader.current]
 * means "not signed in" (operations then classify AUTH_REQUIRED).
 */
data class SaveSyncSession(val origin: String, val username: String?)

/** Reads the durable current session. Production impl reads the desktop session record store. */
fun interface SaveSyncSessionReader {
    fun current(): SaveSyncSession?
}

/**
 * Resolves (registering if needed) the RomM device identity for [origin] + [username].
 * Mirrors Android's `DeviceIdentityLoader`; null means the device could not be registered
 * (operations then classify AUTH_REQUIRED).
 */
fun interface SaveSyncDeviceIdentityLoader {
    fun load(origin: String, username: String): DeviceIdentity?
}

/** SHA-256 (lowercase hex) + byte size of a save payload. */
data class SaveContentHash(val sha256Hex: String, val sizeBytes: Long)

/**
 * Local filesystem access for durable autosave SRAM bytes — the desktop mirror of Android's
 * `SaveContentStore` (LIBRETRO_REFACTOR.md section 11.1). Paths are derived from
 * [com.romm.androidtv.emulation.model.SavePathPolicy]; writes are crash-safe
 * (temp + fsync + atomic rename, same pattern as LaunchJournalSupervisor/SecureFiles).
 */
interface SaveContentGateway {
    /** Reads the current durable local autosave bytes for this scope, or null if none exist yet. */
    fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray?

    /**
     * Atomically replaces the durable local autosave for this scope with [bytes]:
     * write-temp, `fsync`, atomic rename. Never leaves a partially written file at the final path.
     */
    fun writeLocalAtomically(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray)

    /**
     * Preserves [bytes] under a quarantine-specific name instead of ever touching the real autosave
     * path (unknown-provenance / size-mismatch downloads). Returns the absolute path preserved.
     */
    fun quarantine(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        reason: String,
        nowEpochMs: Long,
    ): String

    /**
     * Durably preserves [bytes] under a deterministic conflict-backup path (a sibling of the slot
     * directory) so the LOSING copy of an explicit conflict resolution is never silently discarded
     * (mirrors Android's `SaveContentStore.conflictBackup`). The path is keyed by [choice] +
     * [contentHash] and idempotent: re-resolving with identical content converges on one file.
     * Returns the absolute path preserved.
     */
    fun conflictBackup(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        choice: String,
        contentHash: String,
    ): String

    /** Computes the SHA-256 hash and size of [bytes] (pure — overridable in tests). */
    fun hashAndSize(bytes: ByteArray): SaveContentHash = SaveContentHash(sha256Hex(bytes), bytes.size.toLong())
}

/**
 * The RomM negotiated-sync HTTP surface used by the drain, wrapped behind a seam so tests can
 * fake every call without a server. Production impl: [RommSyncApiGateway] (delegates to
 * `com.romm.androidtv.romm.RommSyncApi`'s blocking methods).
 */
interface RommSyncGateway {
    /** `POST /api/sync/negotiate` — fresh session, never reuses a stale pre-play one. */
    fun negotiateSync(origin: String, request: SyncNegotiateRequest): SyncNegotiateResult

    /** `POST /api/sync/sessions/{id}/complete` with exact completed/failed counters. Non-fatal on failure. */
    fun completeSyncSession(origin: String, sessionId: Long, request: SyncCompleteRequest): SyncCompleteResult

    /** `POST /api/saves` (multipart). 409 surfaces as [SaveUploadResult.Conflict]. */
    fun uploadSave(origin: String, request: SaveUploadRequest): SaveUploadResult

    /** `GET /api/saves/{id}/content` with session bookkeeping. */
    fun downloadSaveContent(origin: String, saveId: Long, deviceId: String, sessionId: Long?): SaveDownloadResult

    /** `GET /api/saves/{id}/content?optimistic=false` — no session bookkeeping (conflict backups). */
    fun downloadSaveContentBackup(origin: String, saveId: Long, deviceId: String): SaveDownloadResult

    /** `POST /api/saves/{id}/downloaded` — confirms an adopted download. */
    fun confirmDownload(origin: String, saveId: Long, deviceId: String): SaveConfirmResult

    /**
     * `GET /api/saves?rom_id=X[&device_id=Y]` — every save the user owns for a ROM, across slots
     * and devices. Conflict-resolution fallback when the replica has no recorded server save id.
     */
    fun listSaves(origin: String, romId: Long, deviceId: String?): SaveListResult

    /**
     * `POST /api/play-sessions` — best-effort play-session telemetry so the server advances
     * `rom_user.last_played`/`now_playing` (the Home screen's "Continue Playing" row). A failure
     * here must never block save-sync or gameplay.
     */
    fun ingestPlaySessions(origin: String, request: PlaySessionIngestRequest): PlaySessionIngestResult
}

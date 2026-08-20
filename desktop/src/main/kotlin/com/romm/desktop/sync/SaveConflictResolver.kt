package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SaveReplicaStore
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.time.Instant
import java.util.logging.Logger

/**
 * The user's explicit choice for a CONFLICT replica (mirrors Android's `ConflictChoice` plus the
 * desktop quarantine escape hatch). [QUARANTINE] is offered for incompatible-provenance conflicts,
 * where KEEP_LOCAL/KEEP_SERVER are rejected: it preserves the server copy in the quarantine dir
 * and settles the replica QUARANTINED without ever adopting or uploading it.
 */
enum class SaveConflictChoice { KEEP_LOCAL, KEEP_SERVER, QUARANTINE }

/**
 * Result of an explicit conflict-resolution attempt. On [Failure] the choice was NOT applied:
 * both copies are preserved exactly as found (the local bytes were never touched before the
 * losing copy was durably backed up).
 */
sealed interface SaveConflictResolutionResult {
    data class Success(
        val choice: SaveConflictChoice,
        /** Where the losing SERVER copy was durably preserved (keep-local only), if any. */
        val serverBackupPath: String? = null,
        /** Where the losing LOCAL copy was durably preserved (keep-server only), if any. */
        val localBackupPath: String? = null,
        /** Where the server copy was preserved in the quarantine dir ([QUARANTINE] choice only). */
        val quarantinedPath: String? = null,
    ) : SaveConflictResolutionResult

    /** [reason] is safe to surface in the UI as-is. */
    data class Failure(val reason: String, val httpCode: Int? = null) : SaveConflictResolutionResult
}

/**
 * Desktop port of Android's `ConflictResolverImpl` (LIBRETRO_REFACTOR.md section 11.3 — the
 * user-facing half of Phase 9's "conflict preserves both copies"). Honors the user's explicit
 * choice for one CONFLICT replica:
 *
 * **KEEP LOCAL** (local file wins, uploaded over server):
 *  1. Read the durable local bytes (abort if missing — nothing to keep).
 *  2. Fetch the losing server copy (`downloadSaveContentBackup`, no session bookkeeping) and
 *     durably back it up under a deterministic conflict-backup path BEFORE any overwrite.
 *  3. Upload local bytes with `overwrite = true`.
 *  4. Persist the replica as SYNCED (new local generation, server metadata from the upload)
 *     ONLY after the upload succeeds.
 *  The PROVENANCE GUARD (described under KEEP SERVER) runs before any of this: uploading a
 *  different-core save over the server would be exactly the data-safety error it prevents.
 *
 * **KEEP SERVER** (server copy wins, adopted locally):
 *  1. Identify the server save against the server's own listing (the recorded `rommSaveId` when
 *     present in it, else the newest listed save matching the slot — fallback for conflicts that
 *     predate that record). The listing also carries the save's emulator: the PROVENANCE GUARD
 *     (mirroring Android's `isProvenanceCompatible`) rejects KEEP_LOCAL and KEEP_SERVER with
 *     "incompatible-provenance … quarantine UI only" when it differs from this replica's coreId —
 *     an incompatible-provenance conflict can never be resolved by adopting/uploading a
 *     different-core save. Unknown provenance (null emulator) is rejected the same way.
 *  2. Download the server bytes; validate hash + exact known SRAM size (no fabrication).
 *  3. Durably back up the losing local copy BEFORE atomic replacement.
 *  4. Adopt atomically, re-hash, persist the replica as SYNCED, confirm the download.
 *
 * **QUARANTINE** (the bad server copy is preserved, never adopted — the escape hatch for
 * incompatible-provenance conflicts, where both keep-choices above are rejected):
 *  1. Identify the server save (same listing lookup; no provenance gate here — quarantining an
 *     unknown/different-core copy is exactly the point).
 *  2. Download the server bytes (no session bookkeeping) and preserve them via
 *     `content.quarantine(..., reason = "conflict")` in the quarantine dir.
 *  3. Persist the replica as QUARANTINED (`lastError = "quarantined: conflict"`). The local bytes
 *     are never touched (the save stays playable) and nothing is uploaded or confirmed.
 *  QUARANTINED is a blocked-replay status, so the drain never re-negotiates around it.
 *
 * No timestamps are used for decision-making and there is no automatic choice — the user's click
 * is the only input. Status is persisted only after all network operations succeed; any failure
 * preserves both copies and returns [SaveConflictResolutionResult.Failure].
 *
 * Unlike Android, the original negotiation session is long gone by resolution time (the drain
 * completed it with exact counters when the conflict was recorded), so no `sessionId`/session
 * bookkeeping is carried on these calls — server-side completion counters were already settled.
 */
class SaveConflictResolver(
    private val saveReplicas: SaveReplicaStore,
    private val content: SaveContentGateway,
    private val sessionReader: SaveSyncSessionReader,
    private val deviceIdentityLoader: SaveSyncDeviceIdentityLoader,
    private val sync: RommSyncGateway,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** Resolved per-upload: governs the server auto-clean of the autosave slot. Defaults to on. */
    private val shouldAutoclean: () -> Boolean = { true },
) {

    /**
     * Applies [choice] (KEEP_LOCAL / KEEP_SERVER / QUARANTINE) to [replica]. The replica is
     * re-read from the store first so a concurrent change (e.g. the user played again) invalidates
     * the choice instead of acting on a stale row. Only CONFLICT replicas resolve; anything else
     * returns [SaveConflictResolutionResult.Failure] without touching any data.
     */
    fun resolve(replica: SaveReplicaRecord, choice: SaveConflictChoice): SaveConflictResolutionResult {
        val current = saveReplicas.findByScope(
            SaveReplicaScope(replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot),
        ) ?: return SaveConflictResolutionResult.Failure("replica-missing: the save record is gone")
        if (current.syncStatus != SaveSyncStatus.CONFLICT) {
            // Resolution is offered only on CONFLICT — enforce it here too so a UI bug or a race
            // can never apply a choice to a healthy/in-flight replica.
            return SaveConflictResolutionResult.Failure(
                "not-conflict: the save is '${current.syncStatus.name}', no resolution needed",
            )
        }

        val session = sessionReader.current()
            ?: return SaveConflictResolutionResult.Failure("no active session — log in again to resolve")
        val username = session.username
            ?: return SaveConflictResolutionResult.Failure("no active session — log in again to resolve")
        val origin = session.origin

        val deviceIdentity = deviceIdentityLoader.load(origin, username)
            ?: return SaveConflictResolutionResult.Failure("device not registered — cannot resolve")

        val deviceId = deviceIdentity.rommDeviceId
        return when (choice) {
            SaveConflictChoice.KEEP_LOCAL -> resolveKeepLocal(current, origin, deviceId)
            SaveConflictChoice.KEEP_SERVER -> resolveKeepServer(current, origin, deviceId)
            SaveConflictChoice.QUARANTINE -> resolveQuarantine(current, origin, deviceId)
        }
    }

    // ------------------------------------------------------------------ KEEP LOCAL

    private fun resolveKeepLocal(replica: SaveReplicaRecord, origin: String, deviceId: String): SaveConflictResolutionResult {
        val localBytes = content.readLocal(replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot)
            ?: return SaveConflictResolutionResult.Failure(
                "no-local-bytes: cannot keep local without a durable local save",
            )

        // Preserve the LOSING server copy before any overwrite (Android conflictBackup). The
        // server save id comes from the replica's record (the drain persists it when the conflict
        // is negotiated); when it predates that, list the ROM's saves. If neither identifies a
        // server copy, ABORT — Android's ConflictResolverImpl never overwrites without first
        // durably backing up the losing copy, and uploading overwrite=true here would silently
        // destroy it (data loss). The local copy is preserved; the user can retry once the
        // server save becomes identifiable again.
        val serverSave = findServerSave(replica, origin, deviceId)
            ?: return SaveConflictResolutionResult.Failure(
                "server-save-unidentified: cannot resolve conflict — the server copy could not be identified or backed up",
            )

        // Provenance guard (Android isProvenanceCompatible): uploading a save written by a
        // different core over the server would corrupt this slot for every other device.
        if (!isProvenanceCompatible(replica.coreId, serverSave.emulator)) {
            return SaveConflictResolutionResult.Failure(
                "incompatible-provenance: local core '${replica.coreId}' vs server '${serverSave.emulator}' — quarantine UI only",
            )
        }

        val serverBytes = when (val dl = sync.downloadSaveContentBackup(origin, serverSave.saveId, deviceId)) {
            is SaveDownloadResult.Success -> dl.bytes
            is SaveDownloadResult.Failure -> return SaveConflictResolutionResult.Failure(
                "server-backup-download-failed: ${dl.error}", dl.httpCode,
            )
        }
        val actualServerHash = sha256Hex(serverBytes)
        if (replica.serverHash != null && replica.serverHash != actualServerHash) {
            return SaveConflictResolutionResult.Failure(
                "server-hash-mismatch: expected '${replica.serverHash}', got '$actualServerHash'",
            )
        }
        val serverBackupPath = content.conflictBackup(
            replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot,
            bytes = serverBytes, choice = "keep-local", contentHash = actualServerHash,
        )

        // Local wins: upload over the server with overwrite=true (no session bookkeeping — see class doc).
        val uploadResult = sync.uploadSave(
            origin,
            SaveUploadRequest(
                romId = replica.romId,
                slot = replica.slot,
                emulator = replica.coreId,
                deviceId = deviceId,
                sessionId = null,
                overwrite = true,
                fileName = "${replica.slot}.srm",
                bytes = localBytes,
                autocleanup = shouldAutoclean(),
                autocleanupLimit = 5,
            ),
        )

        return when (uploadResult) {
            is SaveUploadResult.Success -> {
                val now = clock()
                upsert(replica.copy(
                    rommSaveId = uploadResult.save.saveId,
                    serverHash = uploadResult.save.contentHash ?: sha256Hex(localBytes),
                    serverSizeBytes = uploadResult.save.fileSizeBytes,
                    serverUpdatedAtEpochMs = uploadResult.save.updatedAt?.toEpochMilli(),
                    localHash = sha256Hex(localBytes),
                    localSizeBytes = localBytes.size.toLong(),
                    // Mirror Android: the resolved generation is stamped at resolution time.
                    localWrittenAtEpochMs = now,
                    syncStatus = SaveSyncStatus.SYNCED,
                    lastError = null,
                ))
                SaveConflictResolutionResult.Success(
                    choice = SaveConflictChoice.KEEP_LOCAL,
                    serverBackupPath = serverBackupPath,
                )
            }
            is SaveUploadResult.Conflict ->
                // Server still reports conflict after overwrite=true — unexpected; abort. Both copies
                // remain intact (server copy backed up above, local bytes never touched).
                SaveConflictResolutionResult.Failure(
                    "upload-still-conflict: the server rejected the overwrite; both copies are preserved",
                    uploadResult.httpCode,
                )
            is SaveUploadResult.Failure ->
                // Upload failed — the server backup is preserved and local bytes are untouched.
                SaveConflictResolutionResult.Failure("upload-failed: ${uploadResult.error}", uploadResult.httpCode)
        }
    }

    // ------------------------------------------------------------------ KEEP SERVER

    private fun resolveKeepServer(replica: SaveReplicaRecord, origin: String, deviceId: String): SaveConflictResolutionResult {
        val serverSave = findServerSave(replica, origin, deviceId)
            ?: return SaveConflictResolutionResult.Failure(
                "no-server-save: the server has no save to keep for this game",
            )

        // Provenance guard (Android isProvenanceCompatible): adopting a different-core save would
        // replace the playable local copy with bytes this core cannot load.
        if (!isProvenanceCompatible(replica.coreId, serverSave.emulator)) {
            return SaveConflictResolutionResult.Failure(
                "incompatible-provenance: local core '${replica.coreId}' vs server '${serverSave.emulator}' — quarantine UI only",
            )
        }

        val serverBytes = when (val dl = sync.downloadSaveContent(origin, serverSave.saveId, deviceId, null)) {
            is SaveDownloadResult.Success -> dl.bytes
            is SaveDownloadResult.Failure -> return SaveConflictResolutionResult.Failure(
                "server-download-failed: ${dl.error}", dl.httpCode,
            )
        }

        // Validate hash (when the conflict recorded one) + exact known SRAM size — no fabrication.
        val actualServerHash = sha256Hex(serverBytes)
        if (replica.serverHash != null && replica.serverHash != actualServerHash) {
            return SaveConflictResolutionResult.Failure(
                "server-hash-mismatch: expected '${replica.serverHash}', got '$actualServerHash'",
            )
        }
        val expectedSize = replica.expectedSramSizeBytes
        if (expectedSize != null && serverBytes.size.toLong() != expectedSize) {
            return SaveConflictResolutionResult.Failure(
                "sram-size-mismatch: expected $expectedSize bytes, got ${serverBytes.size}",
            )
        }

        // Preserve the LOSING local copy before atomic replacement (Android conflictBackup).
        val localBackupPath = content.readLocal(replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot)
            ?.let { localBytes ->
                content.conflictBackup(
                    replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot,
                    bytes = localBytes, choice = "keep-server", contentHash = sha256Hex(localBytes),
                )
            }

        // Adopt the server copy: atomic write, re-hash, persist as SYNCED.
        content.writeLocalAtomically(replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot, serverBytes)
        val now = clock()
        upsert(replica.copy(
            localHash = actualServerHash,
            localSizeBytes = serverBytes.size.toLong(),
            localWrittenAtEpochMs = now,
            rommSaveId = serverSave.saveId,
            serverHash = replica.serverHash ?: actualServerHash,
            serverSizeBytes = serverBytes.size.toLong(),
            serverUpdatedAtEpochMs = serverSave.updatedAt?.toEpochMilli() ?: replica.serverUpdatedAtEpochMs,
            syncStatus = SaveSyncStatus.SYNCED,
            lastError = null,
        ))

        // Confirm the download (idempotent, best-effort — mirrors Android's keep-server).
        when (val confirm = sync.confirmDownload(origin, serverSave.saveId, deviceId)) {
            is SaveConfirmResult.Success -> Unit
            is SaveConfirmResult.Failure ->
                log.warning("keep-server: confirmDownload failed (non-fatal, local copy adopted): ${confirm.error}")
        }

        return SaveConflictResolutionResult.Success(
            choice = SaveConflictChoice.KEEP_SERVER,
            localBackupPath = localBackupPath,
        )
    }

    // ------------------------------------------------------------------ QUARANTINE

    /**
     * "Bad server copy preserved, never adopted": the escape hatch for incompatible-provenance
     * conflicts (both keep-choices are rejected there — see the class doc). The local bytes stay
     * on disk untouched (the save remains playable); the server copy is downloaded (no session
     * bookkeeping — it is NOT being adopted) and preserved in the quarantine dir; the replica
     * settles QUARANTINED so the drain never re-negotiates around it (blocked-replay status).
     * No upload, no adoption, no confirm. There is deliberately NO provenance gate here:
     * quarantining an unknown/different-core copy is exactly what this choice exists for.
     */
    private fun resolveQuarantine(replica: SaveReplicaRecord, origin: String, deviceId: String): SaveConflictResolutionResult {
        val serverSave = findServerSave(replica, origin, deviceId)
            ?: return SaveConflictResolutionResult.Failure(
                "server-save-unidentified: cannot quarantine — the server copy could not be identified",
            )

        val serverBytes = when (val dl = sync.downloadSaveContentBackup(origin, serverSave.saveId, deviceId)) {
            is SaveDownloadResult.Success -> dl.bytes
            is SaveDownloadResult.Failure -> return SaveConflictResolutionResult.Failure(
                "server-backup-download-failed: ${dl.error}", dl.httpCode,
            )
        }

        // Preserve the server copy in the quarantine dir (never at the real autosave path).
        val quarantinedPath = content.quarantine(
            replica.serverKey, replica.userKey, replica.romId, replica.romHash, replica.slot,
            bytes = serverBytes, reason = "conflict", nowEpochMs = clock(),
        )

        // Settle the replica QUARANTINED. Local hash/size/generation are untouched — the playable
        // local copy is exactly as found; only the sync state records the user's decision.
        upsert(replica.copy(
            syncStatus = SaveSyncStatus.QUARANTINED,
            lastError = "quarantined: conflict",
        ))

        return SaveConflictResolutionResult.Success(
            choice = SaveConflictChoice.QUARANTINE,
            quarantinedPath = quarantinedPath,
        )
    }

    // ------------------------------------------------------------------ shared helpers

    /**
     * Mirrors Android's `ConflictResolverImpl.isProvenanceCompatible`: only a server save written
     * by the SAME core as this replica may be adopted or uploaded against it. Unknown provenance
     * (null emulator) is incompatible — such conflicts are "quarantine UI only".
     */
    private fun isProvenanceCompatible(localCoreId: String, serverEmulator: String?): Boolean =
        serverEmulator != null && localCoreId == serverEmulator

    /** The server-side save for this slot, identified against the server's own listing — the
     *  authoritative provenance source for the guard. Prefers [SaveReplicaRecord.rommSaveId] when
     *  present in the listing; falls back to the newest listed save matching the slot (conflicts
     *  that predate the record). A listing failure yields null: resolution then aborts rather
     *  than acting on unknown provenance. */
    private data class ServerSave(val saveId: Long, val updatedAt: Instant?, val emulator: String?)

    private fun findServerSave(replica: SaveReplicaRecord, origin: String, deviceId: String): ServerSave? {
        return when (val result = sync.listSaves(origin, replica.romId, deviceId)) {
            is SaveListResult.Success -> {
                val candidates = result.saves.filter { it.slot == null || it.slot == replica.slot }
                val recorded = replica.rommSaveId?.let { id -> candidates.firstOrNull { it.saveId == id } }
                (recorded ?: candidates.maxByOrNull { it.updatedAt?.toEpochMilli() ?: 0L })
                    ?.let { ServerSave(it.saveId, it.updatedAt, it.emulator) }
            }
            is SaveListResult.Failure -> {
                log.warning("findServerSave: listSaves failed for rom ${replica.romId}: ${result.error}")
                null
            }
        }
    }

    /** Unwraps the store's Result so a persistence failure aborts the resolution (status is only
     *  persisted as the FINAL step of each flow — a throw here means nothing was committed). */
    private fun upsert(replica: SaveReplicaRecord) {
        val result = saveReplicas.upsert(replica)
        if (result.isFailure) {
            throw IllegalStateException(
                "upsert replica failed for scope ${replica.serverKey}/${replica.userKey}/${replica.romId}",
                result.exceptionOrNull(),
            )
        }
    }

    private companion object {
        val log: Logger = Logger.getLogger("SaveConflictResolver")
    }
}

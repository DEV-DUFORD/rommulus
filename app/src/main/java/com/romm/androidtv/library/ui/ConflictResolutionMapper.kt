package com.romm.androidtv.library.ui

import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveReplicaEntity
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Non-suspending presentation action interface for [SaveConflictScreen].
 *
 * Contract:
 * - No method is called before the user makes an explicit selection.
 * - [cancel] changes nothing on disk or in Room; it merely dismisses the screen.
 * - [keepLocal] and [keepServer] may report intent only to a state owner/resolver.
 *   A concrete resolver **MUST** durably preserve/back up the losing copy before
 *   replacing the winning copy on disk or in Room. This interface does not perform
 *   any filesystem or network operations itself; that responsibility belongs to the
 *   production wiring layer (p5-wiring milestone).
 */
interface ConflictPresentationAction {
    /** User explicitly chose to keep the local copy. */
    fun keepLocal()

    /** User explicitly chose to keep the server copy. */
    fun keepServer()

    /** User dismissed without choosing; no data is modified. */
    fun cancel()
}

/**
 * Non-suspending presentation action interface for [SaveQuarantineScreen].
 *
 * Contract:
 * - No method is called before the user makes an explicit selection.
 * - [dismiss] changes nothing on disk or in Room; it merely dismisses the screen.
 *   The quarantined file remains preserved at its quarantine path.
 */
interface QuarantinePresentationAction {
    /** User acknowledged and dismissed; no data is modified. */
    fun dismiss()
}

/**
 * Resolves a save conflict or quarantine by choosing which copy to keep.
 *
 * **Contract**: The implementer MUST back up the losing copy before replacing it on disk
 * or in Room. This screen never discards data without an explicit backup step. The backup
 * path, naming convention, and retention policy are defined by the production implementation
 * (p5-wiring milestone), not by this interface.
 *
 * [sessionId] is the sync negotiation session ID from
 * [SaveSyncOutcome.ConflictRequiresResolution][com.romm.androidtv.romm.save.SaveSyncOutcome.ConflictRequiresResolution].
 * It is required for session-aware network calls and deterministic backup naming.
 */
interface ConflictResolutionAction {
    /**
     * User chose to keep the local copy and discard the server copy.
     * [localEntity] is the current local replica metadata; [serverOperation] describes
     * the server side of the conflict; [sessionId] is the original negotiation session.
     */
    suspend fun resolveKeepLocal(
        sessionId: Long,
        localEntity: SaveReplicaEntity,
        serverOperation: SyncOperation,
    )

    /**
     * User chose to keep the server copy and discard the local copy.
     * [localEntity] is the current local replica metadata; [serverOperation] describes
     * the server side of the conflict; [sessionId] is the original negotiation session.
     */
    suspend fun resolveKeepServer(
        sessionId: Long,
        localEntity: SaveReplicaEntity,
        serverOperation: SyncOperation,
    )

    /**
     * User acknowledged quarantine and dismissed the screen. No data is modified.
     * The quarantined file remains on disk at [quarantinedPath].
     */
    fun acknowledgeQuarantine(quarantinedPath: String)
}

/**
 * Maps domain models ([SaveReplicaEntity], [SyncOperation]) into pure-UI models
 * ([SaveConflictUiModel], [SaveQuarantineUiModel]) suitable for Compose rendering.
 *
 * This class is deliberately stateless and side-effect-free so it can be unit-tested
 * without Android dependencies, Room, or coroutines.
 */
object ConflictResolutionMapper {

    private val utcFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneOffset.UTC)

    /**
     * Maps a genuine conflict outcome into [SaveConflictUiModel].
     *
     * @param localEntity  The locally-authored save replica metadata. May have null server fields.
     * @param serverOp     The server-side [SyncOperation] from negotiation (action == CONFLICT).
     */
    fun mapConflict(
        localEntity: SaveReplicaEntity,
        serverOp: SyncOperation,
    ): SaveConflictUiModel {
        val localSide = SaveConflictSide(
            label = "Local",
            saveId = localEntity.rommSaveId,
            fileName = resolveFileName(localEntity),
            hashPrefix = localEntity.localHash?.take(12),
            sizeText = formatSize(localEntity.localSizeBytes),
            coreId = localEntity.coreId,
            slot = localEntity.slot,
            romId = localEntity.romId,
            updatedAtText = formatInstant(localEntity.localWrittenAtEpochMs),
        )

        val serverSide = SaveConflictSide(
            label = "Server",
            saveId = serverOp.saveId,
            fileName = serverOp.fileName,
            hashPrefix = serverOp.serverContentHash?.take(12),
            sizeText = "Not reported", // SyncOperation does not carry server_size_bytes in the canonical response fixture
            coreId = serverOp.emulator,
            slot = serverOp.slot,
            romId = serverOp.romId,
            updatedAtText = formatInstant(serverOp.serverUpdatedAt),
        )

        return SaveConflictUiModel(
            description = serverOp.reason.ifBlank { "Both the local and server copies have changed since last sync." },
            local = localSide,
            server = serverSide,
        )
    }

    /**
     * Maps a quarantined outcome into [SaveQuarantineUiModel].
     */
    fun mapQuarantine(
        reason: String,
        quarantinedPath: String,
        localEntity: SaveReplicaEntity? = null,
    ): SaveQuarantineUiModel {
        val description = when (reason) {
            "size-mismatch" ->
                "The downloaded save file does not match the expected SRAM size for this core. It may belong to a different emulator or ROM revision."
            "unknown-provenance" ->
                "The downloaded save has no recognized core provenance metadata. It cannot be safely adopted without manual verification."
            else ->
                "This save was quarantined ($reason) and cannot be auto-adopted. A separate compatibility or import decision is required."
        }

        val quarantinedSide = if (localEntity != null) {
            SaveConflictSide(
                label = "Quarantined",
                saveId = localEntity.rommSaveId,
                fileName = resolveFileName(localEntity),
                hashPrefix = null,
                sizeText = formatSize(localEntity.localSizeBytes),
                coreId = localEntity.coreId,
                slot = localEntity.slot,
                romId = localEntity.romId,
                updatedAtText = formatInstant(localEntity.localWrittenAtEpochMs),
            )
        } else {
            SaveConflictSide(
                label = "Quarantined",
                saveId = null,
                fileName = quarantinedPath.substringAfterLast('/'),
                hashPrefix = null,
                sizeText = null,
                coreId = null,
                slot = null,
                romId = null,
                updatedAtText = null,
            )
        }

        return SaveQuarantineUiModel(
            reason = reason,
            description = description,
            quarantined = quarantinedSide,
            quarantinedPath = quarantinedPath,
        )
    }

    // ---- Formatting helpers (pure functions, testable) ----

    private fun resolveFileName(entity: SaveReplicaEntity): String {
        // Use a deterministic fallback derived from the entity scope rather than an empty string.
        return if (entity.slot.isNotBlank()) "${entity.slot}.srm" else "save-${entity.romId}.srm"
    }

    internal fun formatSize(bytes: Long?): String? {
        if (bytes == null || bytes <= 0) return null
        return when {
            bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
            bytes >= 1024 -> "${bytes / 1024} KB"
            else -> "$bytes B"
        }
    }

    internal fun formatInstant(epochMs: Long?): String? {
        if (epochMs == null || epochMs <= 0) return null
        return try {
            val instant = Instant.ofEpochMilli(epochMs)
            "${utcFormatter.format(instant)} UTC"
        } catch (_: Exception) {
            null
        }
    }

    internal fun formatInstant(instant: Instant?): String? {
        if (instant == null) return null
        return try {
            "${utcFormatter.format(instant)} UTC"
        } catch (_: Exception) {
            null
        }
    }

}

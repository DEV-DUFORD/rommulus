package com.romm.androidtv.romm.save

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Where one [SaveReplicaEntity] currently stands relative to the RomM server
 * (LIBRETRO_REFACTOR.md section 11.1/11.3). This is the *local* record's
 * status, not an upload-queue job's status (that is
 * [PendingOperation][com.romm.androidtv.romm.save.PendingOperationEntity]'s
 * concern once Milestone 5 adds it — see HANDOFF.md's `p5-room-pending`
 * todo).
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

    /**
     * The server negotiated a `conflict` outcome (section 11.3): automatic
     * replacement is stopped until the user makes an explicit local/server
     * choice. Never resolved from wall-clock timestamps alone.
     */
    CONFLICT,

    /**
     * A downloaded save has unknown/incompatible provenance (section 11.1's
     * "legacy save" case, or an incompatible-core/size mismatch). Preserved
     * on disk but never auto-adopted and never `/downloaded`-confirmed until
     * an explicit user import.
     */
    QUARANTINED,

    /**
     * The server's save was downloaded with validated provenance but unknown
     * SRAM size. The candidate bytes are quarantined; the core must load
     * first so JNI can report expectedSramSizeBytes for exact-match validation.
     * This is distinct from [PENDING_DOWNLOAD] (which implies size is known)
     * and [QUARANTINED] (which implies permanent rejection).
     */
    AWAITING_CORE_VALIDATION,
}

/**
 * One durable local save-replica record: everything this app needs to know
 * about a single server+user+ROM+slot's autosave SRAM without re-deriving it
 * from the filesystem or a network round trip (LIBRETRO_REFACTOR.md section
 * 11.1, "Local records need at least").
 *
 * Deliberately keyed the same way as
 * [SavePathPolicy][com.romm.androidtv.emulation.model.SavePathPolicy]'s
 * on-disk layout ([serverKey]/[userKey]/[romId]/[romHash]/[slot]) so a
 * record and its backing file can never drift apart under a rename or
 * re-upload: a different [romHash] is structurally a different row here,
 * exactly as it is structurally a different directory on disk.
 */
@Entity(
    tableName = "save_replicas",
    indices = [
        Index(
            value = ["serverKey", "userKey", "romId", "romHash", "slot"],
            unique = true,
        ),
    ],
)
data class SaveReplicaEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Server profile and authenticated user scope.
    val serverKey: String,
    val userKey: String,

    // ROM ID and verified ROM hash.
    val romId: Long,
    val romHash: String,

    // Slot (SavePathPolicy.AUTOSAVE_SLOT is the only supported value today).
    val slot: String,

    // Producer/backend ID, core ID, exact core build revision, and expected SRAM size.
    val coreId: String,
    val coreBuildRevision: String,
    val expectedSramSizeBytes: Long? = null,

    // Local hash, size, and durable-write time. Null until the first successful checkpoint.
    val localHash: String? = null,
    val localSizeBytes: Long? = null,
    val localWrittenAtEpochMs: Long? = null,

    // RomM save ID and last-known server metadata. Null until a server round trip succeeds.
    val rommSaveId: Long? = null,
    val serverHash: String? = null,
    val serverSizeBytes: Long? = null,
    val serverUpdatedAtEpochMs: Long? = null,

    // Sync status and last error.
    val syncStatus: SaveSyncStatus = SaveSyncStatus.UNSYNCED,
    val lastError: String? = null,
) {
    init {
        require(serverKey.isNotBlank()) { "serverKey must not be blank" }
        require(userKey.isNotBlank()) { "userKey must not be blank" }
        require(romId > 0) { "romId must be a positive RomM ROM ID" }
        require(romHash.isNotBlank()) { "romHash must not be blank" }
        require(slot.isNotBlank()) { "slot must not be blank" }
        require(coreId.isNotBlank()) { "coreId must not be blank" }
        require(coreBuildRevision.isNotBlank()) { "coreBuildRevision must not be blank" }
        expectedSramSizeBytes?.let { require(it >= 0) { "expectedSramSizeBytes must not be negative" } }
    }
}

package com.romm.androidtv.romm.save

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * The kind of durable work a [PendingOperationEntity] represents. Only
 * [UPLOAD] exists today (LIBRETRO_REFACTOR.md section 11.4's "upload
 * queue"), but the type is factored out now rather than hard-coding "this
 * queue only ever uploads" into the schema/DAO, since section 11.5 (save
 * states) will need the same durable, resumable, WorkManager-backed queuing
 * discipline for state uploads later.
 */
enum class PendingOperationType {
    UPLOAD,
}

/**
 * The state-machine status of one [PendingOperationEntity], exactly mirroring
 * the diagram in LIBRETRO_REFACTOR.md section 11.4:
 *
 * ```text
 * PENDING -> RUNNING -> SUCCEEDED
 *                     -> RETRYABLE_FAILURE -> PENDING
 *                     -> AUTH_REQUIRED
 *                     -> CONFLICT
 *                     -> PERMANENT_FAILURE
 * ```
 *
 * See [PendingOperationTransitions] for the validated transition table.
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

/**
 * A pure, Android-free validator for [PendingOperationStatus] transitions
 * (LIBRETRO_REFACTOR.md section 11.4). Kept separate from the Room entity so
 * it is testable as an ordinary JUnit5 unit test — unlike the Room
 * entity/DAO themselves, which need a real SQLite implementation and can
 * only be exercised from `app/src/androidTest` in this repo (no Robolectric
 * dependency; see HANDOFF.md's Session 8 sitrep).
 *
 * [SUCCEEDED], [AUTH_REQUIRED], [CONFLICT], and [PERMANENT_FAILURE] are
 * terminal here: none of them have an automatic next state in section
 * 11.4's diagram. Resuming after `AUTH_REQUIRED` (re-authentication) or
 * `CONFLICT` (explicit user choice) means creating a *new*
 * [PendingOperationEntity] row, not mutating a terminal one in place — this
 * keeps the history of what actually happened intact for observability.
 */
object PendingOperationTransitions {

    private val allowed: Map<PendingOperationStatus, Set<PendingOperationStatus>> = mapOf(
        PendingOperationStatus.PENDING to setOf(PendingOperationStatus.RUNNING),
        PendingOperationStatus.RUNNING to setOf(
            PendingOperationStatus.SUCCEEDED,
            PendingOperationStatus.RETRYABLE_FAILURE,
            PendingOperationStatus.AUTH_REQUIRED,
            PendingOperationStatus.CONFLICT,
            PendingOperationStatus.PERMANENT_FAILURE,
        ),
        PendingOperationStatus.RETRYABLE_FAILURE to setOf(PendingOperationStatus.PENDING),
        PendingOperationStatus.SUCCEEDED to emptySet(),
        PendingOperationStatus.AUTH_REQUIRED to emptySet(),
        PendingOperationStatus.CONFLICT to emptySet(),
        PendingOperationStatus.PERMANENT_FAILURE to emptySet(),
    )

    /** Whether [from] -> [to] is a legal transition per section 11.4's state diagram. */
    fun isValidTransition(from: PendingOperationStatus, to: PendingOperationStatus): Boolean =
        to in allowed.getValue(from)

    /** True if no further automatic transition out of [status] exists. */
    fun isTerminal(status: PendingOperationStatus): Boolean = allowed.getValue(status).isEmpty()
}

/**
 * One durable, resumable unit of queued work — currently always an
 * upload — for the section 11.4 "upload queue". Backed by WorkManager
 * (Milestone 7, `p5-workmanager`) for actual execution/retry scheduling;
 * this row is the source of truth that survives process death, so a retry
 * always resumes from persisted state rather than in-memory job state.
 *
 * Deliberately scoped identically to [SaveReplicaEntity] (server/user/ROM/
 * hash/slot) so the two can always be joined by that tuple, and dedupe rule
 * "preserving the newest durable local generation" (section 11.4) is
 * enforced structurally: [localWrittenAtEpochMs] must match the
 * [SaveReplicaEntity.localWrittenAtEpochMs] this operation is uploading, and
 * callers creating a new pending operation for the same scope should replace
 * (not stack) any existing non-terminal one for the same scope + type — see
 * [PendingOperationDao.findActiveByScope].
 */
@Entity(
    tableName = "pending_operations",
    indices = [
        Index(value = ["serverKey", "userKey", "romId", "romHash", "slot", "operationType"]),
    ],
)
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    // Scope — matches SaveReplicaEntity's key exactly so the two can be joined.
    val serverKey: String,
    val userKey: String,
    val romId: Long,
    val romHash: String,
    val slot: String,

    val operationType: PendingOperationType,

    /**
     * The durable local write ([SaveReplicaEntity.localWrittenAtEpochMs])
     * this operation is uploading. Used to detect and drop a now-stale
     * queued operation if a newer local write superseded it before this one
     * ran (section 11.4: "preserving the newest durable local generation").
     */
    val localGenerationEpochMs: Long,

    val status: PendingOperationStatus = PendingOperationStatus.PENDING,

    /** Number of times this operation has entered [PendingOperationStatus.RUNNING]. */
    val attemptCount: Int = 0,

    val lastError: String? = null,
    val lastHttpCode: Int? = null,

    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    init {
        require(serverKey.isNotBlank()) { "serverKey must not be blank" }
        require(userKey.isNotBlank()) { "userKey must not be blank" }
        require(romId > 0) { "romId must be a positive RomM ROM ID" }
        require(romHash.isNotBlank()) { "romHash must not be blank" }
        require(slot.isNotBlank()) { "slot must not be blank" }
        require(attemptCount >= 0) { "attemptCount must not be negative" }
    }
}

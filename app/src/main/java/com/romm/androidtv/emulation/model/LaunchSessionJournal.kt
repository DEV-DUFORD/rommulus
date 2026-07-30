package com.romm.androidtv.emulation.model

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.FileOutputStream
/**
 * Atomic, file-backed launch-session journal (LIBRETRO_REFACTOR.md section 6).
 *
 * Lives outside the cache directory so it survives cache eviction. Keyed by
 * [LaunchSpec.sessionId]. Each descriptor is a single JSON file written with
 * temp-write / fsync / atomic-rename discipline, so process death mid-write
 * never produces a torn record.
 *
 * States are honest and idempotent:
 * - [DescriptorState.LAUNCHED] — main process created the intent; EmulationActivity started.
 * - [DescriptorState.CORE_LOADED] — core loaded successfully; candidate validation may proceed.
 * - [DescriptorState.ADOPTED] — candidate SRAM was restored, checkpointed, and adopted durably.
 * - [DescriptorState.REJECTED] — size mismatch or other validation failure; candidate preserved.
 * - [DescriptorState.CRASHED] — process death or native crash before adoption/rejection.
 *
 * Invalid transitions are rejected (e.g. ADOPTED → CORE_LOADED). Recovery on
 * next main-process resume replays the last known state idempotently.
 */
@OptIn(kotlin.ExperimentalStdlibApi::class)
class LaunchSessionJournal(private val journalDir: File) {

    private val moshi = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
    private val descriptorAdapter = moshi.adapter<SessionDescriptor>()

    init {
        journalDir.mkdirs()
    }

    /**
     * Creates or returns an existing descriptor for [sessionId].
     * Idempotent: calling twice with the same sessionId when already in a terminal
     * state returns the existing record without mutation.
     */
    fun createOrGet(sessionId: String): SessionDescriptor {
        require(sessionId.isNotBlank()) { "sessionId must not be blank" }
        val file = descriptorFile(sessionId)
        val existing = read(file)
        if (existing != null) return existing
        val newDesc = SessionDescriptor(
            sessionId = sessionId,
            state = DescriptorState.LAUNCHED,
            candidatePath = null,
            candidateDownloadedSizeBytes = null,
            canonicalSavePath = null,
            checkpointedHash = null,
            errorDetail = null,
        )
        write(file, newDesc)
        return newDesc
    }

    /**
     * Patches identity fields on the descriptor for [sessionId] without advancing state.
     * Used to persist authoritative ROM/core metadata early (before core load) so that
     * process-death recovery has exact values rather than fabricated defaults.
     */
    fun patchIdentity(sessionId: String, patch: SessionDescriptorPatch): SessionDescriptor {
        val file = descriptorFile(sessionId)
        val current = read(file)
            ?: throw IllegalStateException("No descriptor for session $sessionId — call createOrGet() first")

        val updated = SessionDescriptor(
            sessionId = current.sessionId,
            state = current.state,
            candidatePath = patch.candidatePath ?: current.candidatePath,
            candidateDownloadedSizeBytes = patch.candidateDownloadedSizeBytes ?: current.candidateDownloadedSizeBytes,
            rommSaveId = patch.rommSaveId ?: current.rommSaveId,
            canonicalSavePath = patch.canonicalSavePath ?: current.canonicalSavePath,
            checkpointedHash = patch.checkpointedHash ?: current.checkpointedHash,
            errorDetail = patch.errorDetail ?: current.errorDetail,
            rommSessionId = patch.rommSessionId ?: current.rommSessionId,
            romId = patch.romId ?: current.romId,
            romHash = patch.romHash ?: current.romHash,
            coreId = patch.coreId ?: current.coreId,
            coreBuildRevision = patch.coreBuildRevision ?: current.coreBuildRevision,
            canonicalFileName = patch.canonicalFileName ?: current.canonicalFileName,
            expectedSramSizeBytes = patch.expectedSramSizeBytes ?: current.expectedSramSizeBytes,
            serverContentHash = patch.serverContentHash ?: current.serverContentHash,
        )
        write(file, updated)
        return updated
    }

    /**
     * Advances the descriptor for [sessionId] to the next valid state.
     * Returns the updated descriptor or throws if the transition is invalid.
     */
    fun advance(sessionId: String, nextState: DescriptorState, patch: SessionDescriptorPatch): SessionDescriptor {
        val file = descriptorFile(sessionId)
        val current = read(file)
            ?: throw IllegalStateException("No descriptor for session $sessionId — call createOrGet() first")

        if (!current.state.isValidTransition(nextState)) {
            throw IllegalArgumentException(
                "Invalid transition ${current.state} -> $nextState for session $sessionId"
            )
        }

        val updated = SessionDescriptor(
            sessionId = current.sessionId,
            state = nextState,
            candidatePath = patch.candidatePath ?: current.candidatePath,
            candidateDownloadedSizeBytes = patch.candidateDownloadedSizeBytes ?: current.candidateDownloadedSizeBytes,
            rommSaveId = patch.rommSaveId ?: current.rommSaveId,
            canonicalSavePath = patch.canonicalSavePath ?: current.canonicalSavePath,
            checkpointedHash = patch.checkpointedHash ?: current.checkpointedHash,
            errorDetail = patch.errorDetail ?: current.errorDetail,
            rommSessionId = patch.rommSessionId ?: current.rommSessionId,
            romId = patch.romId ?: current.romId,
            romHash = patch.romHash ?: current.romHash,
            coreId = patch.coreId ?: current.coreId,
            coreBuildRevision = patch.coreBuildRevision ?: current.coreBuildRevision,
            canonicalFileName = patch.canonicalFileName ?: current.canonicalFileName,
            expectedSramSizeBytes = patch.expectedSramSizeBytes ?: current.expectedSramSizeBytes,
            serverContentHash = patch.serverContentHash ?: current.serverContentHash,
        )
        write(file, updated)
        return updated
    }

    /**
     * Reads the descriptor for [sessionId], or null if none exists.
     * Used by recovery logic to replay results after process death.
     */
    fun read(sessionId: String): SessionDescriptor? = read(descriptorFile(sessionId))

    /**
     * Deletes the descriptor file. Terminal cleanup after the result has been
     * consumed by the main process finalizer.
     */
    fun remove(sessionId: String): Boolean {
        val file = descriptorFile(sessionId)
        return if (file.exists()) file.delete() else false
    }

    /**
     * Lists all sessionIds with non-terminal descriptors (i.e. sessions that
     * may need recovery on next main-process resume).
     */
    fun listPending(): List<SessionDescriptor> {
        if (!journalDir.isDirectory) return emptyList()
        return journalDir.listFiles()?.filter { it.extension == "json" }?.mapNotNull { file ->
            read(file)
        }?.filter { !it.state.isTerminal } ?: emptyList()
    }

    private fun descriptorFile(sessionId: String): File =
        File(journalDir, "${sanitizeSessionId(sessionId)}.json")

    private fun sanitizeSessionId(id: String): String =
        id.replace(Regex("""[^a-zA-Z0-9._-]"""), "_")

    /**
     * Atomically writes [desc] to [file] using temp-write / fsync / atomic-rename.
     *
     * **Durability boundary**: On Android/Linux, `File.mkdirs()` does not guarantee the
     * parent directory's metadata is synced to persistent storage before we write into it.
     * If the device loses power between `mkdirs()` and `fsync()`, the journal file may be
     * lost on reboot even though the rename succeeded. There is no safe Android API that
     * syncs a parent directory's metadata (`FileOutputStream(directory).fd.sync()` is not
     * supported — Android throws `IOException: Is a directory`). Recovery handles this by
     * re-creating missing descriptors from the main process's own state on next launch.
     */
    private fun write(file: File, desc: SessionDescriptor) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, "${file.name}.tmp")
        try {
            FileOutputStream(temp).use { fos ->
                val json = toJson(desc)
                fos.write(json.toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
            if (!temp.renameTo(file)) {
                // Fallback: copy + delete (cross-filesystem rename failure).
                temp.inputStream().use { inputStream ->
                    FileOutputStream(file).use { fos ->
                        inputStream.copyTo(fos)
                        fos.flush()
                        fos.fd.sync()
                    }
                }
                temp.delete()
            }
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    private fun read(file: File): SessionDescriptor? {
        if (!file.isFile) return null
        val contents = try { file.readText() } catch (_: Exception) { return null }
        return try {
            fromJson(contents)
        } catch (_: Exception) {
            // Corrupted file — treat as missing. Recovery will re-create.
            null
        }
    }

    // ---- Simple JSON serialization (no external dependency) ----

    // ---- Moshi-based JSON serialization (replaces hand-built parser) ----

    private fun toJson(d: SessionDescriptor): String = descriptorAdapter.toJson(d)

    private fun fromJson(json: String): SessionDescriptor = descriptorAdapter.fromJson(json)!!
}

/**
 * Honest, ordered states for a launch session descriptor.
 * Transitions are strictly unidirectional; terminal states cannot advance.
 */
enum class DescriptorState {
    LAUNCHED,
    CORE_LOADED,
    ADOPTED,
    REJECTED,
    CRASHED;

    /** Terminal for state-machine advancement: no further transitions allowed. */
    val isTerminal: Boolean get() = this == ADOPTED || this == REJECTED || this == CRASHED

    /** Cleanup-only: can be safely removed on recovery without replaying any work. */
    val isCleanupOnly: Boolean get() = this == REJECTED || this == CRASHED

    fun isValidTransition(next: DescriptorState): Boolean = when (this) {
        LAUNCHED -> next == CORE_LOADED || next == REJECTED || next == CRASHED
        CORE_LOADED -> next == ADOPTED || next == REJECTED || next == CRASHED
        ADOPTED, REJECTED, CRASHED -> false
    }
}

/**
 * Immutable snapshot of a single launch session's descriptor.
 */
data class SessionDescriptor(
    val sessionId: String,
    val state: DescriptorState,
    /** App-private path to the candidate (quarantined) SRAM bytes from AwaitingCoreValidation. Null if no candidate. */
    val candidatePath: String?,
    /** The downloaded size of the candidate bytes. Used for exact-match validation against nativeGetSramSizeBytes. */
    val candidateDownloadedSizeBytes: Long?,
    /** RomM save ID for the downloaded candidate. Stored for post-death recovery of finalization. Null if no candidate. */
    val rommSaveId: Long? = null,
    /** App-private canonical autosave path where SRAM is normally persisted. */
    val canonicalSavePath: String?,
    /** SHA-256 hex of the checkpointed SRAM after successful adoption. Null until adopted. */
    val checkpointedHash: String?,
    /** Human-readable error detail for REJECTED/CRASHED states. */
    val errorDetail: String?,
    /** RomM sync session ID (distinct from app launch sessionId). Persisted for post-death finalization. Null if no candidate. */
    val rommSessionId: Long? = null,
    /** Exact ROM ID from the LaunchSpec. Persisted for post-death recovery identity validation. */
    val romId: Long? = null,
    /** Verified content hash of the staged ROM file. Persisted for post-death recovery. */
    val romHash: String? = null,
    /** Core ID from authoritative CoreManifest entry. Persisted for post-death recovery. */
    val coreId: String? = null,
    /** Exact core build revision from authoritative CoreManifest entry. Persisted for post-death recovery. */
    val coreBuildRevision: String? = null,
    /** Canonical file name from real staged/scope data. Persisted for post-death recovery. */
    val canonicalFileName: String? = null,
    /** JNI-learned expected SRAM size in bytes. Persisted during finalizeAdoption for future sync decisions. */
    val expectedSramSizeBytes: Long? = null,
    /** Server-reported content hash from the original candidate download. Persisted for ADOPTED recovery finalization. Null if not reported. */
    val serverContentHash: String? = null,
)

/**
 * Immutable patch used with [LaunchSessionJournal.advance]. Only non-null fields override.
 */
data class SessionDescriptorPatch(
    val candidatePath: String? = null,
    val candidateDownloadedSizeBytes: Long? = null,
    val rommSaveId: Long? = null,
    val canonicalSavePath: String? = null,
    val checkpointedHash: String? = null,
    val errorDetail: String? = null,
    val rommSessionId: Long? = null,
    val romId: Long? = null,
    val romHash: String? = null,
    val coreId: String? = null,
    val coreBuildRevision: String? = null,
    val canonicalFileName: String? = null,
    val expectedSramSizeBytes: Long? = null,
    val serverContentHash: String? = null,
)

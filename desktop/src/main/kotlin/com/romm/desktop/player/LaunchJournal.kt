package com.romm.desktop.player

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.WRITE

/**
 * Atomic file writes for the player journal machinery: temp file in the SAME directory as
 * the target (O_CREAT|O_EXCL via `Files.createTempFile`) + full write + fsync, then an
 * atomic rename into place. Same pattern as `JsonSettingsStore`/`AtomicSettingsCodec`
 * (Phase 5): a reader can never observe a partially written file — the target is either the
 * old content or the new content, never a torn write. On failure the temp file is removed
 * and the previous target is left intact.
 */
internal object AtomicFileIo {

    /** 0600: journal/request files are user-only (plans/LINUX_X64.md §9; §12.4 step 2). */
    val FILE_USER_ONLY: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
    )

    /** 0700: session directories are user-only (plans/LINUX_X64.md §9). */
    val DIR_USER_ONLY: Set<PosixFilePermission> = setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

    fun writeAtomically(target: Path, bytes: ByteArray, permissions: Set<PosixFilePermission>? = null) {
        val dir = checkNotNull(target.parent) { "target has no parent directory: $target" }
        val temp = Files.createTempFile(dir, ".tmp-", target.fileName.toString())
        try {
            FileChannel.open(temp, WRITE).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true) // fsync BEFORE the rename so the content is durable
            }
            if (permissions != null) setPosixPermissionsQuietly(temp, permissions)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    fun setPosixPermissionsQuietly(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // Non-POSIX filesystems (e.g. Windows): permissions are not enforced there.
        }
    }

    /** Creates [dir] (and parents) with 0700 when absent. */
    fun ensureUserOnlyDirectory(dir: Path) {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir)
            setPosixPermissionsQuietly(dir, DIR_USER_ONLY)
        }
    }
}

/** Lifecycle states of a launch journal (plans/LINUX_X64.md §12.5). */
enum class JournalState {
    /** Request + journal committed and the player spawned. Awaiting result reconciliation. */
    PENDING,

    /**
     * The launch did not complete cleanly: the player died without a valid result (or the
     * spawn failed). Files are preserved for forensics and the diagnostic is re-surfaced at
     * startup. INTERRUPTED means "no launch completed, none will be retried" — it is NOT a
     * barrier to reconciliation: if a strictly valid result for this session appears later
     * (e.g., written just after the scan that marked it INTERRUPTED), [LaunchJournalSupervisor.reconcile]
     * still reconciles and adopts it. The state suppresses launch retries, never the recovery
     * of a valid result.
     */
    INTERRUPTED,

    /**
     * Result reconciliation committed — the adoption decision is final. The journal exists
     * only transiently between this write and the cleanup deletion (a crash in that window
     * is recovered idempotently at the next startup).
     */
    RECONCILED,
}

/** In-memory launch journal record. */
data class LaunchJournal(
    val sessionId: String,
    val requestPath: Path,
    val resultPath: Path,
    val candidateSavePath: Path,
    val state: JournalState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun withState(state: JournalState, nowEpochMs: Long): LaunchJournal =
        copy(state = state, updatedAtEpochMs = nowEpochMs)
}

/** JSON representation on disk (Moshi). Paths are stored as absolute strings. */
@JsonClass(generateAdapter = false)
internal data class LaunchJournalFile(
    val schemaVersion: Int = JOURNAL_SCHEMA_VERSION,
    val sessionId: String,
    val requestPath: String,
    val resultPath: String,
    val candidateSavePath: String,
    val state: JournalState,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
) {
    fun toJournal(): LaunchJournal = LaunchJournal(
        sessionId = sessionId,
        requestPath = Path.of(requestPath),
        resultPath = Path.of(resultPath),
        candidateSavePath = Path.of(candidateSavePath),
        state = state,
        createdAtEpochMs = createdAtEpochMs,
        updatedAtEpochMs = updatedAtEpochMs,
    )

    companion object {
        fun from(journal: LaunchJournal): LaunchJournalFile = LaunchJournalFile(
            schemaVersion = JOURNAL_SCHEMA_VERSION,
            sessionId = journal.sessionId,
            requestPath = journal.requestPath.toString(),
            resultPath = journal.resultPath.toString(),
            candidateSavePath = journal.candidateSavePath.toString(),
            state = journal.state,
            createdAtEpochMs = journal.createdAtEpochMs,
            updatedAtEpochMs = journal.updatedAtEpochMs,
        )
    }
}

internal const val JOURNAL_SCHEMA_VERSION: Int = 1

/**
 * Persistence for launch journals under `stateDir()/journals/<sessionId>/`
 * (plans/LINUX_X64.md §9). Per-session layout:
 *
 * ```
 * journals/<sessionId>/journal.json    — this store's record (0600, atomic writes)
 * journals/<sessionId>/request.json    — v1 launch request written by the supervisor
 * journals/<sessionId>/candidate.srm   — save candidate written by the player
 * journals/<sessionId>/result.json     — v1 result written by the player
 * journals/<sessionId>/player.log      — bounded player stdout+stderr capture (0600, rotated to player.log.1)
 * ```
 *
 * Writes are atomic (temp + fsync + rename, see [AtomicFileIo]) and journal files are 0600.
 * Malformed journal files are never deleted or overwritten: [read] surfaces the failure and
 * the supervisor preserves the file and surfaces a recovery diagnostic (§12.5).
 *
 * A reconciled session's `player.log` (+ rotation) is the one retained artifact: it is kept
 * for on-device diagnostics and pruned by [LaunchJournalSupervisor] to the newest
 * [LaunchJournalSupervisor.RETAINED_PLAYER_LOG_SESSIONS] sessions; every other artifact is
 * deleted with the journal.
 */
class LaunchJournalStore(private val journalsRoot: Path) {

    private val moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val journalAdapter = moshi.adapter(LaunchJournalFile::class.java)

    fun sessionDir(sessionId: String): Path {
        SecureFiles.requireSessionId(sessionId).getOrThrow()
        return journalsRoot.resolve(sessionId)
    }

    /** Creates the session directory (0700) when absent. */
    fun ensureSessionDir(sessionId: String): Path {
        val dir = sessionDir(sessionId)
        AtomicFileIo.ensureUserOnlyDirectory(dir)
        return dir
    }

    fun journalPath(sessionId: String): Path = sessionDir(sessionId).resolve(JOURNAL_FILE_NAME)

    fun requestPath(sessionId: String): Path = sessionDir(sessionId).resolve(REQUEST_FILE_NAME)

    fun resultPath(sessionId: String): Path = sessionDir(sessionId).resolve(RESULT_FILE_NAME)

    fun candidatePath(sessionId: String): Path = sessionDir(sessionId).resolve(CANDIDATE_FILE_NAME)

    /** Bounded player stdout+stderr capture ([PlayerLogCapture], 0600). */
    fun playerLogPath(sessionId: String): Path = sessionDir(sessionId).resolve(PLAYER_LOG_FILE_NAME)

    /** Rotation slot for the player log (older content, replaced on each rotation). */
    fun playerLogRotationPath(sessionId: String): Path =
        sessionDir(sessionId).resolve(PLAYER_LOG_FILE_NAME + PlayerLogCapture.ROTATION_SUFFIX)

    /** Atomically writes [journal]'s record file (0600); creates the session directory (0700) when absent. */
    fun write(journal: LaunchJournal) {
        SecureFiles.requireSessionId(journal.sessionId).getOrThrow()
        val target = journalPath(journal.sessionId)
        AtomicFileIo.ensureUserOnlyDirectory(checkNotNull(target.parent))
        val json = journalAdapter.toJson(LaunchJournalFile.from(journal))
        AtomicFileIo.writeAtomically(
            target,
            json.toByteArray(StandardCharsets.UTF_8),
            AtomicFileIo.FILE_USER_ONLY,
        )
    }

    /**
     * Reads the journal for [sessionId]. Absent file → [Result.success] with null.
     * Malformed/unreadable → [Result.failure]; the file is preserved untouched (fail-closed).
     */
    fun read(sessionId: String): Result<LaunchJournal?> = runCatching {
        val path = journalPath(sessionId) // throws for unsafe session IDs → Result.failure
        if (!Files.exists(path)) return@runCatching null
        SecureFiles.resolveExistingRegular(path).getOrThrow()
        val text = Files.readString(path, StandardCharsets.UTF_8)
        val file = journalAdapter.fromJson(text) ?: throw IOException("journal is empty: $path")
        if (file.schemaVersion != JOURNAL_SCHEMA_VERSION) {
            throw IOException("unsupported journal schemaVersion: ${file.schemaVersion}")
        }
        if (file.sessionId != sessionId) {
            throw IOException("journal sessionId mismatch: file=${file.sessionId} dir=$sessionId")
        }
        file.toJournal()
    }

    /** Atomically writes the v1 request JSON for [sessionId] (0600); creates the session directory when absent. */
    fun writeRequest(sessionId: String, json: String) {
        val target = requestPath(sessionId)
        AtomicFileIo.ensureUserOnlyDirectory(checkNotNull(target.parent))
        AtomicFileIo.writeAtomically(
            target,
            json.toByteArray(StandardCharsets.UTF_8),
            AtomicFileIo.FILE_USER_ONLY,
        )
    }

    /** Session IDs on disk (directory names that pass [SecureFiles.requireSessionId]). */
    fun listSessionIds(): List<String> {
        if (!Files.isDirectory(journalsRoot)) return emptyList()
        return try {
            val names = Files.list(journalsRoot).use { stream ->
                stream.filter { Files.isDirectory(it) && !Files.isSymbolicLink(it) }
                    .map { it.fileName.toString() }
                    .toList()
            }
            names.filter { SecureFiles.requireSessionId(it).isSuccess }.sorted()
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        const val JOURNAL_FILE_NAME = "journal.json"
        const val REQUEST_FILE_NAME = "request.json"
        const val RESULT_FILE_NAME = "result.json"
        const val CANDIDATE_FILE_NAME = "candidate.srm"
        const val PLAYER_LOG_FILE_NAME = "player.log"
    }
}

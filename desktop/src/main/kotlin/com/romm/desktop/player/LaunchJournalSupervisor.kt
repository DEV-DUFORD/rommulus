package com.romm.desktop.player

import com.romm.androidtv.storage.AppPaths
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption.READ
import java.nio.file.StandardOpenOption.WRITE
import java.util.UUID

/** Parameters for a player launch (paths as [Path]; serialized to absolute strings in the request). */
data class PlayerLaunchParams(
    val coreId: String,
    val coreBuildRevision: String,
    val corePath: Path,
    /** ROM to play. Null for no-content cores (e.g. `test_core`); serialized as `""` on the wire. */
    val contentPath: Path? = null,
    /** SHA-256 of the ROM; empty string → the player skips hash verification (§12.2). Must be blank when [contentPath] is null. */
    val contentHash: String = "",
    val systemDir: Path,
    /** Confirmed save location (under the data root). Only replaced when every adoption check passes. */
    val savePath: Path,
    val expectedSaveSize: Long? = null,
    val video: VideoSettings = VideoSettings(),
    /** v2 optional: stored controller bindings to apply from the first frame; null = player defaults. */
    val controllerBindings: ControllerBindings? = null,
    /** Optional Linux keyboard bindings to apply from the first frame. */
    val keyboardBindings: KeyboardBindings? = null,
    /** Curated per-game renderer override; null preserves the core default. */
    val rendererOverride: RendererOverride? = null,
)

/** A prepared (or recovered) player session and its on-disk artifacts. */
data class LaunchSession(
    val sessionId: String,
    val requestPath: Path,
    val resultPath: Path,
    val candidateSavePath: Path,
)

/** Outcome of [LaunchJournalSupervisor.prepareLaunch]. */
sealed interface PrepareLaunchResult {
    /** Request + journal committed and the spawn attempt made; see [launch] for its outcome. */
    data class Ready(val session: LaunchSession, val launch: LaunchOutcome) : PrepareLaunchResult

    /** The launch could not be prepared (invalid input or I/O failure). No player is running. */
    data class Failed(val reason: String, val session: LaunchSession? = null) : PrepareLaunchResult
}

/** Liveness of the player process for a session, as seen by the supervisor. */
enum class PlayerLiveness {
    /** A player for this session is running and owned by this app instance. */
    ALIVE_OWNED,
    /** No player for this session is running (or we are certain it is not ours). */
    DEAD,
    /** Liveness could not be determined — fail-closed: treated like [ALIVE_OWNED] (leave alone). */
    UNKNOWN,
}

/** Diagnostics surfaced by [LaunchJournalSupervisor.scanIncompleteJournals] (§12.5). */
data class LaunchRecoveryDiagnostic(
    val sessionId: String,
    val kind: Kind,
    val detail: String,
) {
    enum class Kind {
        /** Session directory with files but no journal.json (request committed, journal never was). Preserved. */
        ORPHAN_SESSION_FILES,
        /** journal.json exists but does not parse. Preserved. */
        MALFORMED_JOURNAL,
        /** Journal was already RECONCILED; leftover of a crash during cleanup. Removed (idempotent). */
        STALE_RECONCILED_CLEANED,
        /** Player still alive and owned: journal left untouched; no second player is started. */
        PLAYER_STILL_ALIVE,
        /** No result file and no live player: marked INTERRUPTED, candidate inspected, preserved. */
        INTERRUPTED_NO_RESULT,
        /** Result file present but malformed/untrusted. Preserved. */
        MALFORMED_RESULT,
        /** A valid unreconciled result was replayed and reconciliation committed; journal deleted. */
        REPLAY_RECONCILED,
        /** Reconciliation failed (e.g. adoption checks rejected the candidate). Files preserved. */
        RECONCILE_FAILED,
        /** Unexpected error while scanning a session. Preserved. */
        UNEXPECTED_ERROR,
    }

    val summary: String get() = "[$kind] session=$sessionId: $detail"
}

/** Snapshot of the candidate save taken at inspection time (re-hashed, never cached). */
data class CandidateInspection(
    val path: Path,
    val exists: Boolean,
    val sizeBytes: Long?,
    val sha256Hex: String?,
) {
    fun describe(): String = if (!exists) "absent" else "size=$sizeBytes sha256=$sha256Hex"
}

/** The adoption decision for a session's candidate save. */
sealed interface AdoptionDecision {
    data class Approve(val reason: String) : AdoptionDecision
    data class Reject(val reason: String) : AdoptionDecision
}

data class AdoptionSummary(
    val decision: AdoptionDecision,
    /** True when the candidate was moved into place at [targetSavePath]. */
    val adopted: Boolean,
    val targetSavePath: Path?,
)

/** Report from [LaunchJournalSupervisor.onPlayerExit]. */
sealed interface PlayerExitReport {
    /** The result was validated and reconciliation committed; the journal was deleted. */
    data class Reconciled(
        val session: LaunchSession,
        val result: PlayerResult,
        val adoption: AdoptionSummary?,
    ) : PlayerExitReport

    /** Missing/malformed/untrusted result (or crash exit): marked INTERRUPTED, files preserved (§12.3/§12.5). */
    data class CrashInterrupted(
        val session: LaunchSession,
        val reason: String,
        val candidate: CandidateInspection,
    ) : PlayerExitReport

    /** No journal for this session — nothing to reconcile. */
    data class JournalMissing(val sessionId: String) : PlayerExitReport

    /** A valid result existed but reconciliation failed; files preserved (fail-closed). */
    data class ReconcileFailed(
        val session: LaunchSession,
        val reason: String,
    ) : PlayerExitReport
}

/** Outcome of [LaunchJournalSupervisor.reconcile]. */
sealed interface ReconcileOutcome {
    /** Reconciliation committed (or confirmed already committed); session artifacts cleaned up. */
    data class Success(
        val adoption: AdoptionSummary?,
        val alreadyReconciled: Boolean = false,
    ) : ReconcileOutcome

    /** Reconciliation failed; every file is preserved for the next attempt/forensics. */
    data class Failed(val reason: String) : ReconcileOutcome
}

/**
 * Seam for the candidate-save adoption policy (§12.5): "Candidate adoption must reuse current
 * hash, ROM identity, core revision, and expected-size checks." [DefaultSaveAdoptionPolicy]
 * implements the desktop-side checks; tests (and later waves) substitute their own.
 */
fun interface SaveAdoptionPolicy {
    fun evaluate(context: AdoptionContext): AdoptionDecision
}

data class AdoptionContext(
    val journal: LaunchJournal,
    val request: PlayerRequest,
    val result: PlayerResult,
    /** Canonical (symlink-resolved) candidate path, verified to live in the session directory. */
    val candidatePath: Path,
    /** Canonical target save path (under the data root). */
    val targetSavePath: Path,
)

/**
 * Desktop-side adoption checks mirroring the player's §12.4 validation at commit time:
 *  1. identity binding — the result belongs to this session and names this session's candidate;
 *  2. verification datum — at least one of result.saveHash / result.saveSize must be present;
 *     a present candidate with neither is rejected (fail-closed symmetry with the replay path);
 *  3. hash — SHA-256 of the candidate recomputed AT OPEN TIME and compared to result.saveHash;
 *  4. expected size — candidate size must match request.expectedSaveSize and result.saveSize;
 *  5. ROM identity — when request.contentHash is pinned, the content file is re-hashed and compared;
 *  6. core revision — the request must carry a pinned coreBuildRevision (full core-metadata
 *     pinning against installed cores is enforced player-side, §12.4 step 8).
 *
 * Any failure yields [AdoptionDecision.Reject] and leaves the confirmed save at
 * [AdoptionContext.targetSavePath] untouched — "never overwrite a confirmed save only because
 * a newer file exists" (§12.5).
 */
class DefaultSaveAdoptionPolicy : SaveAdoptionPolicy {

    override fun evaluate(context: AdoptionContext): AdoptionDecision {
        val journal = context.journal
        val request = context.request
        val result = context.result

        if (result.sessionId != journal.sessionId) {
            return AdoptionDecision.Reject("result sessionId does not match the journal")
        }
        if (result.candidateSavePath != journal.candidateSavePath.toString()) {
            return AdoptionDecision.Reject("result candidateSavePath does not match the session's candidate")
        }

        // A PRESENT candidate may only be adopted when the result carries at least one
        // verification datum (hash or size) — symmetric with the fail-closed replay path:
        // without either we cannot prove the checkpoint is what the player claims, so reject
        // and let the journal be preserved (§12.5).
        if (result.saveHash == null && result.saveSize == null) {
            return AdoptionDecision.Reject("result carries no saveHash or saveSize; a present candidate cannot be adopted without verification data")
        }

        result.saveHash?.let { expected ->
            val actual = SecureFiles.sha256Hex(context.candidatePath)
            if (!actual.equals(expected.trim(), ignoreCase = true)) {
                return AdoptionDecision.Reject("candidate hash mismatch (expected=$expected actual=$actual)")
            }
        }

        val size = Files.size(context.candidatePath)
        request.expectedSaveSize?.let { expected ->
            if (size != expected) return AdoptionDecision.Reject("candidate size $size != expectedSaveSize $expected")
        }
        result.saveSize?.let { expected ->
            if (size != expected) return AdoptionDecision.Reject("candidate size $size != result saveSize $expected")
        }

        val contentHash = request.contentHash
        if (contentHash.isNotEmpty()) {
            val canonicalContent = SecureFiles.resolveExistingRegular(Path.of(request.contentPath)).getOrElse {
                return AdoptionDecision.Reject("content file missing or unreadable; ROM identity cannot be verified")
            }
            if (!SecureFiles.sha256Hex(canonicalContent).equals(contentHash.trim(), ignoreCase = true)) {
                return AdoptionDecision.Reject("content hash mismatch; ROM identity unverified")
            }
        }

        if (request.coreBuildRevision.isBlank()) {
            return AdoptionDecision.Reject("request has no pinned core build revision")
        }

        return AdoptionDecision.Approve("hash, size, ROM identity, and core revision checks passed")
    }
}

/**
 * Launch journal supervision for the `rommulus-player` process (Phase 8 Wave 2;
 * plans/LINUX_X64.md §12.4/§12.5).
 *
 * ## Crash-safe ordering ([prepareLaunch])
 *  1. Create the session directory `journals/<sessionId>/` (0700).
 *  2. Atomically write `request.json` (0600) — INERT on its own: without a journal, no player
 *     is ever spawned for it and startup recovery ignores orphan request files.
 *  3. Atomically write `journal.json` (0600, state=PENDING) — from this moment the session is
 *     supervised; every later crash is recoverable by [scanIncompleteJournals].
 *  4. Spawn the player (`rommulus-player --request <file>`). A crash between 3 and 4 leaves a
 *     PENDING journal with no result → startup marks it INTERRUPTED.
 *
 * ## Reconciliation ([onPlayerExit], replayed by [scanIncompleteJournals])
 * The result file is read from disk, strictly parsed, and its sessionId re-verified against
 * the journal. Adoption of the candidate save goes through [SaveAdoptionPolicy]. Only after
 * the adoption decision is final does the journal flip to RECONCILED — and only then are the
 * session artifacts deleted (§12.4: "The client commits result reconciliation before deleting
 * the journal").
 *
 * ## Player log retention
 * A cleanly reconciled session RETAINS its `player.log` (+ rotation) — a clean exit is exactly
 * when the renderer/core diagnostics are needed (a black-screen session closes cleanly). The
 * retention is bounded: [pruneRetainedPlayerLogs] keeps only the newest
 * [RETAINED_PLAYER_LOG_SESSIONS] sessions' logs (each ≤ 2 × 2 MiB by rotation), and the
 * startup scan treats a log-only session directory as retention residue, never as an orphan.
 * INTERRUPTED (forensic) sessions keep their log unconditionally.
 *
 * ## Fail-closed invariants
 * - A journal is never deleted while its result is unreconciled (malformed result, adoption
 *   rejection, hash mismatch → files preserved + a recovery diagnostic).
 * - The confirmed save at `savePath` is only ever replaced after EVERY adoption check passes;
 *   the replacement itself is atomic (copy to temp in the target directory + fsync + rename).
 * - Malformed journals/results are never deleted or overwritten.
 */
class LaunchJournalSupervisor(
    private val journalsRoot: Path,
    private val launcher: PlayerProcessLauncher,
    private val adoptionPolicy: SaveAdoptionPolicy = DefaultSaveAdoptionPolicy(),
    /** Liveness probe for a session's player (a real probe lands with the player in Wave 3+). */
    private val playerLiveness: (String) -> PlayerLiveness = { PlayerLiveness.DEAD },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Ingests the player's controller-binding sidecar (`<sessionDir>/controller-bindings.json`,
     * §11.9) BEFORE reconciliation can delete the session artifacts. Invoked from [onPlayerExit]
     * and [reconcile] (the startup-replay path) with the session directory; it MUST be
     * idempotent (a second call finds no file) and MUST NOT throw — failures are logged by the
     * ingestor itself and never break reconciliation. A crashed player never writes a sidecar,
     * so crash paths simply find nothing to do. Null (the default) disables ingestion.
     */
    private val bindingSidecarIngestor: ((Path) -> Unit)? = null,
) {

    internal val store = LaunchJournalStore(journalsRoot)

    /** Runs the sidecar ingestor fail-soft (see [bindingSidecarIngestor] for the contract). */
    private fun ingestBindingSidecar(sessionId: String) {
        bindingSidecarIngestor?.let { ingestor ->
            runCatching { ingestor(store.sessionDir(sessionId)) }
        }
    }

    /**
     * Imports any sidecars already written by exited players without changing journal state.
     *
     * The normal exit watcher performs this import, but callers that need controller settings
     * immediately (for example, a rapid relaunch) can reconcile just this independent artifact
     * without treating a still-running player's journal as interrupted.
     */
    fun syncControllerBindingSidecars() {
        store.listSessionIds().forEach(::ingestBindingSidecar)
    }

    // ------------------------------------------------------------------ prepare

    /**
     * Writes the request + journal atomically (crash-safe ordering above) and spawns the player.
     * On spawn failure the journal is marked INTERRUPTED immediately (we know no player exists)
     * and [PrepareLaunchResult.Failed] is returned; the session files are preserved.
     */
    fun prepareLaunch(
        params: PlayerLaunchParams,
        sessionId: String = UUID.randomUUID().toString(),
    ): PrepareLaunchResult {
        SecureFiles.requireSessionId(sessionId).fold(
            onSuccess = { /* ok */ },
            onFailure = { e -> return PrepareLaunchResult.Failed(e.message ?: "invalid sessionId") },
        )

        val sessionDir = runCatching { store.ensureSessionDir(sessionId) }
            .getOrElse { return PrepareLaunchResult.Failed("cannot create session directory: ${it.message}") }
        val requestPath = sessionDir.resolve(LaunchJournalStore.REQUEST_FILE_NAME)
        val resultPath = sessionDir.resolve(LaunchJournalStore.RESULT_FILE_NAME)
        val candidateSavePath = sessionDir.resolve(LaunchJournalStore.CANDIDATE_FILE_NAME)
        val now = clock()

        val request = PlayerRequest(
            protocolVersion = PLAYER_PROTOCOL_VERSION,
            sessionId = sessionId,
            coreId = params.coreId,
            coreBuildRevision = params.coreBuildRevision,
            corePath = params.corePath.toAbsolutePath().normalize().toString(),
            // No-content cores (test_core) launch with an empty content path (§12.2: the player
            // then has no ROM to load/verify).
            contentPath = params.contentPath?.toAbsolutePath()?.normalize()?.toString().orEmpty(),
            contentHash = params.contentHash,
            systemDir = params.systemDir.toAbsolutePath().normalize().toString(),
            savePath = params.savePath.toAbsolutePath().normalize().toString(),
            candidateSavePath = candidateSavePath.toString(),
            resultPath = resultPath.toString(),
            expectedSaveSize = params.expectedSaveSize,
            video = params.video,
            controllerBindings = params.controllerBindings,
            keyboardBindings = params.keyboardBindings,
            rendererOverride = params.rendererOverride,
        )

        // Step 2: request first — an orphan request (no journal) is inert.
        runCatching { store.writeRequest(sessionId, PlayerProtocol.serializeRequest(request)) }
            .getOrElse { return PrepareLaunchResult.Failed("failed to write request file: ${it.message}") }

        // Step 3: journal second — from now on the session is supervised.
        val journal = LaunchJournal(
            sessionId = sessionId,
            requestPath = requestPath,
            resultPath = resultPath,
            candidateSavePath = candidateSavePath,
            state = JournalState.PENDING,
            createdAtEpochMs = now,
            updatedAtEpochMs = now,
        )
        runCatching { store.write(journal) }
            .getOrElse { return PrepareLaunchResult.Failed("failed to write journal: ${it.message}") }

        val session = LaunchSession(sessionId, requestPath, resultPath, candidateSavePath)

        // Step 4: spawn.
        return when (val outcome = launcher.launch(request)) {
            is LaunchOutcome.Started -> PrepareLaunchResult.Ready(session, outcome)
            is LaunchOutcome.Error -> {
                store.write(journal.withState(JournalState.INTERRUPTED, clock()))
                PrepareLaunchResult.Failed(outcome.message, session)
            }
        }
    }

    // ------------------------------------------------------------------ exit handling

    /**
     * Reconciles a session after its player process exited.
     *
     * A strictly valid result for this session is authoritative regardless of the exit code —
     * the player writes launch_failed/runtime_failed results precisely to be reconciled.
     * Otherwise (missing result, malformed JSON, protocol mismatch, foreign sessionId, crash
     * exit) the session is marked INTERRUPTED and ALL files are preserved (§12.3: such exits
     * are crashes, never coerced into an exit kind).
     */
    fun onPlayerExit(session: LaunchSession, exitCode: Int): PlayerExitReport {
        val journal = store.read(session.sessionId).fold(
            onSuccess = { it },
            onFailure = { e ->
                return PlayerExitReport.ReconcileFailed(session, "journal unreadable or malformed; preserved: ${e.message}")
            },
        ) ?: return PlayerExitReport.JournalMissing(session.sessionId)

        // Ingest the player's controller-binding sidecar (if it wrote one) BEFORE any
        // reconciliation below can delete the session artifacts. Runs for every exit kind —
        // a sidecar only exists when the player completed its shutdown sequence, so this is
        // a no-op on crash paths.
        ingestBindingSidecar(session.sessionId)

        val resultState = readResultFile(session)
        if (resultState !is ResultFile.Valid || resultState.value.sessionId != session.sessionId) {
            markInterrupted(journal)
            val reason = when (resultState) {
                is ResultFile.Absent -> "no result file after exit (exitCode=$exitCode) — classified as crash per §12.3"
                is ResultFile.Invalid -> "result file unusable (exitCode=$exitCode): ${resultState.reason}"
                else -> "result sessionId does not match the journal"
            }
            return PlayerExitReport.CrashInterrupted(session, reason, inspectCandidate(session.candidateSavePath))
        }

        return when (val outcome = reconcile(session.sessionId)) {
            is ReconcileOutcome.Success ->
                PlayerExitReport.Reconciled(session, resultState.value, outcome.adoption)
            is ReconcileOutcome.Failed ->
                // Fail-closed: files preserved; the next startup scan replays reconciliation.
                PlayerExitReport.ReconcileFailed(session, outcome.reason)
        }
    }

    /** [onPlayerExit] by session ID (loads the journal to reconstruct the session). */
    fun onPlayerExitBySessionId(sessionId: String, exitCode: Int): PlayerExitReport {
        val journal = store.read(sessionId).getOrNull()
            ?: return PlayerExitReport.JournalMissing(sessionId)
        val session = LaunchSession(journal.sessionId, journal.requestPath, journal.resultPath, journal.candidateSavePath)
        return onPlayerExit(session, exitCode)
    }

    // ------------------------------------------------------------------ startup scan

    /**
     * Startup crash-recovery scan (§12.5). For each session under the journals root:
     * - no player and no result → mark INTERRUPTED + inspect the candidate save (preserved);
     * - valid result not reconciled → replay idempotent reconciliation — including for
     *   INTERRUPTED sessions whose result file appeared after the interrupt (recovery-friendly;
     *   INTERRUPTED is not terminal for reconciliation, see [JournalState.INTERRUPTED]);
     * - malformed journal/result (a result with a foreign sessionId counts as untrusted) →
     *   mark INTERRUPTED + preserve files + surface a recovery diagnostic (never delete);
     * - player still alive and owned → leave the journal untouched and do NOT start another player.
     */
    fun scanIncompleteJournals(): List<LaunchRecoveryDiagnostic> {
        val diagnostics = store.listSessionIds().flatMap { sessionId ->
            try {
                scanOne(sessionId)
            } catch (e: Exception) {
                listOf(
                    LaunchRecoveryDiagnostic(
                        sessionId,
                        LaunchRecoveryDiagnostic.Kind.UNEXPECTED_ERROR,
                        e.message ?: e::class.java.simpleName,
                    ),
                )
            }
        }
        // A crash between the RECONCILED write and the cleanup sweep can leave more than
        // [RETAINED_PLAYER_LOG_SESSIONS] log-only session directories behind; prune them here
        // too so the retention bound holds across restarts.
        pruneRetainedPlayerLogs()
        return diagnostics
    }

    private fun scanOne(sessionId: String): List<LaunchRecoveryDiagnostic> {
        val journalPath = store.journalPath(sessionId)
        if (!Files.exists(journalPath)) {
            // A reconciled session retains its player log (bounded, see
            // [pruneRetainedPlayerLogs]): a log-only directory is retention residue, not an
            // orphan — no diagnostic.
            if (isRetainedLogResidue(sessionId)) return emptyList()
            return listOf(
                diag(
                    sessionId,
                    LaunchRecoveryDiagnostic.Kind.ORPHAN_SESSION_FILES,
                    "session directory without journal.json (request committed, journal never was); files preserved",
                ),
            )
        }

        val journal = store.read(sessionId).fold(
            onSuccess = { it },
            onFailure = { e ->
                return listOf(
                    diag(sessionId, LaunchRecoveryDiagnostic.Kind.MALFORMED_JOURNAL, "journal.json does not parse; file preserved: ${e.message}"),
                )
            },
        ) ?: return emptyList() // unreachable: journal existence verified above

        if (journal.state == JournalState.RECONCILED) {
            // Reconciliation committed but the process died before cleanup — finish it (idempotent).
            cleanupSession(sessionId)
            return listOf(
                diag(sessionId, LaunchRecoveryDiagnostic.Kind.STALE_RECONCILED_CLEANED, "reconciled journal left behind by a crash during cleanup; removed"),
            )
        }

        if (playerLiveness(sessionId) != PlayerLiveness.DEAD) {
            return listOf(
                diag(
                    sessionId,
                    LaunchRecoveryDiagnostic.Kind.PLAYER_STILL_ALIVE,
                    "player for this session is still running (or liveness unknown); journal left untouched and no second player started",
                ),
            )
        }

        val session = LaunchSession(sessionId, journal.requestPath, journal.resultPath, journal.candidateSavePath)
        return when (val resultState = readResultFile(session)) {
            is ResultFile.Absent -> {
                markInterrupted(journal)
                val inspection = inspectCandidate(journal.candidateSavePath)
                listOf(
                    diag(
                        sessionId,
                        LaunchRecoveryDiagnostic.Kind.INTERRUPTED_NO_RESULT,
                        "no result file and no live player; marked INTERRUPTED; candidate: ${inspection.describe()}",
                    ),
                )
            }
            is ResultFile.Invalid -> {
                markInterrupted(journal)
                listOf(diag(sessionId, LaunchRecoveryDiagnostic.Kind.MALFORMED_RESULT, "result file unusable; preserved: ${resultState.reason}"))
            }
            is ResultFile.Valid -> {
                if (resultState.value.sessionId != sessionId) {
                    // Same classification as onPlayerExit: a result that does not belong to this
                    // session is untrusted. Mark INTERRUPTED and preserve everything — without
                    // this guard the scan would re-surface RECONCILE_FAILED forever for a
                    // mismatch that can never heal on its own.
                    markInterrupted(journal)
                    return listOf(
                        diag(
                            sessionId,
                            LaunchRecoveryDiagnostic.Kind.MALFORMED_RESULT,
                            "result sessionId does not match the journal; treated as untrusted and preserved",
                        ),
                    )
                }
                when (val outcome = reconcile(sessionId)) {
                    is ReconcileOutcome.Success ->
                        listOf(
                            diag(sessionId, LaunchRecoveryDiagnostic.Kind.REPLAY_RECONCILED, "valid unreconciled result replayed; reconciliation committed and journal deleted"),
                        )
                    is ReconcileOutcome.Failed ->
                        listOf(diag(sessionId, LaunchRecoveryDiagnostic.Kind.RECONCILE_FAILED, "reconciliation failed; files preserved: ${outcome.reason}"))
                }
            }
        }
    }

    private fun diag(sessionId: String, kind: LaunchRecoveryDiagnostic.Kind, detail: String): LaunchRecoveryDiagnostic =
        LaunchRecoveryDiagnostic(sessionId, kind, detail)

    // ------------------------------------------------------------------ reconciliation

    /**
     * Idempotent reconciliation of one session. Re-reads the journal, request, and result from
     * disk (never trusts in-memory copies — TOCTOU), validates strictly, applies the adoption
     * policy, commits RECONCILED, then deletes the session artifacts. Never deletes a journal
     * whose result is unreconciled; never overwrites the confirmed save unless every check passes.
     */
    fun reconcile(sessionId: String): ReconcileOutcome {
        val journal = store.read(sessionId)
            .getOrElse { return ReconcileOutcome.Failed("journal unreadable: ${it.message}") }
            ?: return ReconcileOutcome.Failed("no journal for session $sessionId")

        // Startup-replay path: a sidecar may still be on disk when the process died between
        // the player's exit and the exit watcher. Idempotent no-op when already ingested or
        // absent (covers the RECONCILED early-return below too).
        ingestBindingSidecar(sessionId)

        if (journal.state == JournalState.RECONCILED) {
            cleanupSession(sessionId)
            return ReconcileOutcome.Success(adoption = null, alreadyReconciled = true)
        }

        // Re-validate the request from disk (TOCTOU: do not trust a previously parsed copy).
        val request = readRequestFile(journal.requestPath)
            ?: return ReconcileOutcome.Failed("request file missing or malformed; preserved")
        if (request.sessionId != sessionId) {
            return ReconcileOutcome.Failed("request sessionId does not match the journal")
        }

        val session = LaunchSession(sessionId, journal.requestPath, journal.resultPath, journal.candidateSavePath)
        val resultState = readResultFile(session)
        if (resultState !is ResultFile.Valid) {
            val reason = when (resultState) {
                is ResultFile.Absent -> "no result file for session $sessionId"
                is ResultFile.Invalid -> "result unusable: ${resultState.reason}"
                else -> "unexpected result state"
            }
            return ReconcileOutcome.Failed(reason)
        }
        val result = resultState.value
        if (result.sessionId != sessionId) {
            return ReconcileOutcome.Failed("result sessionId does not match the journal")
        }

        val adoption: AdoptionSummary? =
            if (request.contentPath.isEmpty()) {
                // No-content core (e.g. test_core): the request carries no ROM identity, so there
                // is nothing to bind a save to — skip adoption entirely. The player still writes a
                // scratch candidate and reports checkpointWritten=true but never sets saveHash or
                // saveSize; running the policy against that result would Reject ("no saveHash or
                // saveSize") and wedge this journal in RECONCILE_FAILED at every startup scan,
                // with the session dir never cleaned. The candidate is plain session residue:
                // once RECONCILED is committed below, cleanupSession removes it with everything
                // else. Crash recovery is unaffected — INTERRUPTED sessions without a result are
                // still preserved for inspection (that path never reaches this code).
                null
            } else if (result.checkpointWritten && result.exitKind in ADOPTABLE_EXIT_KINDS) {
                val adoptionResult = adoptOrConfirm(journal, request, result)
                if (adoptionResult.isFailure) {
                    return ReconcileOutcome.Failed(adoptionResult.exceptionOrNull()?.message ?: "adoption failed")
                }
                adoptionResult.getOrThrow()
            } else {
                null
            }

        // Commit the reconciliation BEFORE deleting the journal (§12.4). The adoption decision
        // is now final, so cleanupSession removes every artifact including any leftover candidate.
        store.write(journal.withState(JournalState.RECONCILED, clock()))
        cleanupSession(sessionId)
        return ReconcileOutcome.Success(adoption = adoption)
    }

    /**
     * Adopts the session's candidate save, or confirms a previously completed adoption
     * (idempotent replay after a crash between adoption and cleanup).
     */
    private fun adoptOrConfirm(journal: LaunchJournal, request: PlayerRequest, result: PlayerResult): Result<AdoptionSummary> =
        runCatching {
            val sessionDir = store.sessionDir(journal.sessionId)
            val targetSavePath = Path.of(request.savePath)

            // TOCTOU: re-resolve the candidate at open time — canonical, regular, non-symlink,
            // and still inside this session's directory (a swapped-in symlink is rejected).
            if (!Files.exists(journal.candidateSavePath)) {
                // Candidate gone: either a previous reconcile already adopted it (crash before
                // cleanup) or the player never wrote one. Confirm the former; fail-closed on the latter.
                val failureReason = confirmAlreadyAdopted(result, request, targetSavePath)
                if (failureReason != null) throw IOException(failureReason)
                val verifiedByData =
                    result.saveHash != null || result.saveSize != null || request.expectedSaveSize != null
                return@runCatching AdoptionSummary(
                    decision = AdoptionDecision.Approve(
                        if (verifiedByData) {
                            "candidate already adopted in a previous reconciliation (idempotent replay); confirmed against verification data"
                        } else {
                            "candidate already adopted in a previous reconciliation (idempotent replay); no verification datum present — confirmed by structural evidence (candidate absent, save at the requested path)"
                        },
                    ),
                    adopted = false,
                    targetSavePath = targetSavePath,
                )
            }
            val candidate = SecureFiles.resolveExistingRegular(journal.candidateSavePath).getOrThrow()
            if (!SecureFiles.isWithin(candidate, sessionDir.toRealPath())) {
                throw SecurityException("candidate save escapes the session directory: $candidate")
            }

            val targetCanonical = SecureFiles.resolveExistingRegular(targetSavePath)
                .fold(
                    onSuccess = { it },
                    onFailure = { targetSavePath.toAbsolutePath().normalize() }, // target absent yet — fine
                )

            val decision = adoptionPolicy.evaluate(AdoptionContext(journal, request, result, candidate, targetCanonical))
            if (decision is AdoptionDecision.Reject) {
                // Fail-closed: an unverifiable checkpoint is NOT reconciled. The journal stays,
                // the candidate and result are preserved for forensics, and the confirmed save
                // (if any) is untouched (§12.5). The next startup scan re-surfaces this.
                throw IOException("adoption rejected: ${decision.reason}")
            }

            adoptFile(candidate, targetSavePath).getOrThrow()
            Files.deleteIfExists(candidate)
            AdoptionSummary(decision = decision, adopted = true, targetSavePath = targetSavePath)
        }

    /**
     * Confirms that a previously completed adoption is in place (candidate already moved to the
     * confirmed save). Verifies every datum the session's own records carry — result.saveHash,
     * result.saveSize, and request.expectedSaveSize — against the file now at [targetSavePath];
     * any mismatch fails closed.
     *
     * When NO verification datum exists anywhere, the adoption is confirmed by STRUCTURAL
     * evidence instead: this session's own records (a result with checkpointWritten=true naming
     * exactly this candidate; a request pinning exactly this savePath) combined with the
     * candidate being gone and a save present at the requested path is the crash signature of a
     * completed adoption whose RECONCILED write was lost. Confirming only commits RECONCILED and
     * deletes the journal artifacts — it never touches the confirmed save — so there is no data-
     * loss or double-adoption risk, and the journal can never be permanently wedged in this
     * window (previously this case failed closed forever, re-surfacing RECONCILE_FAILED at every
     * startup). Returns null when confirmed, or a failure reason.
     */
    private fun confirmAlreadyAdopted(
        result: PlayerResult,
        request: PlayerRequest,
        targetSavePath: Path,
    ): String? {
        if (!Files.exists(targetSavePath)) {
            return "candidate save missing and no confirmed save exists; nothing to adopt"
        }
        val canonical = SecureFiles.resolveExistingRegular(targetSavePath).getOrThrow()
        result.saveHash?.let { expected ->
            val actual = SecureFiles.sha256Hex(canonical)
            if (!actual.equals(expected.trim(), ignoreCase = true)) {
                return "confirmed save does not match the result's hash; cannot confirm a previous adoption"
            }
        }
        result.saveSize?.let { expected ->
            if (Files.size(canonical) != expected) {
                return "confirmed save size does not match the result's saveSize"
            }
        }
        request.expectedSaveSize?.let { expected ->
            if (Files.size(canonical) != expected) {
                return "confirmed save size does not match the request's expectedSaveSize"
            }
        }
        // No verification datum in any of the session's records: confirm by structural evidence
        // (see KDoc) so the journal is never permanently wedged after a crash in this window.
        return null
    }

    /**
     * Moves [candidate] into place at [target] atomically and across directory boundaries
     * (state root → data root may be different filesystems, so a plain rename is not enough):
     * copy to a temp file in the target's directory (O_CREAT|O_EXCL) + fsync + atomic rename.
     * Called ONLY after every adoption check passed — this is the sole path that can replace
     * an existing confirmed save (§12.5).
     */
    private fun adoptFile(candidate: Path, target: Path): Result<Unit> = runCatching {
        val dir = checkNotNull(target.parent) { "target has no parent directory: $target" }
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".save-", "tmp")
        try {
            FileChannel.open(temp, WRITE).use { out ->
                FileChannel.open(candidate, READ).use { input ->
                    input.transferTo(0, input.size(), out)
                }
                out.force(true)
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    // ------------------------------------------------------------------ helpers

    private sealed interface ResultFile {
        data object Absent : ResultFile
        data class Invalid(val reason: String) : ResultFile
        data class Valid(val value: PlayerResult) : ResultFile
    }

    /** Reads + strictly parses the result file, re-verifying it at open time (TOCTOU). */
    private fun readResultFile(session: LaunchSession): ResultFile {
        val path = session.resultPath
        if (!Files.exists(path)) return ResultFile.Absent
        val canonicalResult = SecureFiles.resolveExistingRegular(path)
        if (canonicalResult.isFailure) {
            return ResultFile.Invalid("result file unreadable or not a regular file: ${canonicalResult.exceptionOrNull()?.message}")
        }
        val canonical = canonicalResult.getOrThrow()
        if (!SecureFiles.isWithin(canonical, journalsRoot.toRealPath())) {
            return ResultFile.Invalid("result file escapes the journals root (symlink?): $canonical")
        }
        val textResult = runCatching { Files.readString(canonical, StandardCharsets.UTF_8) }
        if (textResult.isFailure) {
            return ResultFile.Invalid("result file unreadable: ${textResult.exceptionOrNull()?.message}")
        }
        return PlayerProtocol.parseResult(textResult.getOrThrow()).fold(
            onSuccess = { ResultFile.Valid(it) },
            onFailure = { ResultFile.Invalid(it.message ?: "malformed result") },
        )
    }

    /** Reads + strictly parses the request file from disk (TOCTOU re-validation). */
    private fun readRequestFile(path: Path): PlayerRequest? {
        if (!Files.exists(path)) return null
        val canonicalResult = SecureFiles.resolveExistingRegular(path)
        if (canonicalResult.isFailure) return null
        val textResult = runCatching { Files.readString(canonicalResult.getOrThrow(), StandardCharsets.UTF_8) }
        if (textResult.isFailure) return null
        return PlayerProtocol.parseRequest(textResult.getOrThrow()).getOrNull()
    }

    /**
     * PENDING → INTERRUPTED (idempotent; this function never transitions away from
     * INTERRUPTED/RECONCILED). Note that [reconcile] may still move an INTERRUPTED journal to
     * RECONCILED when a strictly valid result appears later — see [JournalState.INTERRUPTED].
     */
    private fun markInterrupted(journal: LaunchJournal) {
        if (journal.state == JournalState.PENDING) {
            store.write(journal.withState(JournalState.INTERRUPTED, clock()))
        }
    }

    /** Inspects the candidate save at [path] — re-hashed at inspection time (never cached). */
    fun inspectCandidate(path: Path): CandidateInspection {
        if (!Files.exists(path)) return CandidateInspection(path, exists = false, sizeBytes = null, sha256Hex = null)
        val canonical = SecureFiles.resolveExistingRegular(path).getOrNull()
            ?: return CandidateInspection(path, exists = true, sizeBytes = null, sha256Hex = null)
        val size = runCatching { Files.size(canonical) }.getOrNull()
        val hash = runCatching { SecureFiles.sha256Hex(canonical) }.getOrNull()
        return CandidateInspection(path, exists = true, sizeBytes = size, sha256Hex = hash)
    }

    /**
     * Deletes session artifacts after a committed reconciliation. Called only after the journal
     * is RECONCILED (or for stale RECONCILED residue) — at that point the adoption decision is
     * FINAL, so the candidate save is always removed: an adopted candidate was already moved into
     * place (a crash remnant would merely duplicate the confirmed save), and a non-adopted one
     * was scratch data the player explicitly did not checkpoint. Removing it — and thereby the
     * now-empty session directory — is what keeps a cleanly reconciled session from being
     * misreported as ORPHAN_SESSION_FILES on every later startup. A failed deletion is harmless:
     * the next startup sees RECONCILED and cleans up again (idempotent).
     *
     * The player log (active + rotated) is the one retained artifact: a clean exit is exactly
     * when the renderer/core diagnostics are needed (a black-screen session closes cleanly), so
     * the log is kept for on-device debugging. [pruneRetainedPlayerLogs] bounds the retention to
     * the newest [RETAINED_PLAYER_LOG_SESSIONS] sessions; INTERRUPTED sessions keep their log
     * unconditionally (forensics).
     */
    private fun cleanupSession(sessionId: String) {
        runCatching { Files.deleteIfExists(store.journalPath(sessionId)) }
        runCatching { Files.deleteIfExists(store.requestPath(sessionId)) }
        runCatching { Files.deleteIfExists(store.resultPath(sessionId)) }
        runCatching { Files.deleteIfExists(store.candidatePath(sessionId)) }
        // Player log capture (active + rotated): RETAINED for diagnostics (see KDoc); the
        // retention sweep bounds it to the newest [RETAINED_PLAYER_LOG_SESSIONS] sessions.
        pruneRetainedPlayerLogs()
        // Remove the directory when empty (no log → nothing left); a retained log keeps the
        // directory in place until the retention sweep prunes it.
        runCatching { Files.deleteIfExists(store.sessionDir(sessionId)) }
    }

    /**
     * True when [sessionId]'s directory holds nothing but the retained player log (+ its
     * rotation slot) — the residue of a cleanly reconciled session whose log is kept for
     * diagnostics (see [pruneRetainedPlayerLogs]). Such a directory is NOT an orphan: the
     * journal was committed and reconciliation completed, so the startup scan reports no
     * ORPHAN_SESSION_FILES for it. An empty directory (a crash between the artifact deletions
     * and the directory deletion) counts too — the sweep removes it.
     */
    private fun isRetainedLogResidue(sessionId: String): Boolean =
        runCatching {
            val dir = store.sessionDir(sessionId)
            Files.isDirectory(dir) &&
                Files.list(dir).use { stream ->
                    stream.allMatch { it.fileName.toString() in RETAINED_LOG_FILE_NAMES }
                }
        }.getOrDefault(false)

    /**
     * Bounded retention for reconciled sessions' player logs: a cleanly reconciled session
     * keeps its `player.log` (+ rotation) for on-device diagnostics, and this sweep drops the
     * oldest retained logs beyond [RETAINED_PLAYER_LOG_SESSIONS] (newest first, by the log's
     * last-modified time; the session ID breaks ties deterministically). Each session's log is
     * bounded to 2 × [PlayerLogCapture.DEFAULT_MAX_BYTES] (4 MiB) by rotation, so the total
     * retention is bounded to [RETAINED_PLAYER_LOG_SESSIONS] × 4 MiB.
     *
     * Only log-only residue is ever touched: a directory that still carries a journal
     * (PENDING/INTERRUPTED — forensics; INTERRUPTED logs are preserved unconditionally) or any
     * other artifact (orphan files, preserved for the scan to report) is left alone. The
     * "no orphan session files" invariant for non-log artifacts is unchanged: a pruned
     * directory is removed once empty.
     */
    private fun pruneRetainedPlayerLogs() {
        val retained = store.listSessionIds().mapNotNull { sessionId ->
            if (Files.exists(store.journalPath(sessionId))) return@mapNotNull null // in-flight or forensics
            if (!isRetainedLogResidue(sessionId)) return@mapNotNull null // orphan files: preserved
            val newest = maxOf(
                runCatching { Files.getLastModifiedTime(store.playerLogPath(sessionId)).toMillis() }.getOrDefault(0L),
                runCatching { Files.getLastModifiedTime(store.playerLogRotationPath(sessionId)).toMillis() }.getOrDefault(0L),
            )
            if (newest == 0L) null else store.sessionDir(sessionId) to newest
        }
        retained
            .sortedWith(compareByDescending<Pair<Path, Long>> { it.second }.thenBy { it.first.fileName.toString() })
            .drop(RETAINED_PLAYER_LOG_SESSIONS)
            .forEach { (dir, _) ->
                runCatching { Files.deleteIfExists(dir.resolve(LaunchJournalStore.PLAYER_LOG_FILE_NAME)) }
                runCatching { Files.deleteIfExists(dir.resolve(LaunchJournalStore.PLAYER_LOG_FILE_NAME + PlayerLogCapture.ROTATION_SUFFIX)) }
                // The directory held only logs: remove it once empty (no orphan residue).
                runCatching { Files.deleteIfExists(dir) }
            }
    }

    companion object {
        /** Clean exits for which a written checkpoint is eligible for adoption (fail-closed: a crashed session's save is not trusted). */
        val ADOPTABLE_EXIT_KINDS = setOf(PlayerExitKind.COMPLETED, PlayerExitKind.CORE_REQUESTED_SHUTDOWN)

        /**
         * Number of most-recently reconciled sessions whose player logs are retained for
         * on-device diagnostics (a clean exit is exactly when the renderer/core logs are
         * needed). Each session's log is bounded to 2 × [PlayerLogCapture.DEFAULT_MAX_BYTES]
         * (4 MiB) by rotation, so the retention is bounded to N × 4 MiB.
         */
        const val RETAINED_PLAYER_LOG_SESSIONS = 3

        /** The only files a retained (reconciled) session directory may hold. */
        private val RETAINED_LOG_FILE_NAMES = setOf(
            LaunchJournalStore.PLAYER_LOG_FILE_NAME,
            LaunchJournalStore.PLAYER_LOG_FILE_NAME + PlayerLogCapture.ROTATION_SUFFIX,
        )

        /** Production wiring: journals under the XDG state root, real ProcessBuilder launcher. */
        fun forPaths(
            paths: AppPaths,
            bindingSidecarIngestor: ((Path) -> Unit)? = null,
        ): LaunchJournalSupervisor {
            val journalsRoot = paths.stateDir.resolve("journals")
            return LaunchJournalSupervisor(
                journalsRoot = journalsRoot,
                launcher = ProcessBuilderPlayerLauncher.defaultFor(journalsRoot, paths),
                bindingSidecarIngestor = bindingSidecarIngestor,
            )
        }
    }
}

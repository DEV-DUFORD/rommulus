package com.romm.desktop.player

import com.romm.androidtv.storage.TestAppPaths
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.description.TextDescription
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * LaunchJournalSupervisor tests (plans/LINUX_X64.md §12.4/§12.5): the full journal lifecycle,
 * crash recovery on startup, idempotent replay of unreconciled results, fail-closed invariants
 * (never delete an unreconciled journal; never overwrite a confirmed save), and the no-secret
 * guarantee on request/journal files.
 */
class LaunchJournalSupervisorTest {

    @TempDir
    lateinit var tempDir: Path

    private class Fixture(
        val supervisor: LaunchJournalSupervisor,
        val launcher: FakePlayerProcessLauncher,
        val paths: TestAppPaths,
        val params: PlayerLaunchParams,
    ) {
        val journalsRoot: Path get() = paths.stateDir.resolve("journals")
    }

    private fun fixture(
        liveness: (String) -> PlayerLiveness = { PlayerLiveness.DEAD },
        launchOutcome: (PlayerRequest) -> LaunchOutcome = { LaunchOutcome.Started(pid = 4242L) },
        adoptionPolicy: SaveAdoptionPolicy = DefaultSaveAdoptionPolicy(),
        fakeLauncher: FakePlayerProcessLauncher? = null,
    ): Fixture {
        val paths = TestAppPaths(tempDir)
        val journalsRoot = paths.stateDir.resolve("journals")
        val launcher = fakeLauncher ?: FakePlayerProcessLauncher(launchOutcome)
        val supervisor = LaunchJournalSupervisor(
            journalsRoot = journalsRoot,
            launcher = launcher,
            adoptionPolicy = adoptionPolicy,
            playerLiveness = liveness,
        )
        // A real ROM file with a pinned hash so the default adoption policy can verify identity.
        val content = paths.cacheDir.resolve("roms").resolve("game.gba")
        Files.createDirectories(content.parent)
        Files.writeString(content, "ROM-BYTES")
        val params = PlayerLaunchParams(
            coreId = "test_core",
            coreBuildRevision = "pinned-sha",
            corePath = paths.dataDir.resolve("cores").resolve("libtest_core.so"),
            contentPath = content,
            contentHash = SecureFiles.sha256Hex(content),
            systemDir = paths.dataDir.resolve("firmware"),
            savePath = paths.dataDir.resolve("saves").resolve("game").resolve("autosave.srm"),
        )
        return Fixture(supervisor, launcher, paths, params)
    }

    private fun readySession(fixture: Fixture): LaunchSession = when (val result = fixture.supervisor.prepareLaunch(fixture.params)) {
        is PrepareLaunchResult.Ready -> result.session
        is PrepareLaunchResult.Failed -> error("expected Ready, got Failed: ${result.reason}")
    }

    /** Simulates the player writing its candidate save + a valid result file. */
    private fun simulatePlayer(session: LaunchSession, saveBytes: ByteArray = "SAVE".toByteArray()) {
        Files.write(session.candidateSavePath, saveBytes)
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
            saveHash = SecureFiles.sha256Hex(session.candidateSavePath),
            saveSize = saveBytes.size.toLong(),
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))
    }

    // ------------------------------------------------------------------ lifecycle

    @Test
    fun `prepare writes request and journal atomically, exit reconciles, journal deleted`() {
        val f = fixture()
        val session = readySession(f)

        // On-disk artifacts after prepare: request + PENDING journal; no result yet.
        assertThat(Files.exists(session.requestPath)).isTrue()
        assertThat(Files.exists(session.resultPath)).isFalse()
        val pending = f.supervisor.store.read(session.sessionId).getOrNull()
        assertThat(pending?.state).isEqualTo(JournalState.PENDING)

        // The launcher received the request with the session's own result/candidate paths.
        assertThat(f.launcher.launches).hasSize(1)
        assertThat(f.launcher.launches.single().resultPath).isEqualTo(session.resultPath.toString())
        assertThat(f.launcher.launches.single().candidateSavePath).isEqualTo(session.candidateSavePath.toString())

        // Player runs to completion and writes candidate + result.
        simulatePlayer(session)

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.Reconciled::class.java)
        val reconciled = report as PlayerExitReport.Reconciled
        assertThat(reconciled.adoption?.adopted).isTrue()

        // Candidate adopted into the confirmed save location; journal deleted; dir cleaned.
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(Files.exists(session.candidateSavePath)).isFalse()
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
    }

    @Test
    fun `prepareLaunch serializes a null contentPath as an empty string`() {
        val f = fixture()
        val noContent = f.params.copy(contentPath = null, contentHash = "")
        val session = when (val result = f.supervisor.prepareLaunch(noContent)) {
            is PrepareLaunchResult.Ready -> result.session
            is PrepareLaunchResult.Failed -> error("expected Ready, got Failed: ${result.reason}")
        }

        // The request on disk carries the empty content path (no-content core, §12.2) and still
        // round-trips through the strict parser.
        val json = Files.readString(session.requestPath)
        assertThat(json).contains("\"contentPath\": \"\"")
        val parsed = PlayerProtocol.parseRequest(json).getOrThrow()
        assertThat(parsed.contentPath).isEmpty()
        assertThat(f.launcher.launches.single().contentPath).isEmpty()
    }

    // ------------------------------------------------------------------ crash cases

    @Test
    fun `crash without result marks interrupted and inspects candidate`() {
        val f = fixture()
        val session = readySession(f)
        Files.write(session.candidateSavePath, "PARTIAL".toByteArray()) // player died mid-write

        val report = f.supervisor.onPlayerExit(session, exitCode = 137)
        assertThat(report).isInstanceOf(PlayerExitReport.CrashInterrupted::class.java)
        val crash = report as PlayerExitReport.CrashInterrupted
        assertThat(crash.candidate.exists).isTrue()
        assertThat(crash.candidate.sizeBytes).isEqualTo(7L)
        assertThat(crash.candidate.sha256Hex).isEqualTo(SecureFiles.sha256Hex(session.candidateSavePath))

        // Fail-closed: journal preserved as INTERRUPTED; candidate preserved.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
        assertThat(Files.exists(session.candidateSavePath)).isTrue()
    }

    @Test
    fun `startup scan marks interrupted when no result and player dead`() {
        val f = fixture()
        val session = readySession(f) // app crashed right after spawn; player gone, no result

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.INTERRUPTED_NO_RESULT)
        }
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
    }

    @Test
    fun `startup scan preserves malformed result and surfaces diagnostic`() {
        val f = fixture()
        val session = readySession(f)
        Files.writeString(session.resultPath, "this is not json")

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.MALFORMED_RESULT)
        }
        // Files preserved; journal marked INTERRUPTED.
        assertThat(Files.readString(session.resultPath)).isEqualTo("this is not json")
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
    }

    @Test
    fun `startup scan preserves malformed journal and surfaces diagnostic`() {
        val f = fixture()
        val session = readySession(f)
        Files.writeString(f.journalsRoot.resolve(session.sessionId).resolve("journal.json"), "{broken")

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.MALFORMED_JOURNAL)
        }
        assertThat(Files.readString(f.journalsRoot.resolve(session.sessionId).resolve("journal.json"))).isEqualTo("{broken")
    }

    @Test
    fun `startup scan leaves journal untouched when player still alive and owned`() {
        val f = fixture(liveness = { PlayerLiveness.ALIVE_OWNED })
        val session = readySession(f)

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.PLAYER_STILL_ALIVE)
        }
        // Journal stays PENDING — not interrupted, and no second player is started.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.PENDING)
        assertThat(f.launcher.launches).hasSize(1)
    }

    @Test
    fun `spawn failure marks interrupted immediately and reports error`() {
        val f = fixture(launchOutcome = { LaunchOutcome.Error("no such binary: rommulus-player") })
        val result = f.supervisor.prepareLaunch(f.params)
        assertThat(result).isInstanceOf(PrepareLaunchResult.Failed::class.java)
        val failed = result as PrepareLaunchResult.Failed
        assertThat(failed.reason).contains("no such binary")

        val session = failed.session!!
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
        assertThat(Files.exists(session.requestPath)).isTrue() // preserved for forensics
    }

    // ------------------------------------------------------------------ replay (idempotent reconciliation)

    @Test
    fun `startup scan replays valid unreconciled result idempotently`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session) // player finished; app crashed before onPlayerExit

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.REPLAY_RECONCILED)
        }
        // Reconciliation committed: save adopted, journal deleted.
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()

        // A second scan is a no-op (idempotent).
        assertThat(f.supervisor.scanIncompleteJournals()).isEmpty()
    }

    @Test
    fun `replay after crash between adoption and cleanup confirms via save hash`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session, saveBytes = "SAVE".toByteArray())
        // Simulate a crash right after the candidate was moved into place but before cleanup:
        Files.createDirectories(f.params.savePath.parent)
        Files.move(session.candidateSavePath, f.params.savePath)

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.REPLAY_RECONCILED)
        }
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()
    }

    // ------------------------------------------------------------------ fail-closed

    @Test
    fun `adoption rejection preserves journal candidate and confirmed save`() {
        val f = fixture()
        val session = readySession(f)
        val existingSave = "CONFIRMED".toByteArray()
        Files.createDirectories(f.params.savePath.parent)
        Files.write(f.params.savePath, existingSave)

        Files.write(session.candidateSavePath, "NEW".toByteArray())
        // The player's result claims a hash the candidate does not have (tampered/corrupted).
        val badResult = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
            saveHash = "deadbeef",
            saveSize = 3L,
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(badResult))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.ReconcileFailed::class.java)

        // Fail-closed: journal preserved (retried by the next scan), candidate preserved,
        // and the confirmed save is NEVER overwritten.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNotNull
        assertThat(Files.exists(session.candidateSavePath)).isTrue()
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*existingSave)

        // The startup scan surfaces the failure and still preserves everything.
        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.RECONCILE_FAILED)
        }
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*existingSave)
    }

    @Test
    fun `result with foreign sessionId is not reconciled`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session)
        // Rewrite the result with a different (but valid-format) session ID.
        val forged = PlayerResult(
            sessionId = "99999999-9999-9999-9999-999999999999",
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
            saveHash = SecureFiles.sha256Hex(session.candidateSavePath),
            saveSize = 4L,
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(forged))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.CrashInterrupted::class.java)
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
        assertThat(Files.exists(session.resultPath)).isTrue() // preserved
        assertThat(Files.exists(f.params.savePath)).isFalse() // nothing adopted
    }

    @Test
    fun `checkpoint not written means no adoption but clean reconciliation`() {
        val f = fixture()
        val session = readySession(f)
        Files.write(session.candidateSavePath, "SCRATCH".toByteArray())
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.USER_CANCELLED_BEFORE_START,
            checkpointWritten = false,
            candidateSavePath = session.candidateSavePath.toString(),
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.Reconciled::class.java)
        assertThat((report as PlayerExitReport.Reconciled).adoption).isNull()
        assertThat(Files.exists(f.params.savePath)).isFalse() // no save adopted
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull() // journal deleted
    }

    // ------------------------------------------------------------------ no secrets on disk

    @Test
    fun `request and journal files carry no token origin or username`() {
        val f = fixture()
        val session = readySession(f)
        val requestText = Files.readString(session.requestPath)
        val journalText = Files.readString(f.journalsRoot.resolve(session.sessionId).resolve("journal.json"))
        for (forbidden in listOf("\"token\"", "\"origin\"", "\"username\"")) {
            assertThat(requestText).describedAs(TextDescription("request must not contain $forbidden")).doesNotContain(forbidden)
            assertThat(journalText).describedAs(TextDescription("journal must not contain $forbidden")).doesNotContain(forbidden)
        }

        // And the strict parser refuses a smuggled credential field.
        val smuggled = requestText.substringBeforeLast("}") + ",\n  \"token\": \"secret\"\n}"
        assertThat(PlayerProtocol.parseRequest(smuggled).isFailure).isTrue()
    }

    // ------------------------------------------------------------------ audit fixes (Wave 2)

    @Test
    fun `cleanly reconciled session without adoption leaves no orphan residue`() {
        val f = fixture()
        val session = readySession(f)
        // The player left a scratch candidate but declared it is NOT a checkpoint.
        Files.write(session.candidateSavePath, "SCRATCH".toByteArray())
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.USER_CANCELLED_BEFORE_START,
            checkpointWritten = false,
            candidateSavePath = session.candidateSavePath.toString(),
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.Reconciled::class.java)
        assertThat((report as PlayerExitReport.Reconciled).adoption).isNull()

        // Fully reconciled → the whole session dir (including the scratch candidate) is gone, so
        // later startups cannot misreport this cleanly-reconciled session as ORPHAN_SESSION_FILES.
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
        assertThat(f.supervisor.scanIncompleteJournals()).isEmpty()
        assertThat(f.supervisor.scanIncompleteJournals()).isEmpty()
    }

    @Test
    fun `crash before reconciled write with no verification data does not wedge the journal`() {
        val f = fixture()
        val session = readySession(f)
        Files.write(session.candidateSavePath, "SAVE".toByteArray())
        // The result carries NO saveHash and NO saveSize.
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))

        // Fault injection: the adoption (move to savePath + candidate delete) completed, then the
        // process died BEFORE the RECONCILED write.
        Files.createDirectories(f.params.savePath.parent)
        Files.move(session.candidateSavePath, f.params.savePath)

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.REPLAY_RECONCILED)
        }
        // The confirmed save is untouched and the journal is gone — no permanent wedge.
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
    }

    @Test
    fun `adoption of a present candidate without any verification datum is rejected`() {
        val f = fixture()
        val session = readySession(f)
        Files.write(session.candidateSavePath, "SAVE".toByteArray())
        // checkpointWritten=true on an adoptable exit, but NO saveHash and NO saveSize.
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.ReconcileFailed::class.java)
        assertThat((report as PlayerExitReport.ReconcileFailed).reason).contains("no saveHash or saveSize")

        // Fail-closed: journal + candidate preserved, confirmed save untouched.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNotNull
        assertThat(Files.exists(session.candidateSavePath)).isTrue()
        assertThat(Files.exists(f.params.savePath)).isFalse()

        // The startup scan re-surfaces the rejection and still preserves everything.
        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.RECONCILE_FAILED)
        }
        assertThat(Files.exists(f.params.savePath)).isFalse()
    }

    // ------------------------------------------------------------------ no-content core (test_core)

    @Test
    fun `no-content core checkpoint result without verification data reconciles cleanly`() {
        // The desktop Play flow for a no-content core: the player writes a 64-byte scratch
        // candidate and a valid completed result, but never sets saveHash or saveSize. Before
        // the fix this wedged the session — adoption rejected "no saveHash or saveSize",
        // RECONCILE_FAILED re-surfaced at every startup scan, and the session dir was never
        // cleaned. A no-content core has no ROM identity to bind a save to, so reconcile must
        // skip adoption entirely and clean up the session.
        val player = FakePlayerProcessLauncher(
            onLaunch = { request ->
                Files.write(Path.of(request.candidateSavePath), ByteArray(64))
                val result = PlayerResult(
                    sessionId = request.sessionId,
                    exitKind = PlayerExitKind.COMPLETED,
                    checkpointWritten = true,
                    candidateSavePath = request.candidateSavePath,
                    saveHash = null,
                    saveSize = null,
                )
                Files.writeString(Path.of(request.resultPath), PlayerProtocol.serializeResult(result))
            },
        )
        val f = fixture(fakeLauncher = player)
        val noContent = f.params.copy(contentPath = null, contentHash = "")

        val session = when (val result = f.supervisor.prepareLaunch(noContent)) {
            is PrepareLaunchResult.Ready -> result.session
            is PrepareLaunchResult.Failed -> error("expected Ready, got Failed: ${result.reason}")
        }
        // The request on disk carries the empty content path — what reconcile re-reads.
        assertThat(player.launches.single().contentPath).isEmpty()

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.Reconciled::class.java)
        assertThat((report as PlayerExitReport.Reconciled).adoption).isNull() // nothing adopted

        // The session is fully cleaned up: journal gone, candidate deleted, session dir removed.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()
        assertThat(Files.exists(session.candidateSavePath)).isFalse()
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
        assertThat(Files.exists(noContent.savePath)).isFalse() // no confirmed save written

        // No wedge: startup scans are clean and never surface RECONCILE_FAILED.
        assertThat(f.supervisor.scanIncompleteJournals()).isEmpty()
        assertThat(f.supervisor.scanIncompleteJournals().none { it.kind == LaunchRecoveryDiagnostic.Kind.RECONCILE_FAILED }).isTrue()
    }

    @Test
    fun `no-content core crash without result still preserves candidate for inspection`() {
        // Crash recovery must stay intact for no-content cores: no result + dead player is an
        // INTERRUPTED session whose candidate is preserved (the skip-adoption fix only applies
        // to the reconcile path, which a missing result never reaches).
        val f = fixture()
        val noContent = f.params.copy(contentPath = null, contentHash = "")
        val session = when (val result = f.supervisor.prepareLaunch(noContent)) {
            is PrepareLaunchResult.Ready -> result.session
            is PrepareLaunchResult.Failed -> error("expected Ready, got Failed: ${result.reason}")
        }
        Files.write(session.candidateSavePath, ByteArray(64)) // player died mid-run

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.INTERRUPTED_NO_RESULT)
        }
        // Fail-closed: journal marked INTERRUPTED, candidate preserved.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
        assertThat(Files.exists(session.candidateSavePath)).isTrue()
    }

    // ------------------------------------------------------------------ crash windows

    @Test
    fun `crash after adopt rename but before candidate delete does not double-adopt`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session, saveBytes = "SAVE".toByteArray())

        // Fault injection: the adoption copy+rename INTO savePath completed, then the process
        // died BEFORE Files.deleteIfExists(candidate) and before the RECONCILED write — the
        // candidate is still on disk alongside the confirmed save.
        Files.createDirectories(f.params.savePath.parent)
        Files.copy(session.candidateSavePath, f.params.savePath)

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.REPLAY_RECONCILED)
        }
        // Exactly the original bytes — the leftover candidate must never corrupt or duplicate
        // the confirmed save. No temp-file residue from the idempotent re-adopt.
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(Files.list(f.params.savePath.parent).use { it.count() }).isEqualTo(1L)
        assertThat(Files.exists(session.candidateSavePath)).isFalse()
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNull()
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
    }

    @Test
    fun `startup scan cleans stale reconciled residue left by a crash during cleanup`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session, saveBytes = "SAVE".toByteArray())
        // The adoption happened and RECONCILED was committed, but the process died before cleanup.
        Files.createDirectories(f.params.savePath.parent)
        Files.move(session.candidateSavePath, f.params.savePath)
        val journal = checkNotNull(f.supervisor.store.read(session.sessionId).getOrNull())
        f.supervisor.store.write(journal.withState(JournalState.RECONCILED, 123L))

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.STALE_RECONCILED_CLEANED)
        }
        // Everything removed, confirmed save intact; a second scan is clean (idempotent).
        assertThat(Files.readAllBytes(f.params.savePath)).containsExactly(*"SAVE".toByteArray())
        assertThat(Files.exists(f.journalsRoot.resolve(session.sessionId))).isFalse()
        assertThat(f.supervisor.scanIncompleteJournals()).isEmpty()
    }

    // ------------------------------------------------------------------ untrusted inputs at startup

    @Test
    fun `startup scan treats foreign sessionId result as untrusted and marks interrupted`() {
        val f = fixture()
        val session = readySession(f)
        simulatePlayer(session)
        // Rewrite the result with a different (but valid-format) session ID — same forgery the
        // onPlayerExit test uses, but discovered by the STARTUP scan.
        val forged = PlayerResult(
            sessionId = "99999999-9999-9999-9999-999999999999",
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
            saveHash = SecureFiles.sha256Hex(session.candidateSavePath),
            saveSize = 4L,
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(forged))

        val diagnostics = f.supervisor.scanIncompleteJournals()
        assertThat(diagnostics).anySatisfy { d ->
            assertThat(d.sessionId).isEqualTo(session.sessionId)
            assertThat(d.kind).isEqualTo(LaunchRecoveryDiagnostic.Kind.MALFORMED_RESULT)
        }
        // Fail-closed: interrupted, everything preserved, nothing adopted.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()?.state).isEqualTo(JournalState.INTERRUPTED)
        assertThat(Files.exists(session.resultPath)).isTrue()
        assertThat(Files.exists(session.candidateSavePath)).isTrue()
        assertThat(Files.exists(f.params.savePath)).isFalse()

        // The mismatch can never heal on its own — it must not re-surface as RECONCILE_FAILED.
        val second = f.supervisor.scanIncompleteJournals()
        assertThat(second.none { it.kind == LaunchRecoveryDiagnostic.Kind.RECONCILE_FAILED }).isTrue()
    }

    @Test
    fun `symlink candidate escaping the session directory is rejected at adoption`() {
        val f = fixture()
        val session = readySession(f)
        // A real file OUTSIDE the session dir, exposed through a symlink at the candidate path.
        val evil = tempDir.resolve("outside.srm")
        Files.write(evil, "EVIL".toByteArray())
        Files.deleteIfExists(session.candidateSavePath)
        Files.createSymbolicLink(session.candidateSavePath, evil)

        // Even with a result whose hash/size match the symlink target...
        val result = PlayerResult(
            sessionId = session.sessionId,
            exitKind = PlayerExitKind.COMPLETED,
            checkpointWritten = true,
            candidateSavePath = session.candidateSavePath.toString(),
            saveHash = SecureFiles.sha256Hex(evil),
            saveSize = 4L,
        )
        Files.writeString(session.resultPath, PlayerProtocol.serializeResult(result))

        val report = f.supervisor.onPlayerExit(session, exitCode = 0)
        assertThat(report).isInstanceOf(PlayerExitReport.ReconcileFailed::class.java)

        // Fail-closed: journal + result preserved, symlink untouched, confirmed save NOT created.
        assertThat(f.supervisor.store.read(session.sessionId).getOrNull()).isNotNull
        assertThat(Files.isSymbolicLink(session.candidateSavePath)).isTrue()
        assertThat(Files.exists(f.params.savePath)).isFalse()
    }
}

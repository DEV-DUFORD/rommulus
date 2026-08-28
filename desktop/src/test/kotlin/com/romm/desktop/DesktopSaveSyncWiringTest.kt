package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.library.RomDetail
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.firmwareDir
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SchedulerState
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.desktop.player.FakePlayerProcessLauncher
import com.romm.desktop.player.FakeRomContentStager
import com.romm.desktop.player.LaunchJournalSupervisor
import com.romm.desktop.player.LaunchOutcome
import com.romm.desktop.player.PlayerExitKind
import com.romm.desktop.player.PlayerExitReport
import com.romm.desktop.player.PlayerLaunchParams
import com.romm.desktop.player.PlayerProtocol
import com.romm.desktop.player.PlayerRequest
import com.romm.desktop.player.PlayerResult
import com.romm.desktop.player.PrepareLaunchResult
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.FakeSaveSyncSessionReader
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.PreLaunchSaveSynchronizer
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Coordinator-level tests for the Phase 9 save-sync wiring (plans/LINUX_X64.md): the real
 * [SaveSyncDrainExecutor] is wired into [DesktopAppCoordinator.scheduler]'s drain, and a
 * post-play hook enqueues a durable NEGOTIATE_AND_SYNC operation when a player session exits
 * with an ADOPTED checkpoint.
 *
 * The coordinator runs over an in-memory [InMemorySaveStateStore] (injected via the
 * saveStateStoreOverride seam) and a fake-seamed executor (saveSyncDrainExecutorOverride), so no
 * network or real SQLite queue is touched — but the FULL path is exercised: launchPlayer →
 * onPlayerProcessExited → enqueue (store writes) → scheduler.requestDrain("post-play") →
 * BackgroundSyncSchedulerImpl drain thread → SaveSyncDrainExecutor.drainBatch.
 *
 * The fake player pid belongs to a long-lived EXTERNAL `sleep` process: the coordinator's exit
 * watcher blocks on its `ProcessHandle.onExit()` (which is not allowed for the current process —
 * it would reconcile immediately and race the test), so the test's explicit
 * [DesktopAppCoordinator.onPlayerProcessExited] call is the sole, deterministic reconciler after
 * writing the player's candidate save + result file.
 */
@DisplayName("DesktopAppCoordinator — Phase 9 save-sync wiring")
class DesktopSaveSyncWiringTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val FILE_NAME = "zelda.gb"
        val SAVE_BYTES = "post-play-save-bytes".toByteArray()

        /** FakeRomContentStager's default staged payload (the pinned content hash). */
        val ROM_HASH: String = sha256Hex("fake-rom-content".toByteArray())

        /** SavePathPolicy sanitizes '/' and '\' to '_' — the scope key for [ORIGIN]'s save path. */
        val SERVER_KEY: String = ORIGIN.map { if (it == '/' || it == '\\') '_' else it }.joinToString("")
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val supervisor: LaunchJournalSupervisor,
        val launcher: FakePlayerProcessLauncher,
        /** Long-lived process backing the fake player pid; destroy in a finally block. */
        val playerProcess: Process?,
    ) {
        fun close() {
            val p = playerProcess ?: return
            runCatching { p.destroy() }
            runCatching { p.waitFor(2, TimeUnit.SECONDS) }
        }
    }

    private fun testRom(fileName: String = FILE_NAME): RomDetail = RomDetail(
        id = ROM_ID,
        title = "Test Game",
        platformDisplayName = "Nintendo Game Boy",
        platformSlug = "gb",
        summary = null,
        coverUrl = null,
        screenshotUrls = emptyList(),
        genres = emptyList(),
        companies = emptyList(),
        gameModes = emptyList(),
        playerCount = null,
        firstReleaseDateEpochMillis = null,
        averageRating = null,
        regions = emptyList(),
        languages = emptyList(),
        fileSizeBytes = 1234L,
        lastPlayedIso = null,
        nowPlaying = false,
        fileName = fileName,
    )

    /** Installs `libgambatte.so` so the approved gb core resolves. */
    private fun installGambatte(paths: AppPaths) {
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte.so"), byteArrayOf(0))
    }

    /** Gives the coordinator a coherent (non-kiosk) session for [ORIGIN] + [USERNAME]. */
    private fun signIn(c: DesktopAppCoordinator) {
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(c.sessionStorage.save(ORIGIN, USERNAME, 123L, kioskMode = false))
    }

    /** Blocks until [condition] holds (10ms poll, [timeoutMs] deadline). */
    private fun awaitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "condition not met within ${timeoutMs}ms" }
            Thread.sleep(10)
        }
    }

    /**
     * Coordinator wired for save-sync wiring tests: fake launcher (long-lived external pid so the
     * exit watcher blocks and never reconciles early), in-memory save-state store shared with a
     * fake-seamed drain executor (no network).
     */
    private fun wire(paths: AppPaths, store: InMemorySaveStateStore, syncGateway: FakeRommSyncGateway): Wired {
        val playerProcess = try {
            ProcessBuilder("sleep", "60").start()
        } catch (e: Exception) {
            null // no sleep binary — the watcher reconciles immediately (still correct, racy)
        }
        val pid = playerProcess?.pid() ?: -1L
        val launcher = FakePlayerProcessLauncher(
            outcomeFor = { LaunchOutcome.Started(pid = pid) },
        )
        val supervisor = LaunchJournalSupervisor(
            journalsRoot = paths.stateDir.resolve("journals"),
            launcher = launcher,
        )
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = ORIGIN,
            playerSupervisorOverride = supervisor,
            romDetailLookup = { testRom() },
            romContentStagerOverride = FakeRomContentStager(),
            saveStateStoreOverride = store,
            saveSyncDrainExecutorOverride = SaveSyncDrainExecutor(
                pendingOperations = store,
                saveReplicas = store,
                content = FileSaveContentGateway(paths.dataDir.toFile()),
                sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USERNAME)),
                deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1")),
                sync = syncGateway,
            ),
            preLaunchSaveSynchronizerOverride =
                PreLaunchSaveSynchronizer { SaveSyncOutcome.NoOpSynced(0L) },
        )
        return Wired(c, supervisor, launcher, playerProcess)
    }

    /**
     * Launches a real-content session, simulates the player checkpointing + exiting cleanly, and
     * drives [DesktopAppCoordinator.onPlayerProcessExited]. Returns the launch request (for the
     * pinned content hash / paths).
     *
     * Interleaving-robust: the exit watcher normally blocks on the `sleep` pid, but if the OS
     * process lookup is transiently empty it reconciles immediately — possibly BEFORE or AFTER
     * this thread's explicit call. Both threads run the same hook (the coordinator keeps the
     * launch context for replayable reports), so assert on DURABLE evidence: the checkpoint at
     * its confirmed save path and the enqueued operation (polled).
     */
    private fun playAndExit(wired: Wired, saveBytes: ByteArray = SAVE_BYTES): PlayerRequest {
        val c = wired.coordinator
        // Settle the scheduler's startup drain (empty queue → markDrained → Idle) so the later
        // post-play kick is the ONLY drain that processes the enqueued operation.
        awaitUntil(5_000) { c.scheduler.currentState() == SchedulerState.Idle }

        val started = c.launchPlayer(romId = ROM_ID) as PlayerLaunchResult.Started // cast asserts Started
        val request = wired.launcher.launches.single()

        // Simulate the player: write the candidate checkpoint + a valid clean-exit result.
        Files.write(Path.of(request.candidateSavePath), saveBytes)
        Files.writeString(
            Path.of(request.resultPath),
            PlayerProtocol.serializeResult(
                PlayerResult(
                    sessionId = started.sessionId,
                    exitKind = PlayerExitKind.COMPLETED,
                    checkpointWritten = true,
                    candidateSavePath = request.candidateSavePath,
                    saveHash = sha256Hex(saveBytes),
                    saveSize = saveBytes.size.toLong(),
                ),
            ),
        )

        val report = c.onPlayerProcessExited(started.sessionId, exitCode = 0)
        when (report) {
            is PlayerExitReport.Reconciled ->
                assertThat(report.adoption?.adopted).withFailMessage("expected an adopted checkpoint: $report").isTrue()
            // The watcher thread won the race and already reconciled + deleted the journal.
            is PlayerExitReport.JournalMissing -> Unit
            else -> throw AssertionError("unexpected report after clean exit: $report")
        }

        // Durable evidence of adoption: the checkpoint now sits at the confirmed save path.
        val target = Path.of(request.savePath)
        awaitUntil(5_000) { Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(saveBytes) }
        return request
    }

    /** Polls [store] for the single NEGOTIATE_AND_SYNC op (any status) and returns its id. */
    private fun awaitEnqueuedOp(store: InMemorySaveStateStore): Long {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            val allOps = enumValues<PendingOperationStatus>().flatMap { store.findByStatus(it) }
            allOps.singleOrNull { it.operationType == PendingOperationType.NEGOTIATE_AND_SYNC }?.id?.let { return it }
            check(System.currentTimeMillis() < deadline) { "no NEGOTIATE_AND_SYNC op was enqueued" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `player exit with an adopted save enqueues a NEGOTIATE_AND_SYNC op and the drain retries it on network failure`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val store = InMemorySaveStateStore()
        // Default FakeRommSyncGateway: negotiate fails with NETWORK_ERROR → retryable path.
        val syncGateway = FakeRommSyncGateway()
        val wired = wire(paths, store, syncGateway)
        try {
            signIn(wired.coordinator)

            playAndExit(wired)

            // (a) The durable queue holds exactly one NEGOTIATE_AND_SYNC op for this launch's
            // scope. (Polled: the exit-watcher thread may still be finishing its enqueue.)
            val opId = awaitEnqueuedOp(store)
            val op = store.findById(opId)!!
            assertThat(op.operationType).isEqualTo(PendingOperationType.NEGOTIATE_AND_SYNC)
            assertThat(op.serverKey).isEqualTo(SERVER_KEY)
            assertThat(op.userKey).isEqualTo(USERNAME)
            assertThat(op.romId).isEqualTo(ROM_ID)
            assertThat(op.romHash).isEqualTo(ROM_HASH)
            assertThat(op.slot).isEqualTo(SavePathPolicy.AUTOSAVE_SLOT)
            assertThat(op.negotiateCoreId).isEqualTo("gambatte")
            assertThat(op.negotiateFileName).isEqualTo(FILE_NAME)
            assertThat(op.negotiateCoreBuildRevision).isNotBlank()
            assertThat(op.origin).isNull() // resolved at drain time from the session store
            assertThat(op.localGenerationEpochMs).isPositive()

            // The replica row records the adopted generation as UNSYNCED.
            val scope = SaveReplicaScope(op.serverKey, op.userKey, op.romId, op.romHash, op.slot)
            val replica = store.findByScope(scope)!!
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.UNSYNCED)
            assertThat(replica.localHash).isEqualTo(sha256Hex(SAVE_BYTES))
            assertThat(replica.localSizeBytes).isEqualTo(SAVE_BYTES.size.toLong())
            assertThat(replica.coreId).isEqualTo("gambatte")
            assertThat(replica.localWrittenAtEpochMs).isEqualTo(op.localGenerationEpochMs)

            // (b) The scheduler's drain ran the executor: RUNNING → RETRYABLE_FAILURE → PENDING
            // with attempt 1, and the scheduler entered backoff (Waiting) via scheduleRetryAfter.
            awaitUntil(5_000) {
                val current = store.findById(opId)
                current?.attemptCount == 1 &&
                    current.status == PendingOperationStatus.PENDING &&
                    wired.coordinator.scheduler.currentState() is SchedulerState.Waiting
            }
            assertThat(store.findById(opId)!!.status).isEqualTo(PendingOperationStatus.PENDING)
            assertThat(wired.coordinator.scheduler.currentState()).isInstanceOf(SchedulerState.Waiting::class.java)
            assertThat(syncGateway.negotiateCalls).hasSize(1)

            wired.coordinator.scheduler.shutdown()
        } finally {
            wired.close()
        }
    }

    @Test
    fun `the scheduler drain completes an enqueued op end-to-end through the fake sync gateway`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        installGambatte(paths)
        val store = InMemorySaveStateStore()
        // Server negotiates NO_OP for this ROM/slot → the drain marks the replica SYNCED and the
        // op SUCCEEDED, completing the sync session with exact counters.
        val syncGateway = FakeRommSyncGateway().apply {
            negotiateResult = SyncNegotiateResult.Success(
                SyncNegotiateInfo(
                    sessionId = 55L,
                    operations = listOf(
                        SyncOperation(
                            action = SyncAction.NO_OP,
                            romId = ROM_ID,
                            saveId = null,
                            fileName = FILE_NAME,
                            slot = SavePathPolicy.AUTOSAVE_SLOT,
                            emulator = "gambatte",
                            reason = "",
                            serverUpdatedAt = null,
                            serverContentHash = "server-hash-1",
                        ),
                    ),
                ),
            )
        }
        val wired = wire(paths, store, syncGateway)
        try {
            signIn(wired.coordinator)

            playAndExit(wired)

            val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SavePathPolicy.AUTOSAVE_SLOT)
            val opId = awaitEnqueuedOp(store)

            // The full path: PENDING → RUNNING → SUCCEEDED via the scheduler's drain thread.
            awaitUntil(5_000) { store.findById(opId)?.status == PendingOperationStatus.SUCCEEDED }
            val drained = store.findById(opId)!!
            assertThat(drained.attemptCount).isEqualTo(1)
            assertThat(drained.lastError).isNull()

            // NO_OP action: replica settled as SYNCED with the server's agreed metadata.
            val replica = store.findByScope(scope)!!
            assertThat(replica.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(replica.serverHash).isEqualTo("server-hash-1")

            // The negotiate round trip carried this device's identity + the local client save state.
            val (negotiateOrigin, negotiateRequest) = syncGateway.negotiateCalls.single()
            assertThat(negotiateOrigin).isEqualTo(ORIGIN)
            assertThat(negotiateRequest.deviceId).isEqualTo("device-1")
            assertThat(negotiateRequest.saves).hasSize(1)
            val clientSave = negotiateRequest.saves.single()
            assertThat(clientSave.romId).isEqualTo(ROM_ID)
            assertThat(clientSave.emulator).isEqualTo("gambatte")
            assertThat(clientSave.contentHash).isEqualTo(sha256Hex(SAVE_BYTES))

            // Session completed with exact counters; the queue is empty so the scheduler drained to Idle.
            val (completeOrigin, completeSessionId, completeRequest) = syncGateway.completeSessionCalls.single()
            assertThat(completeOrigin).isEqualTo(ORIGIN)
            assertThat(completeSessionId).isEqualTo(55L)
            assertThat(completeRequest.operationsCompleted).isEqualTo(1)
            assertThat(completeRequest.operationsFailed).isEqualTo(0)
            awaitUntil(5_000) { wired.coordinator.scheduler.currentState() == SchedulerState.Idle }

            wired.coordinator.scheduler.shutdown()
        } finally {
            wired.close()
        }
    }

    @Test
    fun `no-content player exit does not enqueue a sync operation`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        val store = InMemorySaveStateStore()
        val wired = wire(paths, store, FakeRommSyncGateway())
        try {
            signIn(wired.coordinator)

            // No-content core (test_core): empty content path/hash — reconcile skips adoption
            // entirely, so there is nothing to bind a save to.
            val params = PlayerLaunchParams(
                coreId = "test_core",
                coreBuildRevision = "1",
                corePath = paths.dataDir.resolve("cores").resolve("libtest_core.so"),
                contentPath = null,
                contentHash = "",
                systemDir = paths.firmwareDir(),
                savePath = paths.dataDir.resolve("saves")
                    .resolve(SERVER_KEY).resolve(USERNAME)
                    .resolve(ROM_ID.toString()).resolve("nohash")
                    .resolve(SavePathPolicy.AUTOSAVE_SLOT).resolve("srm.srm"),
            )
            val sessionId = UUID.randomUUID().toString()
            val session = when (val prepared = wired.coordinator.playerSupervisor.prepareLaunch(params, sessionId)) {
                is PrepareLaunchResult.Ready -> prepared.session
                is PrepareLaunchResult.Failed -> error("expected Ready, got: ${prepared.reason}")
            }

            // The test_core still writes a scratch candidate and reports checkpointWritten — but
            // with no saveHash/saveSize verification data.
            Files.write(session.candidateSavePath, ByteArray(64))
            Files.writeString(
                session.resultPath,
                PlayerProtocol.serializeResult(
                    PlayerResult(
                        sessionId = sessionId,
                        exitKind = PlayerExitKind.COMPLETED,
                        checkpointWritten = true,
                        candidateSavePath = session.candidateSavePath.toString(),
                    ),
                ),
            )

            val report = wired.coordinator.onPlayerProcessExited(sessionId, exitCode = 0)

            assertThat(report).isInstanceOf(PlayerExitReport.Reconciled::class.java)
            assertThat((report as PlayerExitReport.Reconciled).adoption).isNull()
            // Nothing was enqueued in any status.
            for (status in enumValues<PendingOperationStatus>()) {
                assertThat(store.findByStatus(status)).withFailMessage("unexpected ops with status $status").isEmpty()
            }
        } finally {
            wired.close()
        }
    }

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)
}

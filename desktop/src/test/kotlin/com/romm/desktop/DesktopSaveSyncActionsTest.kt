package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.ServerSaveInfo
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.ports.SchedulerState
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.FakeSaveContentGateway
import com.romm.desktop.sync.FakeSaveSyncSessionReader
import com.romm.desktop.sync.SaveConflictChoice
import com.romm.desktop.sync.SaveConflictResolutionResult
import com.romm.desktop.sync.SaveConflictResolver
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Coordinator-level tests for the actionable saves UI (Phase 9 — the user-facing half of
 * "conflict preserves both copies"): [DesktopAppCoordinator.requestSaveSync] ("Sync now") kicks
 * the background drain, and [DesktopAppCoordinator.resolveSaveConflict] routes Keep-local /
 * Keep-server through [SaveConflictResolver].
 *
 * The coordinator runs over an in-memory [InMemorySaveStateStore] (saveStateStoreOverride) with a
 * fake-seamed drain executor AND resolver (no network, no filesystem beyond the temp SQLite db),
 * so the full wiring is exercised deterministically: UI action → coordinator method → scheduler /
 * resolver → durable store.
 */
@DisplayName("DesktopAppCoordinator — save-sync actions (sync now + conflict resolution)")
class DesktopSaveSyncActionsTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val FILE_NAME = "zelda.gb"
        const val ROM_HASH = "abc123hash"
        const val GEN = 1_700_000_000_000L

        val LOCAL_BYTES = "local-save-bytes".toByteArray()
        val SERVER_BYTES = "server-save-bytes".toByteArray()

        /** SavePathPolicy sanitizes '/' and '\' to '_' — the scope key for [ORIGIN]'s save path. */
        val SERVER_KEY: String = ORIGIN.map { if (it == '/' || it == '\\') '_' else it }.joinToString("")

        val SLOT = SavePathPolicy.AUTOSAVE_SLOT
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val store: InMemorySaveStateStore,
        val content: FakeSaveContentGateway,
        val sync: FakeRommSyncGateway,
    )

    /** Coordinator wired with fake-seamed drain executor + conflict resolver (no network). */
    private fun wire(paths: AppPaths): Wired {
        val store = InMemorySaveStateStore()
        val content = FakeSaveContentGateway()
        val sync = FakeRommSyncGateway()
        val sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USERNAME))
        val identityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", "device-1"))
        val c = DesktopAppCoordinator(
            paths = paths,
            secretBackend = FakeSecretBackend(),
            appVersion = "test",
            buildDefaultOrigin = ORIGIN,
            saveStateStoreOverride = store,
            saveSyncDrainExecutorOverride = SaveSyncDrainExecutor(
                pendingOperations = store,
                saveReplicas = store,
                content = content,
                sessionReader = sessionReader,
                deviceIdentityLoader = identityLoader,
                sync = sync,
            ),
            saveConflictResolverOverride = SaveConflictResolver(
                saveReplicas = store,
                content = content,
                sessionReader = sessionReader,
                deviceIdentityLoader = identityLoader,
                sync = sync,
            ),
        )
        return Wired(c, store, content, sync)
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

    private fun conflictReplica(status: SaveSyncStatus = SaveSyncStatus.CONFLICT) = SaveReplicaRecord(
        serverKey = SERVER_KEY, userKey = USERNAME, romId = ROM_ID, romHash = ROM_HASH, slot = SLOT,
        coreId = "gambatte", coreBuildRevision = "rev-1",
        localHash = sha256Hex(LOCAL_BYTES), localSizeBytes = LOCAL_BYTES.size.toLong(),
        localWrittenAtEpochMs = GEN,
        rommSaveId = 900L, serverHash = sha256Hex(SERVER_BYTES),
        syncStatus = status, lastError = "server-newer",
    )

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)

    // ── "Sync now" ──────────────────────────────────────────────────────────────

    @Test
    fun `sync now triggers a drain that settles an enqueued operation`(@TempDir dir: Path) {
        val wired = wire(dir.testRoot())
        try {
            signIn(wired.coordinator)
            // Settle the scheduler's startup drain (empty queue → Idle) so "Sync now" is the ONLY
            // drain that processes the operation seeded below.
            awaitUntil(5_000) { wired.coordinator.scheduler.currentState() == SchedulerState.Idle }

            // Seed the durable queue: one PENDING NEGOTIATE_AND_SYNC op + its UNSYNCED replica.
            // The server negotiates NO_OP → the drain marks the replica SYNCED and the op SUCCEEDED.
            wired.content.setLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, LOCAL_BYTES)
            wired.store.upsert(conflictReplica(status = SaveSyncStatus.UNSYNCED)).getOrThrow()
            val opId = wired.store.enqueue(
                PendingOperationRecord(
                    serverKey = SERVER_KEY, userKey = USERNAME, romId = ROM_ID, romHash = ROM_HASH, slot = SLOT,
                    operationType = PendingOperationType.NEGOTIATE_AND_SYNC,
                    localGenerationEpochMs = GEN, status = PendingOperationStatus.PENDING,
                    origin = null, negotiateFileName = FILE_NAME, negotiateCoreId = "gambatte",
                    negotiateCoreBuildRevision = "rev-1", createdAtEpochMs = GEN, updatedAtEpochMs = GEN,
                ),
            ).getOrThrow()
            wired.sync.negotiateResult = SyncNegotiateResult.Success(
                SyncNegotiateInfo(
                    sessionId = 55L,
                    operations = listOf(
                        SyncOperation(
                            action = SyncAction.NO_OP, romId = ROM_ID, saveId = null, fileName = FILE_NAME,
                            slot = SLOT, emulator = "gambatte", reason = "",
                            serverUpdatedAt = null, serverContentHash = "server-hash-1",
                        ),
                    ),
                ),
            )

            // The user clicks "Sync now" on the save-status line.
            assertThat(wired.coordinator.requestSaveSync()).isTrue()

            // The scheduler's worker thread drained the queue end to end.
            awaitUntil(5_000) { wired.store.findById(opId)?.status == PendingOperationStatus.SUCCEEDED }
            val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)
            assertThat(wired.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            awaitUntil(5_000) { wired.coordinator.scheduler.currentState() == SchedulerState.Idle }
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }

    // ── conflict resolution: keep local ─────────────────────────────────────────

    @Test
    fun `keep local uploads the local copy over the server and settles the replica`(@TempDir dir: Path) {
        val wired = wire(dir.testRoot())
        try {
            signIn(wired.coordinator)
            wired.content.setLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, LOCAL_BYTES)
            wired.store.upsert(conflictReplica()).getOrThrow()
            wired.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES) // losing-copy backup
            wired.sync.uploadResult = SaveUploadResult.Success(
                ServerSaveInfo(
                    saveId = 1000L, romId = ROM_ID, fileName = "autosave.srm", slot = SLOT,
                    emulator = "gambatte", contentHash = sha256Hex(LOCAL_BYTES),
                    updatedAt = null, fileSizeBytes = LOCAL_BYTES.size.toLong(),
                ),
            )

            val result = wired.coordinator.resolveSaveConflict(ROM_ID, keepLocal = true)

            assertThat(result).isInstanceOf(SaveConflictResolutionResult.Success::class.java)
            assertThat((result as SaveConflictResolutionResult.Success).choice).isEqualTo(SaveConflictChoice.KEEP_LOCAL)
            // The upload carried the LOCAL bytes with overwrite=true (local file wins).
            val (_, request) = wired.sync.uploadCalls.single()
            assertThat(request.overwrite).isTrue()
            assertThat(request.bytes).containsExactly(*LOCAL_BYTES)
            // The losing server copy was preserved; the replica settled SYNCED.
            assertThat(wired.content.conflictBackups.single().choice).isEqualTo("keep-local")
            val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)
            val rep = wired.store.findByScope(scope)!!
            assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(rep.rommSaveId).isEqualTo(1000L)
            assertThat(rep.localWrittenAtEpochMs).isGreaterThan(GEN) // stamped at resolution time
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }

    // ── conflict resolution: keep server ────────────────────────────────────────

    @Test
    fun `keep server downloads and adopts the server copy and marks the replica synced`(@TempDir dir: Path) {
        val wired = wire(dir.testRoot())
        try {
            signIn(wired.coordinator)
            wired.content.setLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, LOCAL_BYTES)
            wired.store.upsert(conflictReplica()).getOrThrow()
            wired.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

            val result = wired.coordinator.resolveSaveConflict(ROM_ID, keepLocal = false)

            assertThat(result).isInstanceOf(SaveConflictResolutionResult.Success::class.java)
            assertThat((result as SaveConflictResolutionResult.Success).choice).isEqualTo(SaveConflictChoice.KEEP_SERVER)
            // The server copy replaced the local bytes at the canonical save path; the losing local
            // copy was preserved first.
            assertThat(wired.content.readLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT))
                .containsExactly(*SERVER_BYTES)
            val backup = wired.content.conflictBackups.single()
            assertThat(backup.choice).isEqualTo("keep-server")
            assertThat(backup.bytes).containsExactly(*LOCAL_BYTES)
            // Re-hashed + settled SYNCED; the download was confirmed.
            val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)
            val rep = wired.store.findByScope(scope)!!
            assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
            assertThat(rep.localHash).isEqualTo(sha256Hex(SERVER_BYTES))
            assertThat(wired.sync.confirmCalls).hasSize(1)
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }

    // ── gating: resolution is offered only on CONFLICT ──────────────────────────

    @Test
    fun `resolution is rejected for non-conflict replicas and when no save exists`(@TempDir dir: Path) {
        val wired = wire(dir.testRoot())
        try {
            signIn(wired.coordinator)
            wired.content.setLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, LOCAL_BYTES)

            // A healthy (SYNCED) replica is never "resolved" — no network, no data change.
            wired.store.upsert(conflictReplica(status = SaveSyncStatus.SYNCED)).getOrThrow()
            val notConflict = wired.coordinator.resolveSaveConflict(ROM_ID, keepLocal = true)
            assertThat(notConflict).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
            assertThat((notConflict as SaveConflictResolutionResult.Failure).reason).startsWith("not-conflict")
            assertThat(wired.sync.uploadCalls).isEmpty()
            assertThat(wired.sync.downloadCalls).isEmpty()
            val scope = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)
            assertThat(wired.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)

            // A different ROM with no replica at all has nothing to resolve.
            val missing = wired.coordinator.resolveSaveConflict(99L, keepLocal = true)
            assertThat(missing).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
            assertThat((missing as SaveConflictResolutionResult.Failure).reason)
                .startsWith("no save recorded")

            // Without a coherent session there is no scope to resolve against (separate root so the
            // two coordinators do not share one SQLite file).
            val noSessionCoordinator = wire(dir.resolve("no-session-root").testRoot()).coordinator
            val noSession = noSessionCoordinator.resolveSaveConflict(ROM_ID, keepLocal = true)
            assertThat(noSession).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
            assertThat((noSession as SaveConflictResolutionResult.Failure).reason)
                .startsWith("no active session")
            noSessionCoordinator.scheduler.shutdown()
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }
}

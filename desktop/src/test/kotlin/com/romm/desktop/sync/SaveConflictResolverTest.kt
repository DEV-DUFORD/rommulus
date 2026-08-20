package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.ServerSaveInfo
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Unit tests for [SaveConflictResolver] — the desktop port of Android's `ConflictResolverImpl`
 * (the user-facing half of "conflict preserves both copies"). Runs entirely on fakes:
 * InMemorySaveStateStore, FakeSaveContentGateway, scripted FakeRommSyncGateway — no server.
 */
class SaveConflictResolverTest {

    // ── fixed scenario constants ────────────────────────────────────────────────
    private companion object {
        const val SRV = "srv"
        const val USER = "alice"
        const val ROM_ID = 42L
        const val HASH = "abc123hash"
        const val SLOT = "autosave"
        const val ORIGIN = "https://romm.test"
        const val DEVICE_ID = "device-1"

        val NOW = 1_700_000_000_000L
        val GEN = NOW - 60_000
        val LOCAL_BYTES = "local-save-bytes".toByteArray()
        val SERVER_BYTES = "server-save-bytes".toByteArray()
    }

    private val scope = SaveReplicaScope(SRV, USER, ROM_ID, HASH, SLOT)

    // ── builders ────────────────────────────────────────────────────────────────

    private fun conflictReplica(
        syncStatus: SaveSyncStatus = SaveSyncStatus.CONFLICT,
        rommSaveId: Long? = 900L,
        serverHash: String? = sha256Hex(SERVER_BYTES),
        expectedSize: Long? = null,
    ) = SaveReplicaRecord(
        serverKey = SRV, userKey = USER, romId = ROM_ID, romHash = HASH, slot = SLOT,
        coreId = "snes9x", coreBuildRevision = "rev-42",
        expectedSramSizeBytes = expectedSize,
        localHash = sha256Hex(LOCAL_BYTES), localSizeBytes = LOCAL_BYTES.size.toLong(),
        localWrittenAtEpochMs = GEN,
        rommSaveId = rommSaveId, serverHash = serverHash,
        syncStatus = syncStatus, lastError = "server-newer",
    )

    private fun uploadedSave(saveId: Long = 1000L) = ServerSaveInfo(
        saveId = saveId, romId = ROM_ID, fileName = "autosave.srm", slot = SLOT, emulator = "snes9x",
        contentHash = sha256Hex(LOCAL_BYTES), updatedAt = Instant.ofEpochMilli(NOW + 5),
        fileSizeBytes = LOCAL_BYTES.size.toLong(),
    )

    private class Harness(
        val store: InMemorySaveStateStore = InMemorySaveStateStore(),
        val content: FakeSaveContentGateway = FakeSaveContentGateway(),
        val sync: FakeRommSyncGateway = FakeRommSyncGateway(),
    ) {
        val resolver = SaveConflictResolver(
            saveReplicas = store,
            content = content,
            sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USER)),
            deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", DEVICE_ID)),
            sync = sync,
            clock = { NOW },
        )

        fun seedConflict(replica: SaveReplicaRecord) {
            store.upsert(replica).getOrThrow()
            content.setLocal(SRV, USER, ROM_ID, HASH, SLOT, LOCAL_BYTES)
        }
    }

    // ── KEEP LOCAL ──────────────────────────────────────────────────────────────

    @Test
    fun `keep local uploads local over the server and backs up the losing server copy`() {
        val h = Harness()
        h.seedConflict(conflictReplica())
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES) // losing-copy backup read
        h.sync.uploadResult = SaveUploadResult.Success(uploadedSave())

        val result = h.resolver.resolve(conflictReplica(), keepLocal = true)

        assertThat(result).isEqualTo(
            SaveConflictResolutionResult.Success(
                choice = SaveConflictChoice.KEEP_LOCAL,
                serverBackupPath = "conflict-backups/keep-local-${sha256Hex(SERVER_BYTES).take(16)}.srm",
            ),
        )
        // Local bytes were uploaded over the server with overwrite=true (the user's explicit choice).
        val (origin, request) = h.sync.uploadCalls.single()
        assertThat(origin).isEqualTo(ORIGIN)
        assertThat(request.overwrite).isTrue()
        assertThat(request.bytes).containsExactly(*LOCAL_BYTES)
        assertThat(request.romId).isEqualTo(ROM_ID)
        assertThat(request.slot).isEqualTo(SLOT)
        assertThat(request.emulator).isEqualTo("snes9x")
        assertThat(request.deviceId).isEqualTo(DEVICE_ID)
        // The losing server copy was durably preserved BEFORE the overwrite.
        val backup = h.content.conflictBackups.single()
        assertThat(backup.choice).isEqualTo("keep-local")
        assertThat(backup.bytes).containsExactly(*SERVER_BYTES)
        // Replica settled SYNCED at resolution time with the upload's server metadata; local bytes intact.
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.lastError).isNull()
        assertThat(rep.rommSaveId).isEqualTo(1000L)
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(NOW)
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*LOCAL_BYTES)
    }

    @Test
    fun `keep local upload failure preserves both copies and leaves the conflict in place`() {
        val h = Harness()
        h.seedConflict(conflictReplica())
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)
        h.sync.uploadResult = SaveUploadResult.Failure(RommApiError.NETWORK_ERROR, 503)

        val result = h.resolver.resolve(conflictReplica(), keepLocal = true)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
        assertThat((result as SaveConflictResolutionResult.Failure).httpCode).isEqualTo(503)
        // No silent data loss: local bytes untouched, server copy backed up, replica still CONFLICT.
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(GEN)
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*LOCAL_BYTES)
        assertThat(h.content.conflictBackups.single().choice).isEqualTo("keep-local")
    }

    @Test
    fun `keep local without an identifiable server save aborts without uploading or touching local`() {
        val h = Harness()
        h.seedConflict(conflictReplica(rommSaveId = null, serverHash = null))
        // No rommSaveId and listSaves fails (the fake's default) → the losing server copy cannot
        // be identified. Mirrors Android: never overwrite=true without a durable backup of the
        // losing copy — abort instead of silently destroying it.

        val result = h.resolver.resolve(conflictReplica(rommSaveId = null, serverHash = null), keepLocal = true)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
        assertThat((result as SaveConflictResolutionResult.Failure).reason)
            .startsWith("server-save-unidentified")
        // No silent data loss: no upload (the losing server copy is untouched on the server),
        // no backup written, local bytes intact, replica still CONFLICT.
        assertThat(h.sync.uploadCalls).isEmpty()
        assertThat(h.content.conflictBackups).isEmpty()
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*LOCAL_BYTES)
        assertThat(h.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
    }

    // ── KEEP SERVER ─────────────────────────────────────────────────────────────

    @Test
    fun `keep server downloads adopts the server copy and marks the replica synced`() {
        val h = Harness()
        h.seedConflict(conflictReplica(expectedSize = SERVER_BYTES.size.toLong()))
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)
        h.sync.confirmResult = SaveConfirmResult.Success

        val result = h.resolver.resolve(conflictReplica(expectedSize = SERVER_BYTES.size.toLong()), keepLocal = false)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Success::class.java)
        val success = result as SaveConflictResolutionResult.Success
        assertThat(success.choice).isEqualTo(SaveConflictChoice.KEEP_SERVER)
        assertThat(success.localBackupPath).isNotNull()
        // The server copy was adopted atomically (the fake gateway's file IS the canonical path).
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*SERVER_BYTES)
        // The losing local copy was durably preserved BEFORE replacement.
        val backup = h.content.conflictBackups.single()
        assertThat(backup.choice).isEqualTo("keep-server")
        assertThat(backup.bytes).containsExactly(*LOCAL_BYTES)
        // Replica re-hashed to the adopted bytes and settled SYNCED at resolution time.
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.lastError).isNull()
        assertThat(rep.localHash).isEqualTo(sha256Hex(SERVER_BYTES))
        assertThat(rep.localSizeBytes).isEqualTo(SERVER_BYTES.size.toLong())
        assertThat(rep.localWrittenAtEpochMs).isEqualTo(NOW)
        assertThat(rep.rommSaveId).isEqualTo(900L)
        // The download was confirmed (idempotent bookkeeping).
        assertThat(h.sync.confirmCalls).containsExactly(Triple(ORIGIN, 900L, DEVICE_ID))
    }

    @Test
    fun `keep server with a hash mismatch rejects without touching the local copy`() {
        val h = Harness()
        // Recorded conflict-time server hash does not match what actually downloads.
        h.seedConflict(conflictReplica(serverHash = "deadbeef"))
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val result = h.resolver.resolve(conflictReplica(serverHash = "deadbeef"), keepLocal = false)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
        assertThat((result as SaveConflictResolutionResult.Failure).reason)
            .startsWith("server-hash-mismatch")
        // Local bytes untouched, no adoption, no confirm, replica still CONFLICT.
        assertThat(h.content.readLocal(SRV, USER, ROM_ID, HASH, SLOT)).containsExactly(*LOCAL_BYTES)
        assertThat(h.content.conflictBackups).isEmpty()
        assertThat(h.sync.confirmCalls).isEmpty()
        assertThat(h.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
    }

    @Test
    fun `keep server falls back to listing saves when no server save id is recorded`() {
        val h = Harness()
        h.seedConflict(conflictReplica(rommSaveId = null))
        h.sync.listSavesResult = SaveListResult.Success(
            listOf(
                ServerSaveInfo(
                    saveId = 555L, romId = ROM_ID, fileName = "autosave.srm", slot = SLOT,
                    emulator = "snes9x", contentHash = sha256Hex(SERVER_BYTES),
                    updatedAt = Instant.ofEpochMilli(NOW - 1000), fileSizeBytes = SERVER_BYTES.size.toLong(),
                ),
            ),
        )
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val result = h.resolver.resolve(conflictReplica(rommSaveId = null), keepLocal = false)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Success::class.java)
        // The listed save id was downloaded and adopted.
        assertThat(h.sync.downloadCalls.map { it.saveId }).containsExactly(555L)
        val rep = h.store.findByScope(scope)!!
        assertThat(rep.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(rep.rommSaveId).isEqualTo(555L)
    }

    // ── gating: resolution is offered only on CONFLICT ──────────────────────────

    @Test
    fun `resolution of a non-conflict replica is rejected without touching any data`() {
        val h = Harness()
        h.store.upsert(conflictReplica(syncStatus = SaveSyncStatus.SYNCED)).getOrThrow()
        h.content.setLocal(SRV, USER, ROM_ID, HASH, SLOT, LOCAL_BYTES)

        val result = h.resolver.resolve(conflictReplica(syncStatus = SaveSyncStatus.SYNCED), keepLocal = true)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
        assertThat((result as SaveConflictResolutionResult.Failure).reason).startsWith("not-conflict")
        // No network call, no backup, no status change — a healthy replica is never "resolved".
        assertThat(h.sync.uploadCalls).isEmpty()
        assertThat(h.sync.downloadCalls).isEmpty()
        assertThat(h.sync.confirmCalls).isEmpty()
        assertThat(h.content.conflictBackups).isEmpty()
        assertThat(h.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
    }

    @Test
    fun `resolution without an active session fails before any network call`() {
        val h = Harness()
        h.seedConflict(conflictReplica())
        // Rebuild the resolver with a null session (logged out / kiosk).
        val noSessionResolver = SaveConflictResolver(
            saveReplicas = h.store,
            content = h.content,
            sessionReader = FakeSaveSyncSessionReader(null),
            deviceIdentityLoader = FakeDeviceIdentityLoader(DeviceIdentity("install-1", DEVICE_ID)),
            sync = h.sync,
        )

        val result = noSessionResolver.resolve(conflictReplica(), keepLocal = true)

        assertThat(result).isInstanceOf(SaveConflictResolutionResult.Failure::class.java)
        assertThat((result as SaveConflictResolutionResult.Failure).reason).startsWith("no active session")
        assertThat(h.sync.uploadCalls).isEmpty()
        assertThat(h.store.findByScope(scope)!!.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
    }
}

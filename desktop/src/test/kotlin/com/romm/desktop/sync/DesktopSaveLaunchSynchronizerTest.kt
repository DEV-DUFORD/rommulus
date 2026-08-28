package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.emulation.model.sha256Hex
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.DeviceRegistrationResult
import com.romm.androidtv.romm.RommApiError
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SyncAction
import com.romm.androidtv.romm.SyncNegotiateInfo
import com.romm.androidtv.romm.SyncNegotiateResult
import com.romm.androidtv.romm.SyncOperation
import com.romm.androidtv.romm.save.SaveSyncOutcome
import com.romm.androidtv.romm.save.SaveSyncRequest
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Desktop pre-launch save synchronization")
class DesktopSaveLaunchSynchronizerTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val ROM_HASH = "rom-hash"
        const val CORE_ID = "gambatte"
        const val CORE_REVISION = "v1"
        const val SESSION_ID = 55L
        const val SAVE_ID = 99L
        const val NOW = 1_700_000_000_000L
        val LOCAL_BYTES = "local-save".toByteArray()
        val SERVER_BYTES = "cloud-save".toByteArray()
        val SERVER_KEY = SavePathPolicy.sanitizeSegment(ORIGIN)
        val SLOT = SavePathPolicy.AUTOSAVE_SLOT
    }

    private data class Harness(
        val store: InMemorySaveStateStore,
        val content: FakeSaveContentGateway,
        val sync: FakeRommSyncGateway,
        val synchronizer: DesktopSaveLaunchSynchronizer,
        val queuedCount: () -> Int,
    )

    private fun harness(
        registration: DeviceRegistrationResult = DeviceRegistrationResult.Success(
            DeviceIdentity("install-1", "device-1"),
            alreadyExisted = true,
        ),
    ): Harness {
        val store = InMemorySaveStateStore()
        val content = FakeSaveContentGateway()
        val sync = FakeRommSyncGateway()
        var queued = 0
        val synchronizer = DesktopSaveLaunchSynchronizer(
            saveState = store,
            content = content,
            sessionReader = FakeSaveSyncSessionReader(SaveSyncSession(ORIGIN, USERNAME)),
            deviceIdentityLoader = PreLaunchDeviceIdentityLoader { _, _ -> registration },
            sync = sync,
            clock = { NOW },
            onOperationQueued = { queued += 1 },
        )
        return Harness(store, content, sync, synchronizer) { queued }
    }

    private fun request(expectedSize: Long? = SERVER_BYTES.size.toLong()) = SaveSyncRequest(
        romId = ROM_ID,
        romHash = ROM_HASH,
        coreId = CORE_ID,
        coreBuildRevision = CORE_REVISION,
        expectedSramSizeBytes = expectedSize,
        fileName = "zelda.gb",
    )

    private fun operation(
        action: SyncAction,
        saveId: Long? = SAVE_ID,
        emulator: String? = CORE_ID,
        reason: String = "",
    ) = SyncOperation(
        action = action,
        romId = ROM_ID,
        saveId = saveId,
        fileName = "autosave.srm",
        slot = SLOT,
        emulator = emulator,
        reason = reason,
        serverUpdatedAt = null,
        serverContentHash = sha256Hex(SERVER_BYTES),
    )

    private fun FakeRommSyncGateway.negotiate(operation: SyncOperation) {
        negotiateResult = SyncNegotiateResult.Success(
            SyncNegotiateInfo(SESSION_ID, listOf(operation)),
        )
    }

    private fun Harness.seedLocal(status: SaveSyncStatus = SaveSyncStatus.SYNCED) {
        content.setLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, LOCAL_BYTES)
        store.upsert(
            SaveReplicaRecord(
                serverKey = SERVER_KEY,
                userKey = USERNAME,
                romId = ROM_ID,
                romHash = ROM_HASH,
                slot = SLOT,
                coreId = CORE_ID,
                coreBuildRevision = CORE_REVISION,
                expectedSramSizeBytes = LOCAL_BYTES.size.toLong(),
                localHash = sha256Hex(LOCAL_BYTES),
                localSizeBytes = LOCAL_BYTES.size.toLong(),
                localWrittenAtEpochMs = NOW - 1_000,
                syncStatus = status,
            ),
        ).getOrThrow()
    }

    private fun scope() = SaveReplicaScope(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT)

    @Test
    fun `latest server save is downloaded and adopted before launch`() {
        val h = harness()
        h.sync.negotiate(operation(SyncAction.DOWNLOAD))
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val outcome = h.synchronizer.syncBeforeLaunch(request())

        assertThat(outcome).isEqualTo(
            SaveSyncOutcome.Downloaded(SESSION_ID, SAVE_ID, SERVER_BYTES.size.toLong(), confirmed = true),
        )
        assertThat(h.content.readLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT))
            .containsExactly(*SERVER_BYTES)
        assertThat(h.sync.downloadCalls.single().sessionId).isEqualTo(SESSION_ID)
        assertThat(h.sync.confirmCalls).hasSize(1)
        assertThat(h.store.findByScope(scope())?.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
    }

    @Test
    fun `conflict blocks automatic replacement and preserves the local copy`() {
        val h = harness()
        h.seedLocal()
        h.sync.negotiate(operation(SyncAction.CONFLICT, reason = "both changed"))

        val outcome = h.synchronizer.syncBeforeLaunch(request(LOCAL_BYTES.size.toLong()))

        assertThat(outcome).isInstanceOf(SaveSyncOutcome.ConflictRequiresResolution::class.java)
        assertThat(h.content.readLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT))
            .containsExactly(*LOCAL_BYTES)
        assertThat(h.store.findByScope(scope())?.syncStatus).isEqualTo(SaveSyncStatus.CONFLICT)
        assertThat(h.sync.downloadCalls).isEmpty()
    }

    @Test
    fun `size mismatch is quarantined without confirming or replacing local bytes`() {
        val h = harness()
        h.seedLocal()
        h.sync.negotiate(operation(SyncAction.DOWNLOAD))
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val outcome = h.synchronizer.syncBeforeLaunch(request(expectedSize = SERVER_BYTES.size + 1L))

        assertThat(outcome).isInstanceOf(SaveSyncOutcome.Quarantined::class.java)
        assertThat((outcome as SaveSyncOutcome.Quarantined).reason).isEqualTo("size-mismatch")
        assertThat(h.content.quarantined.single().third).containsExactly(*SERVER_BYTES)
        assertThat(h.content.readLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT))
            .containsExactly(*LOCAL_BYTES)
        assertThat(h.sync.confirmCalls).isEmpty()
        assertThat(h.store.findByScope(scope())?.syncStatus).isEqualTo(SaveSyncStatus.QUARANTINED)
    }

    @Test
    fun `unknown SRAM size stages trusted server save for player core validation`() {
        val h = harness()
        h.sync.negotiate(operation(SyncAction.DOWNLOAD))
        h.sync.downloadResult = SaveDownloadResult.Success(SERVER_BYTES)

        val outcome = h.synchronizer.syncBeforeLaunch(request(expectedSize = null))

        assertThat(outcome).isInstanceOf(SaveSyncOutcome.AwaitingCoreValidation::class.java)
        val awaiting = outcome as SaveSyncOutcome.AwaitingCoreValidation
        assertThat(awaiting.sessionId).isEqualTo(SESSION_ID)
        assertThat(awaiting.rommSaveId).isEqualTo(SAVE_ID)
        assertThat(awaiting.quarantinedPath).isNotBlank()
        assertThat(awaiting.downloadedSizeBytes).isEqualTo(SERVER_BYTES.size.toLong())
        assertThat(awaiting.serverContentHash).isEqualTo(sha256Hex(SERVER_BYTES))
        assertThat(awaiting.emulator).isEqualTo(CORE_ID)
        assertThat(h.content.readLocal(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT))
            .containsExactly(*SERVER_BYTES)
        val replica = h.store.findByScope(scope())
        assertThat(replica?.syncStatus).isEqualTo(SaveSyncStatus.AWAITING_CORE_VALIDATION)
        assertThat(replica?.localHash).isNull()
        assertThat(replica?.localSizeBytes).isNull()
        assertThat(h.sync.confirmCalls).isEmpty()
    }

    @Test
    fun `transient negotiate failure launches offline only with a durable local save`() {
        val h = harness()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.NETWORK_ERROR)

        val withLocal = h.synchronizer.syncBeforeLaunch(request(LOCAL_BYTES.size.toLong()))

        assertThat(withLocal).isEqualTo(
            SaveSyncOutcome.PlayOfflineLocal(RommApiError.NETWORK_ERROR),
        )

        val empty = harness()
        empty.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.NETWORK_ERROR)
        assertThat(empty.synchronizer.syncBeforeLaunch(request()))
            .isEqualTo(SaveSyncOutcome.Failure(RommApiError.NETWORK_ERROR))
    }

    @Test
    fun `auth expiry remains distinct from offline fallback`() {
        val h = harness()
        h.seedLocal()
        h.sync.negotiateResult = SyncNegotiateResult.Failure(RommApiError.AUTH_EXPIRED, 401)

        assertThat(h.synchronizer.syncBeforeLaunch(request(LOCAL_BYTES.size.toLong())))
            .isEqualTo(SaveSyncOutcome.Failure(RommApiError.AUTH_EXPIRED, 401))
    }

    @Test
    fun `transient device registration failure also falls back to a durable local save`() {
        val h = harness(DeviceRegistrationResult.Failure(RommApiError.NETWORK_ERROR))
        h.seedLocal()

        assertThat(h.synchronizer.syncBeforeLaunch(request(LOCAL_BYTES.size.toLong())))
            .isEqualTo(SaveSyncOutcome.PlayOfflineLocal(RommApiError.NETWORK_ERROR))
        assertThat(h.sync.negotiateCalls).isEmpty()
    }

    @Test
    fun `local-newer result queues one durable upload and kicks the drain`() {
        val h = harness()
        h.seedLocal(status = SaveSyncStatus.UNSYNCED)
        h.sync.negotiate(operation(SyncAction.UPLOAD))

        val outcome = h.synchronizer.syncBeforeLaunch(request(LOCAL_BYTES.size.toLong()))

        assertThat(outcome).isInstanceOf(SaveSyncOutcome.UploadQueued::class.java)
        val pending = h.store.findByStatus(PendingOperationStatus.PENDING).single()
        assertThat(pending.operationType).isEqualTo(PendingOperationType.UPLOAD)
        assertThat(pending.origin).isEqualTo(ORIGIN)
        assertThat(pending.uploadFileName).isEqualTo("zelda.gb")
        assertThat(pending.sessionId).isEqualTo(SESSION_ID)
        assertThat(h.store.findByScope(scope())?.syncStatus).isEqualTo(SaveSyncStatus.PENDING_UPLOAD)
        assertThat(h.queuedCount()).isEqualTo(1)
    }
}

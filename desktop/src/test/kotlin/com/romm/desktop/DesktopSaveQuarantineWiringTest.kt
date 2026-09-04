package com.romm.desktop

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.ports.SettingsKeys
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import com.romm.desktop.storage.secret.FakeSecretBackend
import com.romm.desktop.sync.FakeDeviceIdentityLoader
import com.romm.desktop.sync.FakeRommSyncGateway
import com.romm.desktop.sync.FakeSaveContentGateway
import com.romm.desktop.sync.FakeSaveSyncSessionReader
import com.romm.desktop.sync.FileSaveContentGateway
import com.romm.desktop.sync.SaveSyncDrainExecutor
import com.romm.desktop.sync.SaveSyncSession
import com.romm.desktop.ui.screens.detail.SaveSyncUiActions
import com.romm.desktop.ui.screens.detail.saveSyncUiActions
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Coordinator-level tests for the quarantine view wiring (Phase 9, sub-unit F2): the
 * [DesktopAppCoordinator]'s save-status presenter is rooted at the same data dir as
 * [FileSaveContentGateway], so "View quarantine" on the save-status line resolves exactly where
 * preserved copies live — and a QUARANTINED replica offers ONLY that action (never "Sync now").
 *
 * The coordinator runs over an in-memory [InMemorySaveStateStore] (saveStateStoreOverride) with a
 * fake-seamed drain executor (no network); quarantine files are written by the REAL
 * [FileSaveContentGateway] so the presenter's dir derivation is proven against production layout.
 */
@DisplayName("DesktopAppCoordinator — quarantine view wiring (F2)")
class DesktopSaveQuarantineWiringTest {

    private companion object {
        const val ORIGIN = "https://demo.romm.app"
        const val USERNAME = "zack"
        const val ROM_ID = 7L
        const val ROM_HASH = "abc123hash"
        const val GEN = 1_700_000_000_000L

        val SERVER_KEY: String = SavePathPolicy.sanitizeSegment(ORIGIN)

        val SLOT = SavePathPolicy.AUTOSAVE_SLOT
        val SERVER_BYTES = "server-save-bytes".toByteArray()
    }

    private data class Wired(
        val coordinator: DesktopAppCoordinator,
        val store: InMemorySaveStateStore,
    )

    /** Coordinator wired with a fake-seamed drain executor (no network) over the temp root. */
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
        )
        return Wired(c, store)
    }

    /** Gives the coordinator a coherent (non-kiosk) session for [ORIGIN] + [USERNAME]. */
    private fun signIn(c: DesktopAppCoordinator) {
        c.settingsStore.write(mapOf(SettingsKeys.ORIGIN to ORIGIN))
        check(c.sessionStorage.save(ORIGIN, USERNAME, 123L, kioskMode = false))
    }

    private fun quarantinedReplica() = SaveReplicaRecord(
        serverKey = SERVER_KEY, userKey = USERNAME, romId = ROM_ID, romHash = ROM_HASH, slot = SLOT,
        coreId = "gambatte", coreBuildRevision = "rev-1",
        localSizeBytes = SERVER_BYTES.size.toLong(),
        localWrittenAtEpochMs = GEN,
        rommSaveId = 900L,
        syncStatus = SaveSyncStatus.QUARANTINED,
        lastError = "quarantined: size-mismatch (post-play)",
    )

    private fun Path.testRoot(): AppPaths = TestAppPaths(this)

    @Test
    fun `view quarantine resolves the preserved copy written by the production gateway`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        val wired = wire(paths)
        try {
            signIn(wired.coordinator)
            wired.store.upsert(quarantinedReplica()).getOrThrow()

            // The quarantine file is written by the PRODUCTION gateway (the same one the drain and
            // conflict resolver use) — proving the presenter's dir derivation matches it.
            val gateway = FileSaveContentGateway(paths.dataDir.toFile())
            val preservedPath = gateway.quarantine(
                SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, SERVER_BYTES, "size-mismatch", GEN,
            )

            val presenter = wired.coordinator.saveSyncStatusPresenter()
            presenter.refresh(ROM_ID)

            // The status line offers ONLY "View quarantine" for this state — never "Sync now".
            assertThat(presenter.uiState.value)
                .isEqualTo(com.romm.desktop.ui.screens.detail.SaveSyncUiState.Replica(
                    SaveSyncStatus.QUARANTINED, "quarantined: size-mismatch (post-play)",
                ))
            assertThat(saveSyncUiActions(presenter.uiState.value))
                .isEqualTo(SaveSyncUiActions(canSyncNow = false, canResolveConflict = false, canViewQuarantine = true))

            // The dialog's read-only model resolves the metadata + the exact stored path.
            val model = presenter.quarantineView(ROM_ID)
                ?: throw AssertionError("expected a quarantine view for the QUARANTINED replica")
            assertThat(model.reason).isEqualTo("size-mismatch")
            assertThat(model.description).contains("SRAM size")
            assertThat(model.fileName).isEqualTo("autosave.srm")
            assertThat(model.saveId).isEqualTo(900L)
            assertThat(model.coreId).isEqualTo("gambatte")
            assertThat(model.slot).isEqualTo(SLOT)
            assertThat(model.romId).isEqualTo(ROM_ID)
            assertThat(model.quarantinedPath).isEqualTo(preservedPath)
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }

    @Test
    fun `quarantine view is unavailable without a session and for healthy replicas`(@TempDir dir: Path) {
        val paths = dir.testRoot()
        val wired = wire(paths)
        try {
            // No coherent session yet — no scope to scan, even with a QUARANTINED replica stored.
            wired.store.upsert(quarantinedReplica()).getOrThrow()
            assertThat(wired.coordinator.saveSyncStatusPresenter().quarantineView(ROM_ID)).isNull()

            signIn(wired.coordinator)

            // A healthy (SYNCED) replica has nothing quarantined to view — even when a quarantine
            // file from an earlier generation sits on disk.
            wired.store.upsert(quarantinedReplica().copy(syncStatus = SaveSyncStatus.SYNCED, lastError = null)).getOrThrow()
            val gateway = FileSaveContentGateway(paths.dataDir.toFile())
            gateway.quarantine(SERVER_KEY, USERNAME, ROM_ID, ROM_HASH, SLOT, SERVER_BYTES, "size-mismatch", GEN)
            assertThat(wired.coordinator.saveSyncStatusPresenter().quarantineView(ROM_ID)).isNull()
        } finally {
            wired.coordinator.scheduler.shutdown()
        }
    }
}

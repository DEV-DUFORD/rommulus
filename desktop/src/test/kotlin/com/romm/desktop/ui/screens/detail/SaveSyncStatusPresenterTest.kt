package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.io.File
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Focused tests for the read-only save-sync status indicator (first piece of the Linux saves UI):
 * each [SaveSyncStatus] → user-facing label, and the [SaveSyncStatusPresenter]'s mapping of store
 * contents to [SaveSyncUiState] over an in-memory [InMemorySaveStateStore].
 */
@DisplayName("SaveSyncStatusPresenter — save-status mapping")
class SaveSyncStatusPresenterTest {

    private companion object {
        /** Sanitized form of https://demo.romm.app (each '/' → '_'), as replicas persist it. */
        const val SERVER_KEY = "https:__demo.romm.app"
        const val USER_KEY = "zack"
        const val ROM_ID = 7L
        const val ROM_HASH = "abc123"

        fun replica(
            status: SaveSyncStatus,
            lastError: String? = null,
            romId: Long = ROM_ID,
            romHash: String = ROM_HASH,
            slot: String = SavePathPolicy.AUTOSAVE_SLOT,
            writtenAtEpochMs: Long = 1_000L,
            rommSaveId: Long? = null,
            localSizeBytes: Long? = null,
        ) = SaveReplicaRecord(
            serverKey = SERVER_KEY,
            userKey = USER_KEY,
            romId = romId,
            romHash = romHash,
            slot = slot,
            coreId = "gambatte",
            coreBuildRevision = "v1",
            localSizeBytes = localSizeBytes,
            localWrittenAtEpochMs = writtenAtEpochMs,
            rommSaveId = rommSaveId,
            syncStatus = status,
            lastError = lastError,
        )

        fun presenter(store: InMemorySaveStateStore) = SaveSyncStatusPresenter(
            store = store,
            sessionKeysProvider = { SERVER_KEY to USER_KEY },
        )
    }

    @Test
    fun `each sync status maps to its user-facing label`() {
        val expected = mapOf(
            SaveSyncStatus.SYNCED to "Save: synced",
            SaveSyncStatus.PENDING_UPLOAD to "Save: pending upload",
            SaveSyncStatus.PENDING_DOWNLOAD to "Save: pending download",
            SaveSyncStatus.CONFLICT to "Save: conflict — needs resolution",
            SaveSyncStatus.QUARANTINED to "Save: quarantined",
            SaveSyncStatus.UNSYNCED to "Save: unsynced",
            SaveSyncStatus.AWAITING_CORE_VALIDATION to "Save: awaiting core validation",
        )
        expected.forEach { (status, label) ->
            // AssertJ's Java `.as(String)` idiom does not parse in Kotlin (`as` is a hard keyword).
            assertThat(saveStatusLabel(SaveSyncUiState.Replica(status, null)))
                .withFailMessage("label for $status")
                .isEqualTo(label)
        }
    }

    @Test
    fun `no save maps to the none label`() {
        assertThat(saveStatusLabel(SaveSyncUiState.NoSave)).isEqualTo("Save: none")
    }

    @Test
    fun `no save offers no actions`() {
        assertThat(saveSyncUiActions(SaveSyncUiState.NoSave))
            .isEqualTo(SaveSyncUiActions(canSyncNow = false, canResolveConflict = false))
    }

    @Test
    fun `conflict offers only keep-local and keep-server — never sync now`() {
        assertThat(saveSyncUiActions(SaveSyncUiState.Replica(SaveSyncStatus.CONFLICT, null)))
            .isEqualTo(SaveSyncUiActions(canSyncNow = false, canResolveConflict = true))
    }

    @Test
    fun `every non-conflict non-quarantined replica status offers sync now and never conflict resolution`() {
        SaveSyncStatus.entries
            .filter { it != SaveSyncStatus.CONFLICT && it != SaveSyncStatus.QUARANTINED }
            .forEach { status ->
                assertThat(saveSyncUiActions(SaveSyncUiState.Replica(status, null)))
                    .withFailMessage("actions for $status")
                    .isEqualTo(SaveSyncUiActions(canSyncNow = true, canResolveConflict = false))
            }
    }

    @Test
    fun `quarantined offers only view quarantine — never sync now or conflict resolution`() {
        // A quarantined save needs an explicit compatibility/import decision (Android treats it as
        // needing explicit action) — the status line must NOT offer "Sync now" (auto-redrain could
        // undo the user's quarantine choice), and there is nothing to keep-local/keep-server.
        assertThat(saveSyncUiActions(SaveSyncUiState.Replica(SaveSyncStatus.QUARANTINED, "quarantined: size-mismatch")))
            .isEqualTo(SaveSyncUiActions(canSyncNow = false, canResolveConflict = false, canViewQuarantine = true))
    }

    @Test
    fun `refresh with no replica yields NoSave`() {
        val presenter = presenter(InMemorySaveStateStore())
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)
    }

    @Test
    fun `no session keys (kiosk or blank origin) yields NoSave even when a replica exists`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED)).getOrThrow()
        val presenter = SaveSyncStatusPresenter(store, sessionKeysProvider = { null })
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)
    }

    @Test
    fun `replica for the rom yields its sync status and last error`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.CONFLICT, "negotiate rejected: 409")).getOrThrow()
        val presenter = presenter(store)
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value)
            .isEqualTo(SaveSyncUiState.Replica(SaveSyncStatus.CONFLICT, "negotiate rejected: 409"))
    }

    @Test
    fun `replicas for another rom or another slot are ignored`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED, romId = 99L)).getOrThrow()
        store.upsert(replica(SaveSyncStatus.SYNCED, slot = "slot1")).getOrThrow()
        val presenter = presenter(store)
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)
    }

    @Test
    fun `replicas for another session are ignored`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED).copy(serverKey = "other", userKey = "someone")).getOrThrow()
        val presenter = presenter(store)
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)
    }

    @Test
    fun `multiple hash generations (re-uploaded rom) show the newest local write`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED, romHash = "old-hash", writtenAtEpochMs = 1_000L)).getOrThrow()
        store.upsert(replica(SaveSyncStatus.PENDING_UPLOAD, romHash = "new-hash", writtenAtEpochMs = 2_000L)).getOrThrow()
        val presenter = presenter(store)
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.Replica(SaveSyncStatus.PENDING_UPLOAD, null))
    }

    @Test
    fun `refresh re-reads the store so a later status change is picked up`() {
        val store = InMemorySaveStateStore()
        val presenter = presenter(store)
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)

        store.upsert(replica(SaveSyncStatus.UNSYNCED)).getOrThrow()
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.Replica(SaveSyncStatus.UNSYNCED, null))

        // A re-upsert of the same scope (e.g. the drain marking it synced) replaces the record.
        store.upsert(replica(SaveSyncStatus.SYNCED)).getOrThrow()
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.Replica(SaveSyncStatus.SYNCED, null))
    }

    // ── quarantine view (F2: "View quarantine" drill-down state logic) ───────────────

    @Test
    fun `quarantine reason is parsed from the last error`() {
        assertThat(quarantineReason("quarantined: size-mismatch (post-play)")).isEqualTo("size-mismatch")
        assertThat(quarantineReason("quarantined: unknown-provenance (post-play)")).isEqualTo("unknown-provenance")
        assertThat(quarantineReason("quarantined: conflict")).isEqualTo("conflict")
        assertThat(quarantineReason(null)).isEqualTo("unknown")
        assertThat(quarantineReason("   ")).isEqualTo("unknown")
    }

    @Test
    fun `map quarantine renders the metadata rows from the replica`() {
        val model = mapQuarantine(
            reason = "size-mismatch",
            quarantinedPath = "/data/saves/x/y/7/h/quarantine/1700000000000-size_mismatch-autosave-abc.srm",
            replica = replica(SaveSyncStatus.QUARANTINED, rommSaveId = 900L, localSizeBytes = 2048L),
        )
        assertThat(model.title).isEqualTo("Incompatible Save")
        assertThat(model.reason).isEqualTo("size-mismatch")
        assertThat(model.description).contains("SRAM size")
        assertThat(model.fileName).isEqualTo("autosave.srm") // slot-based, like Android's resolveFileName
        assertThat(model.saveId).isEqualTo(900L)
        assertThat(model.sizeText).isEqualTo("2 KB")
        assertThat(model.coreId).isEqualTo("gambatte")
        assertThat(model.slot).isEqualTo(SavePathPolicy.AUTOSAVE_SLOT)
        assertThat(model.romId).isEqualTo(ROM_ID)
        assertThat(model.quarantinedPath).endsWith("1700000000000-size_mismatch-autosave-abc.srm")
    }

    @Test
    fun `map quarantine without a replica falls back to the path file name and unknown reason text`() {
        val model = mapQuarantine(
            reason = "conflict",
            quarantinedPath = "/data/saves/x/y/7/h/quarantine/1700000000000-conflict-autosave-abc.srm",
        )
        assertThat(model.fileName).isEqualTo("1700000000000-conflict-autosave-abc.srm")
        assertThat(model.saveId).isNull()
        assertThat(model.sizeText).isNull()
        assertThat(model.coreId).isNull()
        assertThat(model.slot).isNull()
        assertThat(model.romId).isNull()
        assertThat(model.description).contains("quarantined (conflict)")
    }

    @Test
    fun `newest quarantine file wins by epoch-ms name prefix`() {
        val dir = File.createTempFile("quarantine-test", "").apply { delete(); mkdirs() }
        try {
            File(dir, "1700000000000-size_mismatch-autosave-a.srm").writeText("old")
            File(dir, "1700000009999-size_mismatch-autosave-b.srm").writeText("newer")
            assertThat(newestQuarantineFile(dir)?.name).isEqualTo("1700000009999-size_mismatch-autosave-b.srm")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `quarantine view resolves the preserved copy for a quarantined replica`(@TempDir dir: Path) {
        val store = InMemorySaveStateStore()
        store.upsert(
            replica(SaveSyncStatus.QUARANTINED, "quarantined: size-mismatch (post-play)", rommSaveId = 900L, localSizeBytes = 2048L),
        ).getOrThrow()
        // The quarantine dir under the data dir — FileSaveContentGateway's exact layout.
        val quarantineDir = File(dir.toFile(), "saves/$SERVER_KEY/$USER_KEY/$ROM_ID/$ROM_HASH/quarantine")
            .apply { mkdirs() }
        File(quarantineDir, "1700000000000-size_mismatch-autosave-abc.srm").writeBytes(ByteArray(2048))

        val presenter = SaveSyncStatusPresenter(store, { SERVER_KEY to USER_KEY }, filesDir = dir.toFile())
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value)
            .isEqualTo(SaveSyncUiState.Replica(SaveSyncStatus.QUARANTINED, "quarantined: size-mismatch (post-play)"))

        val model = presenter.quarantineView(ROM_ID)
            ?: throw AssertionError("expected a quarantine view for the QUARANTINED replica")
        assertThat(model.reason).isEqualTo("size-mismatch")
        assertThat(model.description).contains("SRAM size")
        assertThat(model.fileName).isEqualTo("autosave.srm")
        assertThat(model.saveId).isEqualTo(900L)
        assertThat(model.sizeText).isEqualTo("2 KB")
        assertThat(model.coreId).isEqualTo("gambatte")
        assertThat(model.slot).isEqualTo(SavePathPolicy.AUTOSAVE_SLOT)
        assertThat(model.romId).isEqualTo(ROM_ID)
        assertThat(File(model.quarantinedPath).isFile).isTrue()
    }

    @Test
    fun `quarantine view is null for healthy replicas, unknown roms, and missing sessions`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED)).getOrThrow()

        // A healthy (SYNCED) replica has nothing quarantined to show.
        val presenter = SaveSyncStatusPresenter(store, { SERVER_KEY to USER_KEY })
        assertThat(presenter.quarantineView(ROM_ID)).isNull()

        // No replica at all for the ROM.
        val emptyPresenter = SaveSyncStatusPresenter(
            InMemorySaveStateStore(),
            sessionKeysProvider = { SERVER_KEY to USER_KEY },
        )
        assertThat(emptyPresenter.quarantineView(ROM_ID)).isNull()

        // No coherent session — no scope to scan.
        val noSession = SaveSyncStatusPresenter(store, sessionKeysProvider = { null })
        assertThat(noSession.quarantineView(ROM_ID)).isNull()
    }
}

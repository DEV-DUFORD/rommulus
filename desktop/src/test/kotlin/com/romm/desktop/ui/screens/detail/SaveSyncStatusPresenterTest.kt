package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.storage.fakes.InMemorySaveStateStore
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

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
        ) = SaveReplicaRecord(
            serverKey = SERVER_KEY,
            userKey = USER_KEY,
            romId = romId,
            romHash = romHash,
            slot = slot,
            coreId = "gambatte",
            coreBuildRevision = "v1",
            localWrittenAtEpochMs = writtenAtEpochMs,
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
    fun `refresh with no replica yields NoSave`() {
        val presenter = presenter(InMemorySaveStateStore())
        presenter.refresh(ROM_ID)
        assertThat(presenter.uiState.value).isEqualTo(SaveSyncUiState.NoSave)
    }

    @Test
    fun `no session keys (kiosk or blank origin) yields NoSave even when a replica exists`() {
        val store = InMemorySaveStateStore()
        store.upsert(replica(SaveSyncStatus.SYNCED)).getOrThrow()
        val presenter = SaveSyncStatusPresenter(store) { null }
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
}

package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Read-only UI state for the current ROM's autosave sync status — the first piece of the Linux
 * saves UI (a status line on the game detail screen; save-management actions come later).
 */
sealed interface SaveSyncUiState {
    /**
     * Nothing to show: no coherent non-kiosk session (blank origin / kiosk / anonymous) or no
     * autosave replica recorded for this ROM yet (never played, or saved before save sync landed).
     */
    data object NoSave : SaveSyncUiState

    /** The ROM has an autosave replica; [syncStatus] is its durable sync state. */
    data class Replica(
        val syncStatus: SaveSyncStatus,
        /** The drain's last failure reason, if any (rendered as a detail line when present). */
        val lastError: String?,
    ) : SaveSyncUiState
}

/**
 * The short status line shown under the detail screen's Play button. A pure function of
 * [SaveSyncUiState] so it is unit-testable without Compose.
 */
fun saveStatusLabel(state: SaveSyncUiState): String = when (state) {
    is SaveSyncUiState.NoSave -> "Save: none"
    is SaveSyncUiState.Replica -> "Save: ${state.syncStatus.uiLabel}"
}

/**
 * Which save actions the status line offers for [state] — the actionable half of the saves UI.
 * A pure function of [SaveSyncUiState] so it is unit-testable without Compose:
 *
 *  - "Sync now" (force a drain) is available whenever a replica exists in any NON-conflict status;
 *  - Keep-local / Keep-server conflict resolution is offered ONLY when the status is CONFLICT —
 *    never for healthy, in-flight, or quarantined states.
 */
data class SaveSyncUiActions(
    val canSyncNow: Boolean,
    val canResolveConflict: Boolean,
)

fun saveSyncUiActions(state: SaveSyncUiState): SaveSyncUiActions = when (state) {
    is SaveSyncUiState.NoSave -> SaveSyncUiActions(canSyncNow = false, canResolveConflict = false)
    is SaveSyncUiState.Replica -> if (state.syncStatus == SaveSyncStatus.CONFLICT) {
        SaveSyncUiActions(canSyncNow = false, canResolveConflict = true)
    } else {
        SaveSyncUiActions(canSyncNow = true, canResolveConflict = false)
    }
}

private val SaveSyncStatus.uiLabel: String
    get() = when (this) {
        SaveSyncStatus.SYNCED -> "synced"
        SaveSyncStatus.PENDING_UPLOAD -> "pending upload"
        SaveSyncStatus.PENDING_DOWNLOAD -> "pending download"
        SaveSyncStatus.CONFLICT -> "conflict — needs resolution"
        SaveSyncStatus.QUARANTINED -> "quarantined"
        SaveSyncStatus.UNSYNCED -> "unsynced"
        SaveSyncStatus.AWAITING_CORE_VALIDATION -> "awaiting core validation"
    }

/**
 * Read-only presenter driving [SaveSyncUiState] for one ROM's autosave.
 *
 * The replica is looked up by (serverKey, userKey, romId) rather than a full [SaveReplicaScope]
 * because the scope's romHash — the staged ROM content's SHA-256 — is only known at launch time,
 * after content staging, and the evictable ROM cache it would be hashed from may already be gone.
 * Every replica carries exactly one of the seven [SaveSyncStatus] values, so the union of
 * [SaveStateStore.findByStatus] over all statuses is precisely this session's replicas; filter to
 * [romId]'s autosave slot and, when a re-uploaded ROM left several hash generations behind, show
 * the newest local write.
 *
 * [refresh] is synchronous (small local SQLite + JSON reads) — callers on the UI thread dispatch
 * it themselves (the detail screen does). State writes are plain [MutableStateFlow] updates, safe
 * from any thread (including the player exit-watcher daemon).
 */
class SaveSyncStatusPresenter(
    private val store: SaveStateStore,
    /**
     * The current session's save-scope keys (sanitized origin/username), or null when there is no
     * coherent non-kiosk session — read at refresh time so a re-login is picked up without
     * rebuilding the presenter.
     */
    private val sessionKeysProvider: () -> Pair<String, String>?,
) {

    private val _uiState = MutableStateFlow<SaveSyncUiState>(SaveSyncUiState.NoSave)
    val uiState: StateFlow<SaveSyncUiState> = _uiState.asStateFlow()

    /** Re-reads [romId]'s autosave replica and publishes the derived state. Idempotent; safe from any thread. */
    fun refresh(romId: Long) {
        val (serverKey, userKey) = sessionKeysProvider() ?: run {
            _uiState.value = SaveSyncUiState.NoSave
            return
        }
        val replica = findAutosaveReplica(serverKey, userKey, romId)
        _uiState.value = when (replica) {
            null -> SaveSyncUiState.NoSave
            else -> SaveSyncUiState.Replica(replica.syncStatus, replica.lastError)
        }
    }

    private fun findAutosaveReplica(serverKey: String, userKey: String, romId: Long): SaveReplicaRecord? {
        val candidates = SaveSyncStatus.entries
            .flatMap { status -> store.findByStatus(serverKey, userKey, status) }
            .filter { it.romId == romId && it.slot == SavePathPolicy.AUTOSAVE_SLOT }
        // A re-uploaded ROM leaves one replica per content hash; the newest generation is the one
        // that belongs to the current file.
        return candidates.maxByOrNull { it.localWrittenAtEpochMs ?: Long.MIN_VALUE }
    }
}

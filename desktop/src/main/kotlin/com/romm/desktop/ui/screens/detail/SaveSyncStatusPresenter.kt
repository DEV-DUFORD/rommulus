package com.romm.desktop.ui.screens.detail

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.storage.ports.SaveStateStore
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import java.io.File
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
 *  - "Sync now" (force a drain) is available whenever a replica exists in any NON-conflict,
 *    NON-quarantined status;
 *  - Keep-local / Keep-server conflict resolution is offered ONLY when the status is CONFLICT —
 *    never for healthy, in-flight, or quarantined states;
 *  - "View quarantine" is offered ONLY when the status is QUARANTINED. A quarantined save is
 *    preserved on disk and needs an explicit compatibility/import decision — Android treats it as
 *    needing explicit action, so it is NEVER auto-redrained ("Sync now" would just re-negotiate
 *    around a deliberate quarantine and can silently undo the user's escape-hatch choice).
 */
data class SaveSyncUiActions(
    val canSyncNow: Boolean,
    val canResolveConflict: Boolean,
    val canViewQuarantine: Boolean = false,
)

fun saveSyncUiActions(state: SaveSyncUiState): SaveSyncUiActions = when (state) {
    is SaveSyncUiState.NoSave -> SaveSyncUiActions(canSyncNow = false, canResolveConflict = false)
    is SaveSyncUiState.Replica -> when (state.syncStatus) {
        SaveSyncStatus.CONFLICT ->
            SaveSyncUiActions(canSyncNow = false, canResolveConflict = true)
        // Quarantined: the ONLY offered action is viewing the preserved copy.
        SaveSyncStatus.QUARANTINED ->
            SaveSyncUiActions(canSyncNow = false, canResolveConflict = false, canViewQuarantine = true)
        else -> SaveSyncUiActions(canSyncNow = true, canResolveConflict = false)
    }
}

/**
 * Pure-UI model for a quarantined save (desktop mirror of Android's `SaveQuarantineUiModel`):
 * read-only metadata about the preserved copy plus its on-disk location. Carries only what the
 * quarantine dialog needs to render; never reaches the store or the network.
 */
data class SaveQuarantineUiModel(
    /** Explanatory title shown at the top of the dialog. */
    val title: String = "Incompatible Save",

    /** Reason string from the quarantine decision (e.g. "size-mismatch", "unknown-provenance"). */
    val reason: String,

    /** Human-readable explanation of why this save was quarantined. */
    val description: String,

    /** Display file name (e.g. "autosave.srm"). */
    val fileName: String,

    /** RomM save ID; null when unknown. */
    val saveId: Long?,

    /** File size in human-readable form ("12 KB"); null if unavailable. */
    val sizeText: String?,

    /** Core/provenance identifier (e.g. "gambatte"); null when unknown. */
    val coreId: String?,

    /** Slot name (e.g. "autosave"); null when unknown. */
    val slot: String?,

    /** RomM ROM ID this save belongs to; null when unknown (never fabricated as 0). */
    val romId: Long?,

    /** Path on disk where the quarantined copy is preserved; empty when it cannot be located. */
    val quarantinedPath: String,
)

/**
 * Extracts the quarantine reason from a replica's [SaveReplicaRecord.lastError] — e.g.
 * "quarantined: size-mismatch (post-play)" → "size-mismatch", "quarantined: conflict" →
 * "conflict". Returns "unknown" when nothing usable is recorded.
 */
fun quarantineReason(lastError: String?): String {
    var reason = lastError?.trim().orEmpty()
    if (reason.startsWith("quarantined:")) {
        reason = reason.removePrefix("quarantined:").trim()
    }
    // Drop a trailing phase suffix like "(post-play)" / "(pre-play)".
    val open = reason.lastIndexOf('(')
    if (open > 0 && reason.endsWith(')')) {
        reason = reason.substring(0, open).trim()
    }
    return reason.ifBlank { "unknown" }
}

/** Human-readable explanation for a quarantine [reason] (mirrors Android's mapQuarantine). */
fun quarantineDescription(reason: String): String = when (reason) {
    "size-mismatch" ->
        "The downloaded save file does not match the expected SRAM size for this core. It may belong to a different emulator or ROM revision."
    "unknown-provenance" ->
        "The downloaded save has no recognized core provenance metadata. It cannot be safely adopted without manual verification."
    else ->
        "This save was quarantined ($reason) and cannot be auto-adopted. A separate compatibility or import decision is required."
}

/**
 * Maps a quarantined replica + resolved quarantine path into [SaveQuarantineUiModel] (desktop
 * mirror of Android's `ConflictResolutionMapper.mapQuarantine`). Stateless and side-effect-free,
 * so the dialog's state logic is unit-testable without Compose.
 */
fun mapQuarantine(
    reason: String,
    quarantinedPath: String,
    replica: SaveReplicaRecord? = null,
): SaveQuarantineUiModel {
    val fileName = when {
        replica != null && replica.slot.isNotBlank() -> "${replica.slot}.srm"
        quarantinedPath.isNotBlank() -> quarantinedPath.substringAfterLast('/')
        else -> "save-${replica?.romId ?: 0}.srm"
    }
    return SaveQuarantineUiModel(
        reason = reason,
        description = quarantineDescription(reason),
        fileName = fileName,
        saveId = replica?.rommSaveId,
        sizeText = formatSaveSize(replica?.localSizeBytes),
        coreId = replica?.coreId?.takeIf { it.isNotBlank() },
        slot = replica?.slot?.takeIf { it.isNotBlank() },
        romId = replica?.romId,
        quarantinedPath = quarantinedPath,
    )
}

/** Human-readable file size ("12 KB"); null when unknown or non-positive (mirrors Android). */
fun formatSaveSize(bytes: Long?): String? {
    if (bytes == null || bytes <= 0) return null
    return when {
        bytes >= 1_048_576 -> "${bytes / 1_048_576} MB"
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}

/**
 * The newest quarantined copy in [dir]. [com.romm.desktop.sync.FileSaveContentGateway] names
 * quarantine files "<epochMs>-<reason>-<slot>-<random>.srm", so the epoch-ms name prefix is the
 * primary ordering key (last-modified time breaks ties / covers unparseable names). Null when the
 * directory is missing or holds no `.srm` files.
 */
fun newestQuarantineFile(dir: File): File? {
    val files = dir.listFiles { f -> f.isFile && f.extension == "srm" } ?: return null
    return files.maxWithOrNull(
        compareBy<File>({ it.name.takeWhile(Char::isDigit).toLongOrNull() ?: 0L }, { it.lastModified() }),
    )
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
    /**
     * The app data dir (root of `saves/…`) used by [quarantineView] to locate the preserved copy
     * on disk — the same root [com.romm.desktop.sync.FileSaveContentGateway] writes under. Null in
     * tests that only exercise the store-backed state; then the view degrades to an empty path.
     */
    private val filesDir: File? = null,
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

    /**
     * Read-only quarantine view for [romId]'s quarantined autosave — the "View quarantine"
     * drill-down (F2). Resolves the reason from the replica's last error and locates the preserved
     * copy by scanning the quarantine dir for this session's scope. Returns null when there is no
     * coherent session, no autosave replica for this ROM, or the newest replica is not QUARANTINED.
     * Non-mutating: acknowledging/dismissing the dialog changes nothing on disk or in the store.
     */
    fun quarantineView(romId: Long): SaveQuarantineUiModel? {
        val (serverKey, userKey) = sessionKeysProvider() ?: return null
        val replica = findAutosaveReplica(serverKey, userKey, romId) ?: return null
        if (replica.syncStatus != SaveSyncStatus.QUARANTINED) return null
        val quarantinedPath = quarantineDir(serverKey, userKey, replica)
            ?.let { newestQuarantineFile(it) }
            ?.absolutePath.orEmpty()
        return mapQuarantine(quarantineReason(replica.lastError), quarantinedPath, replica)
    }

    /**
     * The quarantine dir for one replica's scope — the SAME derivation as
     * [com.romm.desktop.sync.FileSaveContentGateway.quarantine]: a sibling "quarantine" dir next
     * to the slot directory (`saves/<server-key>/<user-key>/<rom-id>/<rom-hash>/quarantine`).
     * Null when no [filesDir] is configured.
     */
    private fun quarantineDir(serverKey: String, userKey: String, replica: SaveReplicaRecord): File? {
        val root = filesDir ?: return null
        val autosave = SavePathPolicy.autosaveSramPath(root, serverKey, userKey, replica.romId, replica.romHash)
        return File(autosave).parentFile?.parentFile?.resolve("quarantine")
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

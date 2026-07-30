package com.romm.androidtv.library.ui

import androidx.compose.runtime.Stable
import com.romm.androidtv.romm.SyncOperation
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Scoped pre-launch overlay state for a single ROM/session.
 *
 * Survives recomposition because it is stored in MainActivity's mutableStateOf field,
 * not inside a Composable remember block. Cannot cross ROM/session scopes: each instance
 * carries exactly one (romId, sessionId) pair and the caller must match both before
 * reading or clearing.
 *
 * Thread-safety: [isResolving] uses an [AtomicBoolean] for thread-safe duplicate submission
 * guards across coroutine boundaries. All other fields are mutated only on the Main dispatcher
 * per Compose conventions.
 */
@Stable
class SavePreLaunchState(
    val romId: Long,
    val sessionId: Long? = null,
    /** The ROM hash from the launch spec; required for exact scope lookup of SaveReplicaEntity. */
    val romHash: String = "",
) {
    /** The conflict UI model; non-null only while a ConflictRequiresResolution is active. */
    private var _conflictModel: SaveConflictUiModel? = null
    var conflictModel: SaveConflictUiModel?
        get() = _conflictModel
        set(value) { _conflictModel = value }

    /**
     * The original domain [SyncOperation] from negotiation (action == CONFLICT).
     * Preserved alongside the presentation model so resolveConflict can validate
     * the full serverContentHash and persist authoritative serverUpdatedAt end-to-end.
     * Never reconstructed from truncated presentation strings.
     */
    private var _conflictOperation: SyncOperation? = null
    var conflictOperation: SyncOperation?
        get() = _conflictOperation
        set(value) { _conflictOperation = value }

    /** The quarantine UI model; non-null only while a Quarantined outcome is displayed. */
    private var _quarantineModel: SaveQuarantineUiModel? = null
    var quarantineModel: SaveQuarantineUiModel?
        get() = _quarantineModel
        set(value) { _quarantineModel = value }

    /** Transient error message shown on the overlay screen (resolution failure, etc.). */
    private var _errorMessage: String? = null
    var errorMessage: String?
        get() = _errorMessage
        set(value) { _errorMessage = value }

    /** Thread-safe guard for duplicate resolution submissions. Uses AtomicBoolean. */
    private val _isResolving = AtomicBoolean(false)
    var isResolving: Boolean
        get() = _isResolving.get()
        set(value) { _isResolving.set(value) }

    /** The resolved canonical entity after successful Keep Local/Keep Server. Null until set. */
    private var _resolvedEntity: com.romm.androidtv.romm.save.SaveReplicaEntity? = null
    var resolvedEntity: com.romm.androidtv.romm.save.SaveReplicaEntity?
        get() = _resolvedEntity
        set(value) { _resolvedEntity = value }

    /**
     * Clears all transient overlay state. Called when the user dismisses (Cancel/Dismiss)
     * or after a successful resolution that proceeds to launch.
     */
    fun clear() {
        conflictModel = null
        conflictOperation = null
        quarantineModel = null
        errorMessage = null
        isResolving = false
        resolvedEntity = null
    }

    /** Returns true if this state represents an active overlay (conflict or quarantine). */
    val hasOverlay: Boolean
        get() = conflictModel != null || quarantineModel != null

    /**
     * Validates that the given romId/sessionId match this scope.
     * Prevents cross-scope mutation when multiple ROMs are staged concurrently.
     */
    fun matchesScope(romId: Long, sessionId: Long?): Boolean =
        this.romId == romId && (this.sessionId == null || this.sessionId == sessionId)

    companion object {
        /** Returns true if both states represent the same ROM/session scope. */
        fun sameScope(a: SavePreLaunchState?, b: SavePreLaunchState?): Boolean {
            if (a == null || b == null) return false
            return a.romId == b.romId && a.sessionId == b.sessionId
        }
    }
}

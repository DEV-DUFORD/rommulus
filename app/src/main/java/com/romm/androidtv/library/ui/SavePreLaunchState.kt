package com.romm.androidtv.library.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import com.romm.androidtv.romm.SyncOperation

/**
 * Scoped pre-launch overlay state for a single ROM/session.
 *
 * Survives recomposition because it is stored in MainActivity's mutableStateOf field,
 * not inside a Composable remember block. Cannot cross ROM/session scopes: each instance
 * carries exactly one (romId, sessionId) pair and the caller must match both before
 * reading or clearing.
 *
 * Compose observability: [isStaging], [isResolving], and [errorMessage] are exposed as
 * [MutableState] so that mutations trigger recomposition. All mutations must occur on the
 * Main dispatcher per Compose conventions. The controller (MainActivity) enforces a
 * duplicate-entry guard before creating or replacing state — do NOT rely on this class
 * for thread-safe entry guarding.
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
    private val _errorMessageState: MutableState<String?> = mutableStateOf(null)
    var errorMessage: String?
        get() = _errorMessageState.value
        set(value) { _errorMessageState.value = value }

    /**
     * Compose-observable guard for duplicate resolution submissions.
     * Mutations must occur on Main dispatcher.
     */
    private val _isResolvingState: MutableState<Boolean> = mutableStateOf(false)
    var isResolving: Boolean
        get() = _isResolvingState.value
        set(value) { _isResolvingState.value = value }

    /**
     * Compose-observable guard for duplicate staging submissions (Play button taps).
     * Prevents concurrent stage/sync/launch pipelines for the same ROM.
     * Mutations must occur on Main dispatcher.
     */
    private val _isStagingState: MutableState<Boolean> = mutableStateOf(false)
    var isStaging: Boolean
        get() = _isStagingState.value
        set(value) { _isStagingState.value = value }

    /** The resolved canonical entity after successful Keep Local/Keep Server. Null until set. */
    private var _resolvedEntity: com.romm.androidtv.romm.save.SaveReplicaEntity? = null
    var resolvedEntity: com.romm.androidtv.romm.save.SaveReplicaEntity?
        get() = _resolvedEntity
        set(value) { _resolvedEntity = value }

    /**
     * Compose-observable flag indicating bearer-auth expired during pre-launch sync.
     * When true, the UI renders an inline state with a "Log in" action instead of
     * a generic error. Mutations must occur on Main dispatcher.
     */
    private val _isAuthExpiredState: MutableState<Boolean> = mutableStateOf(false)
    var isAuthExpired: Boolean
        get() = _isAuthExpiredState.value
        set(value) { _isAuthExpiredState.value = value }

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
        isStaging = false
        isAuthExpired = false
        resolvedEntity = null
    }

    /**
     * Returns true if this state represents any active overlay: conflict, quarantine,
     * error-only, or auth-expired. Error-only and auth-expired states render inline within
     * GameDetailScreen (not as blocking overlays). Use [hasBlockingOverlay] to gate
     * full-screen overlay replacement.
     */
    val hasOverlay: Boolean
        get() = conflictModel != null || quarantineModel != null || errorMessage != null || isAuthExpired

    /** Returns true only for overlays that replace the game detail screen (conflict or quarantine). */
    val hasBlockingOverlay: Boolean
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

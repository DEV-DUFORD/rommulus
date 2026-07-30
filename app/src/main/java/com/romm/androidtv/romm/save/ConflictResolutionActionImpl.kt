package com.romm.androidtv.romm.save

import com.romm.androidtv.library.ui.ConflictResolutionAction
import com.romm.androidtv.romm.SyncOperation

/**
 * Production [ConflictResolutionAction] that delegates to [ConflictResolver].
 *
 * This adapter bridges the UI interface to the suspending domain resolver.
 * The [localFileName] is captured at construction time so the UI layer does not
 * need to supply it per-resolution.
 *
 * **Cancel/dismiss** remains outside the resolver: [acknowledgeQuarantine] is a
 * no-op that does not modify any data, consistent with the [ConflictResolutionAction]
 * contract.
 */
class ConflictResolutionActionImpl(
    private val resolver: ConflictResolver,
    private val serverOrigin: String,
    private val username: String,
    private val localFileName: String,
) : ConflictResolutionAction {

    override suspend fun resolveKeepLocal(
        sessionId: Long,
        localEntity: SaveReplicaEntity,
        serverOperation: SyncOperation,
    ) {
        resolver.resolveKeepLocal(
            sessionId = sessionId,
            serverOrigin = serverOrigin,
            username = username,
            localEntity = localEntity,
            operation = serverOperation,
            localFileName = localFileName,
        )
    }

    override suspend fun resolveKeepServer(
        sessionId: Long,
        localEntity: SaveReplicaEntity,
        serverOperation: SyncOperation,
    ) {
        resolver.resolveKeepServer(
            sessionId = sessionId,
            serverOrigin = serverOrigin,
            username = username,
            localEntity = localEntity,
            operation = serverOperation,
        )
    }

    override fun acknowledgeQuarantine(quarantinedPath: String) {
        // Non-mutating: quarantined file remains preserved at its path.
        // No data is modified.
    }
}

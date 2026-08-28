package com.romm.desktop.sync

import com.romm.androidtv.romm.DeviceIdentity
import com.romm.androidtv.romm.PlaySessionIngestRequest
import com.romm.androidtv.romm.PlaySessionIngestResult
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncCompleteResult
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult

/** In-memory [SaveContentGateway] for drain-executor unit tests (no filesystem). */
class FakeSaveContentGateway : SaveContentGateway {

    private fun key(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String) =
        "$serverKey|$userKey|$romId|$romHash|$slot"

    /** Canonical local save bytes per scope; absent key == "no local file". */
    val files = mutableMapOf<String, ByteArray>()

    /** (scope key, reason, bytes) in quarantine order. */
    val quarantined = mutableListOf<Triple<String, String, ByteArray>>()

    /** When true, [readLocal] throws — simulates an I/O failure mid-drain (never-strand path). */
    var throwOnRead = false

    fun setLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray) {
        files[key(serverKey, userKey, romId, romHash, slot)] = bytes.copyOf()
    }

    override fun readLocal(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String): ByteArray? {
        check(!throwOnRead) { "simulated I/O failure in readLocal" }
        return files[key(serverKey, userKey, romId, romHash, slot)]?.copyOf()
    }

    override fun writeLocalAtomically(serverKey: String, userKey: String, romId: Long, romHash: String, slot: String, bytes: ByteArray) {
        files[key(serverKey, userKey, romId, romHash, slot)] = bytes.copyOf()
    }

    override fun quarantine(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        reason: String,
        nowEpochMs: Long,
    ): String {
        val k = key(serverKey, userKey, romId, romHash, slot)
        quarantined.add(Triple(k, reason, bytes.copyOf()))
        return "quarantine/$nowEpochMs-$reason-$slot.srm"
    }

    /** (scope key, choice, content hash, bytes) in conflict-backup order. */
    val conflictBackups = mutableListOf<ConflictBackup>()

    data class ConflictBackup(
        val scopeKey: String,
        val choice: String,
        val contentHash: String,
        val bytes: ByteArray,
    )

    override fun conflictBackup(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String,
        bytes: ByteArray,
        choice: String,
        contentHash: String,
    ): String {
        val k = key(serverKey, userKey, romId, romHash, slot)
        conflictBackups.add(ConflictBackup(k, choice, contentHash, bytes.copyOf()))
        return "conflict-backups/$choice-${contentHash.take(16)}.srm"
    }
}

/** Scripted [RommSyncGateway] — every call recorded, results configurable per test. */
open class FakeRommSyncGateway : RommSyncGateway {

    var negotiateResult: SyncNegotiateResult = SyncNegotiateResult.Failure(com.romm.androidtv.romm.RommApiError.NETWORK_ERROR)
    var completeSessionResult: SyncCompleteResult = SyncCompleteResult.Success("completed")
    var uploadResult: SaveUploadResult = SaveUploadResult.Failure(com.romm.androidtv.romm.RommApiError.NETWORK_ERROR)
    var downloadResult: SaveDownloadResult = SaveDownloadResult.Failure(com.romm.androidtv.romm.RommApiError.NETWORK_ERROR)
    var confirmResult: SaveConfirmResult = SaveConfirmResult.Success
    var listSavesResult: com.romm.androidtv.romm.SaveListResult =
        com.romm.androidtv.romm.SaveListResult.Failure(com.romm.androidtv.romm.RommApiError.NETWORK_ERROR)
    var ingestPlaySessionsResult: PlaySessionIngestResult = PlaySessionIngestResult.Success(1, 0)

    val negotiateCalls = mutableListOf<Pair<String, SyncNegotiateRequest>>()
    val completeSessionCalls = mutableListOf<Triple<String, Long, SyncCompleteRequest>>()
    val uploadCalls = mutableListOf<Pair<String, SaveUploadRequest>>()
    val downloadCalls = mutableListOf<Quadruple>()
    val confirmCalls = mutableListOf<Triple<String, Long, String>>()
    val listSavesCalls = mutableListOf<Pair<String, Long>>()
    val ingestPlaySessionsCalls = mutableListOf<Pair<String, PlaySessionIngestRequest>>()

    data class Quadruple(val origin: String, val saveId: Long, val deviceId: String, val sessionId: Long?)

    override fun negotiateSync(origin: String, request: SyncNegotiateRequest): SyncNegotiateResult {
        negotiateCalls.add(origin to request)
        return negotiateResult
    }

    override fun completeSyncSession(origin: String, sessionId: Long, request: SyncCompleteRequest): SyncCompleteResult {
        completeSessionCalls.add(Triple(origin, sessionId, request))
        return completeSessionResult
    }

    override fun uploadSave(origin: String, request: SaveUploadRequest): SaveUploadResult {
        uploadCalls.add(origin to request)
        return uploadResult
    }

    override fun downloadSaveContent(origin: String, saveId: Long, deviceId: String, sessionId: Long?): SaveDownloadResult {
        downloadCalls.add(Quadruple(origin, saveId, deviceId, sessionId))
        return downloadResult
    }

    override fun downloadSaveContentBackup(origin: String, saveId: Long, deviceId: String): SaveDownloadResult {
        downloadCalls.add(Quadruple(origin, saveId, deviceId, null))
        return downloadResult
    }

    override fun confirmDownload(origin: String, saveId: Long, deviceId: String): SaveConfirmResult {
        confirmCalls.add(Triple(origin, saveId, deviceId))
        return confirmResult
    }

    override fun listSaves(origin: String, romId: Long, deviceId: String?): com.romm.androidtv.romm.SaveListResult {
        listSavesCalls.add(origin to romId)
        return listSavesResult
    }

    open override fun ingestPlaySessions(origin: String, request: PlaySessionIngestRequest): PlaySessionIngestResult {
        ingestPlaySessionsCalls.add(origin to request)
        return ingestPlaySessionsResult
    }
}

class FakeSaveSyncSessionReader(var session: SaveSyncSession?) : SaveSyncSessionReader {
    override fun current(): SaveSyncSession? = session
}

class FakeDeviceIdentityLoader(var identity: DeviceIdentity?) : SaveSyncDeviceIdentityLoader {
    override fun load(origin: String, username: String): DeviceIdentity? = identity
}

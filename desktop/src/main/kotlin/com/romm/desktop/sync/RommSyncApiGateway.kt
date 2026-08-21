package com.romm.desktop.sync

import com.romm.androidtv.romm.PlaySessionIngestRequest
import com.romm.androidtv.romm.PlaySessionIngestResult
import com.romm.androidtv.romm.RommSyncApi
import com.romm.androidtv.romm.SaveConfirmResult
import com.romm.androidtv.romm.SaveDownloadResult
import com.romm.androidtv.romm.SaveListResult
import com.romm.androidtv.romm.SaveUploadRequest
import com.romm.androidtv.romm.SaveUploadResult
import com.romm.androidtv.romm.SyncCompleteRequest
import com.romm.androidtv.romm.SyncCompleteResult
import com.romm.androidtv.romm.SyncNegotiateRequest
import com.romm.androidtv.romm.SyncNegotiateResult
import okhttp3.OkHttpClient

/**
 * Production [RommSyncGateway] delegating to `RommSyncApi`'s blocking methods with a shared
 * [OkHttp client]. The desktop keeps session cookies only in memory (DesktopNetworkModule), so the
 * same client instance the rest of the app uses carries authentication. Wired at construction in
 * the follow-up sub-unit; tests use fakes instead of this class.
 */
class RommSyncApiGateway(private val client: OkHttpClient) : RommSyncGateway {

    override fun negotiateSync(origin: String, request: SyncNegotiateRequest): SyncNegotiateResult =
        RommSyncApi.negotiateSync(client, origin, request)

    override fun completeSyncSession(origin: String, sessionId: Long, request: SyncCompleteRequest): SyncCompleteResult =
        RommSyncApi.completeSyncSession(client, origin, sessionId, request)

    override fun uploadSave(origin: String, request: SaveUploadRequest): SaveUploadResult =
        RommSyncApi.uploadSave(client, origin, request)

    override fun downloadSaveContent(origin: String, saveId: Long, deviceId: String, sessionId: Long?): SaveDownloadResult =
        RommSyncApi.downloadSaveContent(client, origin, saveId, deviceId, sessionId)

    override fun downloadSaveContentBackup(origin: String, saveId: Long, deviceId: String): SaveDownloadResult =
        RommSyncApi.downloadSaveContentBackup(client, origin, saveId, deviceId)

    override fun confirmDownload(origin: String, saveId: Long, deviceId: String): SaveConfirmResult =
        RommSyncApi.confirmDownload(client, origin, saveId, deviceId)

    override fun listSaves(origin: String, romId: Long, deviceId: String?): SaveListResult =
        RommSyncApi.listSaves(client, origin, romId, deviceId)

    override fun ingestPlaySessions(origin: String, request: PlaySessionIngestRequest): PlaySessionIngestResult =
        RommSyncApi.ingestPlaySessions(client, origin, request)
}

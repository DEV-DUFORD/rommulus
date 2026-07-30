package com.romm.androidtv.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import com.romm.androidtv.RommApplication
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.romm.BearerAuthInterceptor
import com.romm.androidtv.romm.ClientTokenStore
import com.romm.androidtv.romm.DeviceIdentityStore
import com.romm.androidtv.romm.DeviceRepository
import com.romm.androidtv.romm.DeviceRepositoryImpl
import com.romm.androidtv.romm.RommSyncApi
import com.romm.androidtv.romm.save.DurableSession
import com.romm.androidtv.romm.save.PendingOperationDao
import com.romm.androidtv.romm.save.SaveContentStore
import com.romm.androidtv.romm.save.SaveDatabase
import com.romm.androidtv.romm.save.SaveReplicaDao
import com.romm.androidtv.romm.save.SaveUploadCaller
import com.romm.androidtv.romm.save.SaveUploadExecutor
import com.romm.androidtv.romm.save.SaveUploadExecutorImpl
import com.romm.androidtv.romm.save.SessionReader
import com.romm.androidtv.romm.save.SyncNegotiateAndSyncExecutor
import com.romm.androidtv.romm.save.SyncNegotiateAndSyncExecutorImpl
import okhttp3.OkHttpClient

/**
 * Custom WorkManager [WorkerFactory] that constructs [SaveUploadWorker] with
 * production dependencies (Room DAO, SharedPreferences-backed stores, Bearer-auth
 * OkHttp client). Returns null for any worker class it does not recognize,
 * delegating to the default factory.
 */
class RommWorkerFactory(
    private val context: Context,
    private val executorProvider: () -> SaveUploadExecutor,
) : WorkerFactory() {

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        SaveUploadWorker::class.java.name -> {
            SaveUploadWorker(appContext, workerParameters, executorProvider())
        }
        else -> null
    }

    companion object {
        /**
         * Constructs a production [SaveUploadExecutor] from the Application context.
         * Wires Room DAOs, SharedPreferences-backed stores, and a Bearer-authenticated
         * OkHttp client that never touches WebView cookies.
         */
        fun buildProductionExecutor(context: Context): SaveUploadExecutor {
            val db = RommApplication.database(context)
            val pendingOpDao: PendingOperationDao = db.pendingOperationDao()
            val saveReplicaDao: SaveReplicaDao = db.saveReplicaDao()
            val contentStore: SaveContentStore = com.romm.androidtv.romm.save.FileSaveContentStore(
                context.filesDir,
            )

            val sessionPrefs = context.getSharedPreferences(SessionStore.PREFS_NAME, Context.MODE_PRIVATE)
            val sessionStore = SessionStore(sessionPrefs)

            val tokenStore = ClientTokenStore(context)

            val devicePrefs = context.getSharedPreferences(DeviceIdentityStore.PREFS_NAME, Context.MODE_PRIVATE)
            val deviceIdentityStore = DeviceIdentityStore(devicePrefs)

            // Cookie-independent OkHttp client for worker execution.
            val workerClient = OkHttpClient.Builder()
                .addInterceptor(BearerAuthInterceptor {
                    val session = sessionStore.current()
                    session?.let { s ->
                        tokenStore.getToken(s.origin, s.username ?: "")?.raw
                    }
                })
                .build()

            val deviceRepo: DeviceRepository = DeviceRepositoryImpl(workerClient, deviceIdentityStore)

            val saveUploadCaller = SaveUploadCaller { origin: String, request: com.romm.androidtv.romm.SaveUploadRequest ->
                RommSyncApi.uploadSave(workerClient, origin, request)
            }

            val sessionReader = SessionReader {
                sessionStore.current()?.let { s ->
                    DurableSession(s.origin, s.username)
                }
            }

            val deviceIdentityLoader = com.romm.androidtv.romm.save.DeviceIdentityLoader { origin, username ->
                val result = deviceRepo.ensureRegistered(origin, username)
                (result as? com.romm.androidtv.romm.DeviceRegistrationResult.Success)?.identity
            }

            val negotiateAndSyncExecutor: SyncNegotiateAndSyncExecutor = SyncNegotiateAndSyncExecutorImpl(
                client = workerClient,
                pendingOperationDao = pendingOpDao,
                saveReplicaDao = saveReplicaDao,
                saveContentStore = contentStore,
                sessionReader = sessionReader,
                deviceIdentityLoader = deviceIdentityLoader,
                uploadCaller = saveUploadCaller,
            )

            return SaveUploadExecutorImpl(
                pendingOperationDao = pendingOpDao,
                saveReplicaDao = saveReplicaDao,
                saveContentStore = contentStore,
                sessionReader = sessionReader,
                deviceIdentityLoader = deviceIdentityLoader,
                negotiateAndSyncExecutor = negotiateAndSyncExecutor,
                uploadCaller = saveUploadCaller,
            )
        }
    }
}

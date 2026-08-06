package com.romm.androidtv.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.romm.androidtv.auth.SessionStore
import com.romm.androidtv.romm.save.SaveUploadExecutor
import com.romm.androidtv.romm.save.SaveUploadExecutor.DrainResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Instrumented tests for WorkManager wiring: enqueue behavior and doWork result mapping.
 *
 * Uses [TestListenableWorkerBuilder.from] with a custom [WorkerFactory] to inject a fake
 * [SaveUploadExecutor]. This validates that the real [SaveUploadWorker] class integrates
 * correctly with work-testing infrastructure.
 */
class SaveUploadWorkerInstrumentedTest {

    // Mutable holder so each test can supply its own drain behaviour.
    private var drainBehavior: suspend () -> DrainResult = { DrainResult.Complete }

    private val testFactory = object : WorkerFactory() {
        override fun createWorker(
            appContext: Context,
            workerClassName: String,
            workerParameters: WorkerParameters,
        ): ListenableWorker? = when (workerClassName) {
            SaveUploadWorker::class.java.name -> {
                val fakeExecutor = object : SaveUploadExecutor {
                    override suspend fun drainBatch(): DrainResult = drainBehavior()
                }
                val sessionStore = SessionStore(
                    appContext.getSharedPreferences(SessionStore.PREFS_NAME, Context.MODE_PRIVATE),
                )
                SaveUploadWorker(appContext, workerParameters, fakeExecutor, sessionStore)
            }
            else -> null
        }
    }

    // ---- Enqueue helper: real WorkManager integration ----

    @Test
    fun enqueue_helper_can_be_called_repeatedly_without_error() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SaveUploadEnqueueHelper.cancel(context)

        // Multiple enqueues are safe; KEEP policy de-duplicates.
        SaveUploadEnqueueHelper.enqueue(context)
        SaveUploadEnqueueHelper.enqueue(context)
        SaveUploadEnqueueHelper.enqueue(context)

        SaveUploadEnqueueHelper.cancel(context)
    }

    @Test
    fun enqueue_helper_cancel_then_reenqueue_is_safe() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        SaveUploadEnqueueHelper.cancel(context)
        SaveUploadEnqueueHelper.enqueue(context)
        SaveUploadEnqueueHelper.cancel(context)
        SaveUploadEnqueueHelper.enqueue(context)
        SaveUploadEnqueueHelper.cancel(context)
    }

    // ---- doWork result mapping via custom WorkerFactory ----

    @Test
    fun worker_maps_Complete_to_Result_success() {
        drainBehavior = { DrainResult.Complete }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = buildWorkerWithFactory(context)

        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun worker_maps_Retry_to_Result_retry() {
        drainBehavior = { DrainResult.Retry }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = buildWorkerWithFactory(context)

        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun worker_maps_unexpected_exception_to_Result_retry() {
        drainBehavior = { throw RuntimeException("unexpected bug") }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val worker = buildWorkerWithFactory(context)

        // Production SaveUploadWorker.doWork() catches all top-level exceptions,
        // logs a message about stranded RUNNING recovery, and returns Result.retry().
        // The executor's drainBatch() recovers stranded rows at the start of each call,
        // so retrying gives the next invocation a chance to clean up.
        val result = runBlocking { worker.doWork() }
        assertEquals(ListenableWorker.Result.retry(), result)
    }

    private fun buildWorkerWithFactory(context: Context): SaveUploadWorker {
        return TestListenableWorkerBuilder.from(context, SaveUploadWorker::class.java)
            .setWorkerFactory(testFactory)
            .build()
    }
}

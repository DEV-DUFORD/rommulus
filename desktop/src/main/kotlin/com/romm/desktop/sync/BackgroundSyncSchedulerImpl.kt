package com.romm.desktop.sync

import com.romm.androidtv.storage.ports.BackgroundSyncScheduler
import com.romm.androidtv.storage.ports.SchedulerState
import com.romm.desktop.storage.sqlite.SchedulerStateStore
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.math.pow

/**
 * Bounded exponential backoff with jitter (plans/LINUX_X64.md §10.5). Defaults: 30s base,
 * factor 2, 1h cap, ±20% jitter. Injectable for tests.
 */
class BackoffConfig(
    val baseDelayMs: Long = 30_000L,
    val factor: Int = 2,
    val maxDelayMs: Long = 3_600_000L,
    val jitterRatio: Double = 0.20,
) {
    /**
     * Delay for a given [attemptCount] (1-based). Raw delay is `base * factor^(attempt-1)`
     * capped at [maxDelayMs], then scaled by jitter `[1-jitterRatio, 1+jitterRatio]` using
     * [random] in `[0, 1)`. Never returns less than 1ms.
     */
    fun delayMsForAttempt(attemptCount: Int, random: Double): Long {
        val exponent = maxOf(0, attemptCount - 1)
        val raw = baseDelayMs.toDouble() * factor.toDouble().pow(exponent.toDouble())
        val capped = minOf(raw, maxDelayMs.toDouble())
        val jitter = 1.0 + (random - 0.5) * 2.0 * jitterRatio
        return (capped * jitter).toLong().coerceAtLeast(1)
    }
}

/**
 * In-process [BackgroundSyncScheduler] for the desktop port (plans/LINUX_X64.md §10.5,
 * PHASE5.md §4 item 7). This deliberately does NOT reproduce WorkManager: a single in-process
 * scheduler owns the timing / mutex / state mechanics and delegates the actual work to an
 * INJECTED [drain] action.
 *
 * The durable SQLite queue (PendingOperationStore), NOT an in-memory timer, is the source of
 * truth: closing the app may delay an upload but never loses the operation. This class only
 * decides WHEN to drain; the injected [drain] performs the drain (wired in Phase 6 to
 * PendingOperationStore + network) and never touches the queue itself. The scheduler also
 * never stores auth tokens.
 *
 * Durable retry state (attempt count + next-attempt time) is persisted via [stateStore] so a
 * process restart survives and resumes the backoff schedule.
 *
 * Triggers arrive exclusively through [requestDrain]; Phase 6 passes reasons such as
 * "startup", "auth", "network-restored", "retry-now". On construction the scheduler resumes a
 * persisted future next-attempt as [SchedulerState.Waiting], otherwise it starts a "startup"
 * drain.
 *
 * Threading: a single-threaded [ScheduledExecutorService] plus atomics/locks — no coroutines
 * dependency (the desktop module pulls none in).
 */
class BackgroundSyncSchedulerImpl(
    private val drain: () -> Unit,
    private val stateStore: SchedulerStateStore,
    private val backoff: BackoffConfig = BackoffConfig(),
    private val random: () -> Double = { ThreadLocalRandom.current().nextDouble() },
) : BackgroundSyncScheduler {

    /** Guards [draining], [pendingRetry], [shutdown]; short critical sections only. */
    private val lock = ReentrantLock()

    /** The "one active drain" mutex. True from drain start until markDrained/scheduleRetryAfter. */
    private val draining = AtomicBoolean(false)

    private val stateRef = AtomicReference<SchedulerState>(SchedulerState.Idle)

    @Volatile
    private var shutdown = false

    /** Coalesces requests received while a drain is active into one follow-up drain. */
    private var drainRequestedWhileRunning = false

    private var pendingRetry: ScheduledFuture<*>? = null

    private val scheduler: ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "background-sync-scheduler").apply { isDaemon = true }
        }

    init {
        val persistedNext = stateStore.loadNextAttemptEpochMs()
        if (persistedNext != null && persistedNext > System.currentTimeMillis()) {
            // Resume the persisted backoff schedule: wait, do not drain yet.
            lock.withLock {
                stateRef.set(SchedulerState.Waiting(persistedNext))
                scheduleRetryTask(persistedNext)
            }
        } else {
            // Fresh start, or a persisted retry is now due: drain now.
            requestDrain("startup")
        }
    }

    override fun requestDrain(reason: String): Boolean {
        val started = lock.withLock {
            if (shutdown) {
                false
            } else if (draining.get()) {
                drainRequestedWhileRunning = true
                false
            } else {
                draining.set(true)
                cancelPendingRetry()
                stateRef.set(SchedulerState.Draining)
                true
            }
        }
        if (started) submitDrain()
        return started
    }

    override fun markDrained(): Boolean {
        var runAgain = false
        val marked = lock.withLock {
            if (!draining.get()) {
                false
            } else {
                stateStore.clear()
                if (drainRequestedWhileRunning && !shutdown) {
                    drainRequestedWhileRunning = false
                    runAgain = true
                } else {
                    draining.set(false)
                    stateRef.set(SchedulerState.Idle)
                }
                true
            }
        }
        if (runAgain) submitDrain()
        return marked
    }

    override fun scheduleRetryAfter(tentativeAttemptCount: Int, cause: String): Boolean {
        val nextAttempt = System.currentTimeMillis() +
            backoff.delayMsForAttempt(tentativeAttemptCount, random())
        return lock.withLock {
            val valid = draining.get()
            if (valid) {
                draining.set(false)
                drainRequestedWhileRunning = false
                stateStore.persist(tentativeAttemptCount, nextAttempt)
                cancelPendingRetry()
                stateRef.set(SchedulerState.Waiting(nextAttempt))
                scheduleRetryTask(nextAttempt)
            }
            valid
        }
    }

    override fun currentState(): SchedulerState = stateRef.get()

    override fun shutdown() {
        lock.withLock {
            shutdown = true
            cancelPendingRetry()
            draining.set(false)
            drainRequestedWhileRunning = false
            // The durable SQLite queue is untouched by shutdown: queued operations survive.
            // Retry state was already persisted on scheduleRetryAfter; nothing to add here.
            stateRef.set(SchedulerState.Idle)
        }
        scheduler.shutdown()
    }

    private fun submitDrain() {
        scheduler.execute {
            try {
                drain()
            } catch (t: Throwable) {
                // Safety net: never leave the scheduler stuck in Draining if the injected
                // drain throws before Phase 6 calls markDrained()/scheduleRetryAfter().
                markDrained()
            }
        }
    }

    private fun scheduleRetryTask(nextAttemptEpochMs: Long) {
        cancelPendingRetry()
        val delay = maxOf(0L, nextAttemptEpochMs - System.currentTimeMillis())
        pendingRetry = scheduler.schedule(
            { requestDrain("retry") },
            delay,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun cancelPendingRetry() {
        pendingRetry?.cancel(false)
        pendingRetry = null
    }
}

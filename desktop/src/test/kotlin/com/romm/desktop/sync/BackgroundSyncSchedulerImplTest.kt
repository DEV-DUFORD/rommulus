package com.romm.desktop.sync

import com.romm.androidtv.storage.ports.SchedulerState
import com.romm.desktop.storage.sqlite.Migration
import com.romm.desktop.storage.sqlite.SchedulerStateStore
import com.romm.desktop.storage.sqlite.SqliteDatabase
import com.romm.desktop.storage.sqlite.SqliteSchedulerStateStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Focused tests for the desktop in-process background sync scheduler (plans/LINUX_X64.md §10.5,
 * PHASE5.md §4 item 7): drain invocation, one-active-drain coalescing, durable retry persistence
 * across a scheduler restart, bounded backoff, retry-now bypass, shutdown semantics, state
 * transitions, and the V1→V2 migration that adds the scheduler_state table.
 *
 * The scheduler runs drains on its own single-threaded executor, so the tests synchronize on a
 * drain-call counter rather than assuming a drain completes before the next assertion.
 */
class BackgroundSyncSchedulerImplTest {

    @TempDir
    lateinit var tempDir: Path

    private val drainCalls = AtomicInteger(0)
    private val monitor = java.lang.Object()
    private val openedDbs = mutableListOf<SqliteDatabase>()

    // Drain action shared by most tests: records the call and wakes any waiters.
    private val drainAction: () -> Unit = {
        drainCalls.incrementAndGet()
        synchronized(monitor) { monitor.notifyAll() }
    }

    @AfterEach
    fun tearDown() {
        openedDbs.forEach { it.close() }
        openedDbs.clear()
        drainCalls.set(0)
    }

    // ── scheduler + store helpers ───────────────────────────────────────────────

    private fun newDb(name: String): SqliteDatabase =
        SqliteDatabase.open(tempDir.resolve(name)).getOrThrow().also { openedDbs += it }

    private fun newStore(name: String = "sched.db"): SchedulerStateStore =
        SqliteSchedulerStateStore(newDb(name))

    private fun newScheduler(
        store: SchedulerStateStore,
        drain: () -> Unit = drainAction,
        backoff: BackoffConfig = BackoffConfig(),
        random: () -> Double = { 0.5 },
    ) = BackgroundSyncSchedulerImpl(drain, store, backoff, random)

    /** Blocks until [drainCalls] has reached at least [target] (5s timeout). */
    private fun awaitDrain(target: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        synchronized(monitor) {
            while (drainCalls.get() < target) {
                val remainMs = (deadline - System.nanoTime()) / 1_000_000
                if (remainMs <= 0) break
                monitor.wait(remainMs)
            }
        }
        assertThat(drainCalls.get()).`as`("drain action invoked").isGreaterThanOrEqualTo(target)
    }

    // ── behavior ───────────────────────────────────────────────────────────────

    @Test
    fun `drain action invoked on requestDrain`() {
        val s = newScheduler(newStore())
        awaitDrain(1) // construction triggers the startup drain
        assertThat(drainCalls.get()).isEqualTo(1)

        s.markDrained()
        assertThat(s.requestDrain("auth")).isTrue()
        awaitDrain(2)
        assertThat(drainCalls.get()).isEqualTo(2)
        assertThat(s.currentState()).isEqualTo(SchedulerState.Draining)

        s.markDrained()
        s.shutdown()
    }

    @Test
    fun `concurrent requestDrain coalesces to one active drain`() {
        val release = CountDownLatch(1)
        val blockingDrain: () -> Unit = {
            drainCalls.incrementAndGet()
            synchronized(monitor) { monitor.notifyAll() }
            release.await(5, TimeUnit.SECONDS)
        }
        val s = newScheduler(newStore(), drain = blockingDrain)

        awaitDrain(1) // startup drain is now blocking, holding the one-active-drain mutex
        assertThat(drainCalls.get()).isEqualTo(1)

        // Coalesced while draining: rejected, no overlapping drain started.
        assertThat(s.requestDrain("auth")).isFalse()
        assertThat(s.requestDrain("network-restored")).isFalse()
        assertThat(drainCalls.get()).isEqualTo(1)

        release.countDown() // let the in-flight drain finish
        Thread.sleep(50)
        assertThat(s.currentState()).isEqualTo(SchedulerState.Draining)
        s.markDrained()
        s.shutdown()
        assertThat(drainCalls.get()).isEqualTo(1)
    }

    @Test
    fun `scheduleRetryAfter persists next-attempt and attempt count across a scheduler restart`() {
        val path = "restart.db"
        val db1 = newDb(path)
        val store1 = SqliteSchedulerStateStore(db1)
        val s1 = newScheduler(store1)
        awaitDrain(1)
        s1.markDrained()

        s1.requestDrain("auth")
        awaitDrain(2)
        assertThat(s1.scheduleRetryAfter(3, "network-error")).isTrue()
        val waiting = s1.currentState() as SchedulerState.Waiting
        assertThat(waiting.nextAttemptEpochMs).isGreaterThan(System.currentTimeMillis())

        s1.shutdown()
        db1.close()

        // Recreate on the SAME durable store (new connection, same file).
        val store2 = SqliteSchedulerStateStore(newDb(path))
        assertThat(store2.loadAttemptCount()).isEqualTo(3)
        assertThat(store2.loadNextAttemptEpochMs()).isEqualTo(waiting.nextAttemptEpochMs)

        // A new scheduler resumes the persisted schedule: no drain fires, state is Waiting.
        val callsBeforeRestart = drainCalls.get()
        val s2 = newScheduler(store2)
        assertThat(s2.currentState()).isEqualTo(SchedulerState.Waiting(waiting.nextAttemptEpochMs))
        assertThat(drainCalls.get()).isEqualTo(callsBeforeRestart)
        s2.shutdown()
    }

    @Test
    fun `backoff is bounded exponential with jitter and respects the cap`() {
        // No jitter (jitterRatio 0.0): exact powers of two up to the cap.
        val cfg = BackoffConfig(baseDelayMs = 1000, factor = 2, maxDelayMs = 5000, jitterRatio = 0.0)
        assertThat(cfg.delayMsForAttempt(1, 0.5)).isEqualTo(1000)
        assertThat(cfg.delayMsForAttempt(2, 0.5)).isEqualTo(2000)
        assertThat(cfg.delayMsForAttempt(3, 0.5)).isEqualTo(4000)
        assertThat(cfg.delayMsForAttempt(4, 0.5)).isEqualTo(5000) // 8s capped to 5s
        assertThat(cfg.delayMsForAttempt(10, 0.5)).isEqualTo(5000) // still capped

        // ±20% jitter around the raw delay.
        val jittery = BackoffConfig(baseDelayMs = 1000, factor = 2, maxDelayMs = 5000, jitterRatio = 0.20)
        assertThat(jittery.delayMsForAttempt(1, 0.0)).isEqualTo(800) // 1 - 0.2
        assertThat(jittery.delayMsForAttempt(1, 1.0)).isEqualTo(1200) // 1 + 0.2
    }

    @Test
    fun `retry-now bypasses Waiting and drains immediately`() {
        val s = newScheduler(newStore())
        awaitDrain(1)
        s.markDrained()

        s.requestDrain("auth")
        awaitDrain(2)
        s.scheduleRetryAfter(2, "err")
        assertThat(s.currentState()).isInstanceOf(SchedulerState.Waiting::class.java)

        // requestDrain while Waiting is the retry-now path: accepted and drains immediately.
        assertThat(s.requestDrain("retry-now")).isTrue()
        assertThat(s.currentState()).isEqualTo(SchedulerState.Draining)
        awaitDrain(3)

        s.markDrained()
        s.shutdown()
    }

    @Test
    fun `shutdown persists retry state and stops all further scheduling`() {
        val store = newStore()
        val s = newScheduler(store)
        awaitDrain(1)
        s.markDrained()

        s.requestDrain("auth")
        awaitDrain(2)
        s.scheduleRetryAfter(1, "err")
        s.shutdown()

        assertThat(store.loadAttemptCount()).isEqualTo(1)
        assertThat(store.loadNextAttemptEpochMs()).isNotNull()
        // No further scheduling possible.
        assertThat(s.requestDrain("startup")).isFalse()
        assertThat(s.scheduleRetryAfter(2, "err")).isFalse()
        assertThat(s.markDrained()).isFalse()
        assertThat(drainCalls.get()).isEqualTo(2)
    }

    @Test
    fun `currentState transitions Idle Draining Waiting and back`() {
        val s = newScheduler(newStore())
        awaitDrain(1)
        s.markDrained()
        assertThat(s.currentState()).isEqualTo(SchedulerState.Idle)

        s.requestDrain("auth")
        assertThat(s.currentState()).isEqualTo(SchedulerState.Draining)

        s.scheduleRetryAfter(2, "err")
        assertThat(s.currentState()).isInstanceOf(SchedulerState.Waiting::class.java)

        s.requestDrain("retry-now")
        assertThat(s.currentState()).isEqualTo(SchedulerState.Draining)

        assertThat(s.markDrained()).isTrue()
        assertThat(s.currentState()).isEqualTo(SchedulerState.Idle)
        s.shutdown()
    }

    // ── V1→V2 migration ─────────────────────────────────────────────────────────

    @Test
    fun `sqlite migrates V1 to V2 forward-only and scheduler_state table works`() {
        val v1 = javaClass.classLoader.getResource("db/migrations/V1__init.sql")
            ?.readText() ?: error("V1__init.sql not on test classpath")
        val v2 = javaClass.classLoader.getResource("db/migrations/V2__scheduler_state.sql")
            ?.readText() ?: error("V2__scheduler_state.sql not on test classpath")
        val path = tempDir.resolve("migrated.db")

        // V1 only -> schemaVersion 1, no scheduler_state table yet.
        SqliteDatabase.open(path, listOf(Migration(1, v1))).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(1)
            val tables = db.query<String>(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                { rs -> rs.getString(1) },
            )
            assertThat(tables).doesNotContain("scheduler_state")
        }

        // V1 + V2 -> schemaVersion 2 and the scheduler_state table is present/usable.
        SqliteDatabase.open(path, listOf(Migration(1, v1), Migration(2, v2)))
            .getOrThrow().use { db ->
                assertThat(db.schemaVersion).isEqualTo(2)
                val tables = db.query<String>(
                    "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                    { rs -> rs.getString(1) },
                )
                assertThat(tables).contains("scheduler_state")

                val store = SqliteSchedulerStateStore(db)
                assertThat(store.loadNextAttemptEpochMs()).isNull()
                store.persist(4, 1_700_000_000_000L)
                assertThat(store.loadAttemptCount()).isEqualTo(4)
                assertThat(store.loadNextAttemptEpochMs()).isEqualTo(1_700_000_000_000L)
                store.clear()
                assertThat(store.loadNextAttemptEpochMs()).isNull()
            }

        // Forward-only: a DB already at v2 is refused when only v1 migrations are known.
        val downgrade = SqliteDatabase.open(path, listOf(Migration(1, v1)))
        assertThat(downgrade.isFailure).isTrue()
        assertThat(downgrade.exceptionOrNull()?.message).contains("forward-only")
    }
}

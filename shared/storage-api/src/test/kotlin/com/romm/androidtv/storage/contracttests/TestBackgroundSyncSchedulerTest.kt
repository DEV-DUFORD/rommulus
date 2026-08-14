package com.romm.androidtv.storage.contracttests

import com.romm.androidtv.storage.fakes.TestBackgroundSyncScheduler
import com.romm.androidtv.storage.ports.SchedulerState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class TestBackgroundSyncSchedulerTest {

    @Test
    fun `initial state is Idle`() {
        val scheduler = TestBackgroundSyncScheduler()
        assertTrue(scheduler.currentState() is SchedulerState.Idle)
    }

    @Test
    fun `requestDrain transitions to Draining and records reason`() {
        val scheduler = TestBackgroundSyncScheduler()
        val accepted = scheduler.requestDrain("user action")
        assertTrue(accepted)
        assertTrue(scheduler.currentState() is SchedulerState.Draining)
        assertEquals(1, scheduler.drainRequests.size)
        assertEquals("user action", scheduler.drainRequests[0])
    }

    @Test
    fun `requestDrain rejects when already draining`() {
        val scheduler = TestBackgroundSyncScheduler()
        scheduler.requestDrain("first")
        val rejected = scheduler.requestDrain("second")
        assertFalse(rejected)
        assertEquals(1, scheduler.drainRequests.size)
    }

    @Test
    fun `markDrained transitions from Draining to Idle`() {
        val scheduler = TestBackgroundSyncScheduler()
        scheduler.requestDrain("test")
        val drained = scheduler.markDrained()
        assertTrue(drained)
        assertTrue(scheduler.currentState() is SchedulerState.Idle)
    }

    @Test
    fun `markDrained rejects when not draining`() {
        val scheduler = TestBackgroundSyncScheduler()
        val rejected = scheduler.markDrained()
        assertFalse(rejected)
    }

    @Test
    fun `scheduleRetryAfter transitions to Waiting from Draining`() {
        val scheduler = TestBackgroundSyncScheduler()
        scheduler.requestDrain("test")
        val accepted = scheduler.scheduleRetryAfter(3, "transient error")
        assertTrue(accepted)
        assertTrue(scheduler.currentState() is SchedulerState.Waiting)
        assertEquals(1, scheduler.retries)
    }

    @Test
    fun `scheduleRetryAfter increments retries`() {
        val scheduler = TestBackgroundSyncScheduler()
        scheduler.requestDrain("test")
        scheduler.scheduleRetryAfter(3, "error 1")
        // Reset to draining for second retry.
        scheduler.shutdown()
        scheduler.requestDrain("test2")
        scheduler.scheduleRetryAfter(5, "error 2")
        assertEquals(2, scheduler.retries)
    }

    @Test
    fun `shutdown resets to Idle`() {
        val scheduler = TestBackgroundSyncScheduler()
        scheduler.requestDrain("test")
        scheduler.shutdown()
        assertTrue(scheduler.currentState() is SchedulerState.Idle)
    }
}

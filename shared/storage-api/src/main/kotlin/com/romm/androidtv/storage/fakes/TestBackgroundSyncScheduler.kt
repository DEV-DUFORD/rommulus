package com.romm.androidtv.storage.fakes

import com.romm.androidtv.storage.ports.BackgroundSyncScheduler
import com.romm.androidtv.storage.ports.SchedulerState

/**
 * Test scheduler that records drain requests and retry counts.
 * State transitions: Idle -> Draining -> Idle (markDrained) or Waiting -> Idle (markDrained).
 */
class TestBackgroundSyncScheduler : BackgroundSyncScheduler {

    private val lock = Any()
    @Volatile private var _state: SchedulerState = SchedulerState.Idle
    val drainRequests: MutableList<String> = mutableListOf()
    var retries: Int = 0
        private set

    override fun requestDrain(reason: String): Boolean {
        return synchronized(lock) {
            if (_state is SchedulerState.Draining || _state == SchedulerState.Waiting(Long.MAX_VALUE)) {
                false
            } else {
                drainRequests.add(reason)
                _state = SchedulerState.Draining
                true
            }
        }
    }

    override fun markDrained(): Boolean {
        return synchronized(lock) {
            if (_state is SchedulerState.Draining) {
                _state = SchedulerState.Idle
                true
            } else {
                false
            }
        }
    }

    override fun scheduleRetryAfter(tentativeAttemptCount: Int, cause: String): Boolean {
        return synchronized(lock) {
            if (_state is SchedulerState.Draining || _state is SchedulerState.Waiting) {
                val nextAttempt = System.currentTimeMillis() + tentativeAttemptCount * 1000L
                _state = SchedulerState.Waiting(nextAttempt)
                retries++
                true
            } else {
                false
            }
        }
    }

    override fun currentState(): SchedulerState {
        return synchronized(lock) { _state }
    }

    override fun shutdown() {
        synchronized(lock) { _state = SchedulerState.Idle }
    }
}

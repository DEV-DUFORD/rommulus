package com.romm.desktop.controller

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * True while a [DesktopCaptureCoordinator] session is still accepting input (pre- or
 * post-neutral). Terminal states ([Result], [Cancelled], [TimedOut], [NoDeviceAssigned])
 * and [DesktopCaptureState.Idle] are not active.
 */
fun captureActive(state: DesktopCaptureState): Boolean =
    state is DesktopCaptureState.AwaitingNeutral || state is DesktopCaptureState.Capturing

/**
 * Feeds one [JInputSource]'s poll stream into a [DesktopCaptureCoordinator] (E2: the
 * controller-settings capture overlay).
 *
 * The pump runs a ~60 Hz loop on [scope] for as long as it is started. Each tick is a no-op
 * (no enumeration, no polling) while no capture session is active — checked via
 * [captureActive] on the coordinator's state — so an always-on pump costs effectively nothing.
 * While a session IS active, every enumerated controller is polled once per tick and fed to
 * [DesktopCaptureCoordinator.onPoll]; the first qualifying input across all controllers wins
 * (the coordinator owns that rule).
 *
 * Thread model: [tick] must be invoked from a single thread — the pump's own loop while
 * started, or the test thread when driven manually. Same assumption as
 * [DesktopCaptureCoordinator.onPoll].
 */
class DesktopCapturePump(
    private val source: JInputSource,
    private val capture: DesktopCaptureCoordinator,
    private val scope: CoroutineScope,
    private val pollIntervalMillis: Long = DEFAULT_POLL_INTERVAL_MILLIS,
) {
    private var job: Job? = null

    /** Start the poll loop. Idempotent. */
    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                tick()
                delay(pollIntervalMillis)
            }
        }
    }

    /** Stop the poll loop and release the coroutine. Idempotent. */
    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * One pump iteration: no-op while capture is inactive; otherwise poll every enumerated
     * controller once and feed each sample into the capture coordinator. Also callable
     * directly for deterministic tests.
     */
    internal fun tick() {
        if (!captureActive(capture.state.value)) return
        for (controller in source.enumerate()) {
            capture.onPoll(controller.id, controller.poll())
        }
    }

    companion object {
        /** ~60 Hz poll rate, matching [DesktopControllerRouter]'s default. */
        const val DEFAULT_POLL_INTERVAL_MILLIS = 16L
    }
}

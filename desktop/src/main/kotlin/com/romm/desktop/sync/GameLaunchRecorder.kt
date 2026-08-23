package com.romm.desktop.sync

import com.romm.androidtv.emulation.model.SavePathPolicy
import com.romm.androidtv.romm.PlaySessionEntry
import com.romm.androidtv.romm.PlaySessionIngestRequest
import com.romm.androidtv.romm.PlaySessionIngestResult
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Marks a ROM as played when its player session begins (desktop mirror of Android's
 * `com.romm.androidtv.romm.save.GameLaunchRecorder`).
 *
 * RomM's play-session endpoint requires a positive duration. A one-millisecond interval ending
 * at launch preserves that contract while making `last_played` reflect the launch immediately —
 * this is what makes a title appear in the RomM Home screen's "Continue Playing" row.
 *
 * Best-effort by design: the report runs on a background thread and EVERY failure is logged and
 * swallowed — a play-session report must never block or break the launch/exit flow. Kiosk
 * (anonymous) sessions are skipped, while an unavailable device identity is omitted from the
 * request because the play-session endpoint accepts it as optional.
 */
class GameLaunchRecorder(
    private val gateway: RommSyncGateway,
    private val sessionReader: SaveSyncSessionReader,
    private val deviceIdentityLoader: SaveSyncDeviceIdentityLoader,
    private val executor: Executor = DEFAULT_EXECUTOR,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val log: Logger = Logger.getLogger("GameLaunchRecorder"),
    private val onRecorded: (Long) -> Unit = {},
) {
    /**
     * Records a 1ms play session for [romId] ending at the launch instant. Non-blocking: the
     * gateway call (a blocking HTTP round trip) happens on [executor], never on the caller's
     * thread.
     */
    fun recordLaunch(romId: Long) {
        val launchedAtEpochMs = clock()
        try {
            executor.execute {
                try {
                    // Null session == not signed in OR kiosk (coherentRecord is null without a
                    // non-blank username) — Android skips play-session telemetry in both cases.
                    val session = sessionReader.current() ?: return@execute
                    val username = session.username ?: return@execute
                    // A device identity enriches the report but is not required by RomM's
                    // play-session endpoint. Do not lose the user's recently-played update
                    // when device registration is unavailable.
                    val deviceId = deviceIdentityLoader.load(session.origin, username)?.rommDeviceId

                    val result = gateway.ingestPlaySessions(
                        session.origin,
                        PlaySessionIngestRequest(
                            deviceId = deviceId,
                            sessions = listOf(
                                PlaySessionEntry(
                                    romId = romId,
                                    saveSlot = SavePathPolicy.AUTOSAVE_SLOT,
                                    startTime = Instant.ofEpochMilli(launchedAtEpochMs - 1L),
                                    endTime = Instant.ofEpochMilli(launchedAtEpochMs),
                                    durationMs = 1L,
                                ),
                            ),
                        ),
                    )
                    if (result is PlaySessionIngestResult.Failure) {
                        log.warning("recordLaunch: failed for ROM $romId error=${result.error} httpCode=${result.httpCode}")
                    } else {
                        onRecorded(romId)
                    }
                } catch (e: Exception) {
                    log.log(Level.WARNING, "recordLaunch: threw for ROM $romId", e)
                }
            }
        } catch (e: Exception) {
            // Scheduling itself failed (rejected executor) — still never break the launch.
            log.log(Level.WARNING, "recordLaunch: could not schedule report for ROM $romId", e)
        }
    }

    private companion object {
        /** One shared daemon thread; launches are rare and the report is best-effort. */
        val DEFAULT_EXECUTOR: Executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "play-session-recorder").apply { isDaemon = true }
        }
    }
}

package com.romm.androidtv.romm.save

import android.util.Log

/**
 * Marks a ROM as played when its emulator session begins.
 *
 * RomM normalizes timestamps to whole seconds before validating them. A one-second interval
 * ending at launch remains valid after normalization and updates `last_played` immediately.
 */
internal class GameLaunchRecorder(
    private val recordPlaySession: suspend (PlaySessionRecordRequest) -> PlaySessionRecordResult,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val logTag: String = "GameLaunchRecorder",
) {
    suspend fun recordLaunch(romId: Long) {
        val launchedAtEpochMs = clock()
        try {
            val result = recordPlaySession(
                PlaySessionRecordRequest(
                    romId = romId,
                    slot = com.romm.androidtv.emulation.model.SavePathPolicy.AUTOSAVE_SLOT,
                    startEpochMs = launchedAtEpochMs - LAUNCH_SESSION_DURATION_MS,
                    endEpochMs = launchedAtEpochMs,
                )
            )
            if (result is PlaySessionRecordResult.Failure) {
                Log.w(logTag, "recordLaunch: failed for ROM $romId error=${result.error}")
            }
        } catch (e: Exception) {
            Log.w(logTag, "recordLaunch: threw for ROM $romId", e)
        }
    }

    private companion object {
        const val LAUNCH_SESSION_DURATION_MS = 1_000L
    }
}

package com.romm.androidtv.romm.save

import android.util.Log

/**
 * Marks a ROM as played when its emulator session begins.
 *
 * RomM's play-session endpoint requires a positive duration. A one-millisecond interval ending
 * at launch preserves that contract while making `last_played` reflect the launch immediately.
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
                    startEpochMs = launchedAtEpochMs - 1L,
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
}

package com.romm.androidtv.romm

/**
 * Pure mapper from [StagingOutcome] to concise, actionable user-facing text.
 *
 * Never uses raw `toString()` or object identity. Every sealed variant is
 * mapped to a short sentence the user can act on. Tested on JVM without
 * Android dependencies.
 */
object StagingOutcomeMessage {

    /** Maps any [StagingOutcome] to a concise actionable message for display. */
    fun toUserMessage(outcome: StagingOutcome): String = when (outcome) {
        is StagingOutcome.Success -> "Ready to launch"
        is StagingOutcome.NoApprovedCore ->
            "No approved emulator core for ${outcome.platformSlug}"
        is StagingOutcome.UnsupportedMultiFile ->
            "This ROM has ${outcome.fileCount} files; only single-file ROMs are supported"
        is StagingOutcome.UnsupportedArchiveFormat ->
            "Archive format .${outcome.extension} is not supported"
        is StagingOutcome.ArchiveExtractionFailed ->
            "Could not extract ROM archive: ${outcome.reason}"
        is StagingOutcome.RomNotFound ->
            "ROM not found on server"
        is StagingOutcome.AuthExpired ->
            "Session expired; please log in again"
        is StagingOutcome.InsufficientSpace ->
            "Not enough storage space; need ${(outcome.requiredBytes / 1024)} KB, ${(outcome.availableBytes / 1024)} KB available"
        is StagingOutcome.CorruptedDownload ->
            "Download corrupted: ${outcome.reason}"
        is StagingOutcome.NetworkError ->
            outcome.message.ifBlank { "Network error" }
    }
}

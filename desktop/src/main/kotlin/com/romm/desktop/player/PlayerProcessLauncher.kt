package com.romm.desktop.player

import java.nio.file.Path

/**
 * Spawns the `rommulus-player` process for a prepared launch request (§12.1):
 * `rommulus-player --request <trusted-request-file>`. The request file path is derived from
 * the validated [PlayerRequest.sessionId] — never from caller input.
 */
interface PlayerProcessLauncher {
    fun launch(request: PlayerRequest): LaunchOutcome
}

/** Outcome of a spawn attempt. */
sealed interface LaunchOutcome {
    /** The player process started; [pid] identifies it (liveness tracking lands in a later wave). */
    data class Started(val pid: Long) : LaunchOutcome

    /** Spawn failed (binary missing, request file invalid, OS error). No player is running. */
    data class Error(val message: String) : LaunchOutcome
}

/**
 * Production [PlayerProcessLauncher]: a thin `ProcessBuilder` wrapper.
 *
 * The player binary path is configurable — the `rommulus-player` executable does not exist
 * yet (Phase 8 Wave 3+), so this wrapper is deliberately not exercised by unit tests; the
 * supervisor logic is tested through [PlayerProcessLauncher] with a fake.
 *
 * TOCTOU hardening: before spawning, the request file is re-resolved through [SecureFiles]
 * (canonical, regular, non-symlink) and re-verified to live under the journals root — a
 * previously validated path is never handed to the child process without re-verification.
 */
class ProcessBuilderPlayerLauncher(
    private val playerBinaryPath: Path,
    private val journalsRoot: Path,
    /** Test seam: replaces `ProcessBuilder.start()`. */
    private val starter: (List<String>) -> Process = { command ->
        ProcessBuilder(command).redirectErrorStream(true).start()
    },
) : PlayerProcessLauncher {

    override fun launch(request: PlayerRequest): LaunchOutcome = runCatching {
        val rawPath = journalsRoot.resolve(request.sessionId).resolve(LaunchJournalStore.REQUEST_FILE_NAME)
        val requestFile = SecureFiles.resolveExistingRegular(rawPath).getOrThrow()
        if (!SecureFiles.isWithin(requestFile, journalsRoot.toRealPath())) {
            throw SecurityException("request file escapes the journals root: $requestFile")
        }
        starter(listOf(playerBinaryPath.toString(), "--request", requestFile.toString()))
    }.fold(
        onSuccess = { process -> LaunchOutcome.Started(process.pid()) },
        onFailure = { e -> LaunchOutcome.Error("failed to spawn player: ${e.message ?: e::class.java.simpleName}") },
    )

    companion object {
        /** Default binary location: `rommulus-player` resolved via PATH. */
        fun defaultFor(journalsRoot: Path, playerBinaryPath: Path = Path.of("rommulus-player")): ProcessBuilderPlayerLauncher =
            ProcessBuilderPlayerLauncher(playerBinaryPath, journalsRoot)
    }
}

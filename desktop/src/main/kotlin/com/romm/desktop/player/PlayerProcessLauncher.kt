package com.romm.desktop.player

import com.romm.androidtv.storage.AppPaths
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
 *
 * Env vars (§12.1): the launcher sets `ROMM_PLAYER_CORE_ROOT`, `ROMM_PLAYER_CACHE_ROOT`,
 * `ROMM_PLAYER_DATA_ROOT`, `ROMM_PLAYER_STATE_ROOT`, `ROMM_PLAYER_ALLOWED_CORES`, and
 * `ROMM_PLAYER_EXPECTED_CONTENT_HASH` on the child process from the desktop's [AppPaths]
 * and the request's content hash.
 */
class ProcessBuilderPlayerLauncher(
    private val playerBinaryPath: Path,
    private val journalsRoot: Path,
    private val appPaths: AppPaths,
    /** Test seam: replaces `ProcessBuilder.start()` with full env-var capture. */
    private val starter: (List<String>, Map<String, String>) -> Process = { command, env ->
        ProcessBuilder(command).apply { environment().putAll(env) }.redirectErrorStream(true).start()
    },
) : PlayerProcessLauncher {

    override fun launch(request: PlayerRequest): LaunchOutcome = runCatching {
        val rawPath = journalsRoot.resolve(request.sessionId).resolve(LaunchJournalStore.REQUEST_FILE_NAME)
        val requestFile = SecureFiles.resolveExistingRegular(rawPath).getOrThrow()
        if (!SecureFiles.isWithin(requestFile, journalsRoot.toRealPath())) {
            throw SecurityException("request file escapes the journals root: $requestFile")
        }
        val envVars = buildEnvVars(request)
        val command = listOf(playerBinaryPath.toString(), "--request", requestFile.toString())
        starter(command, envVars)
    }.fold(
        onSuccess = { process -> LaunchOutcome.Started(process.pid()) },
        onFailure = { e -> LaunchOutcome.Error("failed to spawn player: ${e.message ?: e::class.java.simpleName}") },
    )

    private fun buildEnvVars(request: PlayerRequest): Map<String, String> = buildMap {
        put("ROMM_PLAYER_CORE_ROOT", appPaths.dataDir.resolve("cores").toString())
        put("ROMM_PLAYER_CACHE_ROOT", appPaths.cacheDir.toString())
        put("ROMM_PLAYER_DATA_ROOT", appPaths.dataDir.toString())
        put("ROMM_PLAYER_STATE_ROOT", appPaths.stateDir.toString())
        put("ROMM_PLAYER_ALLOWED_CORES", ALLOWED_CORES)
        request.contentHash.takeIf { it.isNotEmpty() }?.let {
            put("ROMM_PLAYER_EXPECTED_CONTENT_HASH", it)
        }
    }

    companion object {
        /** Allowed cores: `coreId=revision` pairs, semicolon-separated. */
        const val ALLOWED_CORES = "test_core=1"

        /** Default binary location: `rommulus_player` resolved via PATH (matches the CMake executable). */
        fun defaultFor(journalsRoot: Path, appPaths: AppPaths, playerBinaryPath: Path = Path.of("rommulus_player")): ProcessBuilderPlayerLauncher =
            ProcessBuilderPlayerLauncher(playerBinaryPath, journalsRoot, appPaths)
    }
}

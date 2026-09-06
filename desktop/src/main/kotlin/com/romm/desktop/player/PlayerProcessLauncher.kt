package com.romm.desktop.player

import com.romm.androidtv.storage.AppPaths
import com.romm.desktop.platform.LinuxNativeArtifactLayout
import com.romm.desktop.platform.NativeArtifactLayout
import java.io.File
import java.nio.file.Files
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
 * The player binary path is configurable. Unit tests exercise the spawn path through the
 * [starter] seam (command + env-var capture, no real binary); the supervisor logic is tested
 * through [PlayerProcessLauncher] with a fake.
 *
 * TOCTOU hardening: before spawning, the request file is re-resolved through [SecureFiles]
 * (canonical, regular, non-symlink) and re-verified to live under the journals root — a
 * previously validated path is never handed to the child process without re-verification.
 *
 * Env vars (§12.1): the launcher sets `ROMM_PLAYER_CORE_ROOT`, `ROMM_PLAYER_CACHE_ROOT`,
 * `ROMM_PLAYER_DATA_ROOT`, `ROMM_PLAYER_STATE_ROOT`, `ROMM_PLAYER_ALLOWED_CORES`, and
 * `ROMM_PLAYER_EXPECTED_CONTENT_HASH` on the child process from the desktop's [AppPaths]
 * and the request's content hash. `ROMM_PLAYER_ALLOWED_CORES` is derived at launch time via
 * [NativeArtifactLayout.deriveAllowedCores] over [NativeArtifactLayout.scanInstalledCoreIds]
 * of the cores root — no hard-coded list.
 *
 * Diagnostics: the player's combined stdout+stderr are drained (async daemon thread, see
 * [PlayerLogCapture]) into the bounded per-session log `<sessionDir>/player.log` — never
 * discarded (the core's serial/renderer/shader diagnostics land there) and never left in an
 * unread pipe (a full pipe deadlocks the player). A failed spawn is recorded in the same file
 * as a `[pre-launch]` line, so launch failures are diagnosable on-device too.
 */
class ProcessBuilderPlayerLauncher(
    private val playerBinaryPath: Path,
    private val journalsRoot: Path,
    private val appPaths: AppPaths,
    /**
     * Platform artifact layout (player/core naming + installed-core scan). Defaults to the
     * Linux layout, preserving the historical desktop behavior.
     */
    private val layout: NativeArtifactLayout = LinuxNativeArtifactLayout,
    private val coresDirectory: Path = appPaths.dataDir.resolve("cores"),
    /** Test seam: replaces `ProcessBuilder.start()` with full env-var capture. */
    private val starter: (List<String>, Map<String, String>) -> Process = { command, env ->
        ProcessBuilder(command).apply {
            environment().putAll(env)
            // Merge stderr into stdout so the async drain captures both in one stream. The
            // output stays piped (the default): [launch] drains it into the bounded per-session
            // log, so the pipe is never left unread (a full pipe deadlocks the player) and the
            // core's diagnostics are never discarded.
            redirectErrorStream(true)
        }.start()
    },
) : PlayerProcessLauncher {

    override fun launch(request: PlayerRequest): LaunchOutcome {
        // Per-session log capture, opened BEFORE the spawn so a failed spawn (missing binary,
        // invalid request file) is recorded in the same file the player's own diagnostics land
        // in. The session directory was prepared by the supervisor; if it is absent the
        // capture degrades to a no-op (no orphan directory is created).
        val playerLog = runCatching {
            SecureFiles.requireSessionId(request.sessionId).getOrThrow()
            PlayerLogCapture(journalsRoot.resolve(request.sessionId).resolve(LaunchJournalStore.PLAYER_LOG_FILE_NAME))
        }.getOrElse { e ->
            return LaunchOutcome.Error("failed to spawn player: ${e.message ?: e::class.java.simpleName}")
        }
        return runCatching {
            val rawPath = journalsRoot.resolve(request.sessionId).resolve(LaunchJournalStore.REQUEST_FILE_NAME)
            val requestFile = SecureFiles.resolveExistingRegular(rawPath).getOrThrow()
            if (!SecureFiles.isWithin(requestFile, journalsRoot.toRealPath())) {
                throw SecurityException("request file escapes the journals root: $requestFile")
            }
            val envVars = buildEnvVars(request)
            val command = listOf(playerBinaryPath.toString(), "--request", requestFile.toString())
            val process = starter(command, envVars)
            try {
                playerLog.startDraining(process)
            } catch (e: Exception) {
                // Without a drain the pipe would fill and deadlock the player: fail closed.
                runCatching { process.destroy() }
                throw e
            }
            process
        }.fold(
            onSuccess = { process -> LaunchOutcome.Started(process.pid()) },
            onFailure = { e ->
                playerLog.appendLine("[pre-launch] failed to spawn player: ${e.message ?: e::class.java.simpleName}")
                playerLog.close()
                LaunchOutcome.Error("failed to spawn player: ${e.message ?: e::class.java.simpleName}")
            },
        )
    }

    private fun buildEnvVars(request: PlayerRequest): Map<String, String> = buildMap {
        val coresDir = coresDirectory
        put("ROMM_PLAYER_CORE_ROOT", coresDir.toString())
        put("ROMM_PLAYER_CACHE_ROOT", appPaths.cacheDir.toString())
        put("ROMM_PLAYER_DATA_ROOT", appPaths.dataDir.toString())
        put("ROMM_PLAYER_STATE_ROOT", appPaths.stateDir.toString())
        put("ROMM_PLAYER_ALLOWED_CORES", layout.deriveAllowedCores(layout.scanInstalledCoreIds(coresDir)))
        request.contentHash.takeIf { it.isNotEmpty() }?.let {
            put("ROMM_PLAYER_EXPECTED_CONTENT_HASH", it)
        }
    }

    companion object {
        /**
         * Resolves the locally built player for development runs; release bundles put the
         * layout's player executable on PATH through their launcher.
         */
        fun defaultFor(
            journalsRoot: Path,
            appPaths: AppPaths,
            layout: NativeArtifactLayout = LinuxNativeArtifactLayout,
            playerBinaryPath: Path = Path.of(layout.playerExecutableName),
            coresDirectory: Path = appPaths.dataDir.resolve("cores"),
        ): ProcessBuilderPlayerLauncher {
            val resolvedPlayer = if (playerBinaryPath == Path.of(layout.playerExecutableName)) {
                findExecutableOnPath(playerBinaryPath.fileName.toString())
                    ?: findDevelopmentPlayer(layout)
                    ?: playerBinaryPath
            } else {
                playerBinaryPath
            }
            return ProcessBuilderPlayerLauncher(
                resolvedPlayer, journalsRoot, appPaths, layout, coresDirectory,
            )
        }

        private fun findDevelopmentPlayer(layout: NativeArtifactLayout): Path? {
            var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            while (true) {
                val candidate = directory.resolve("build").resolve("player").resolve(layout.playerExecutableName)
                if (Files.isExecutable(candidate)) return candidate
                directory = directory.parent ?: return null
            }
        }
    }
}

internal fun findExecutableOnPath(
    executableName: String,
    pathValue: String? = System.getenv("PATH"),
): Path? {
    if (pathValue.isNullOrBlank()) return null
    return pathValue.split(File.pathSeparatorChar)
        .asSequence()
        .filter { it.isNotBlank() }
        .map { Path.of(it).toAbsolutePath().normalize().resolve(executableName) }
        .firstOrNull { Files.isRegularFile(it) && Files.isExecutable(it) }
}

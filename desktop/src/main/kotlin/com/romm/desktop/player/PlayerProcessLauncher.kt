package com.romm.desktop.player

import com.romm.androidtv.emulation.model.CoreManifest
import com.romm.androidtv.storage.AppPaths
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * The desktop player's host ABI. A core is launchable on the Linux desktop only when its
 * [com.romm.androidtv.emulation.model.CoreLicenseFinding.supportedAbis] contains this value
 * (plans/LINUX_X64.md §13.1: `linux-x86_64` is a first-class build identity, not an Android ABI).
 */
const val LINUX_X86_64_ABI = "linux-x86_64"

/**
 * On-disk shared-library file names that may carry [coreId], in preference order.
 *
 * CMake names core targets `<coreId>_core` (e.g. `gambatte_core` → `libgambatte_core.so`),
 * but the synthetic `test_core` target is named `test_core` itself (→ `libtest_core.so`), so
 * both spellings are accepted wherever a core library is resolved.
 */
fun coreLibraryFileNames(coreId: String): List<String> =
    listOf("lib$coreId.so", "lib${coreId}_core.so")

/**
 * Scans [coresDir] for installed core shared libraries (`lib*.so` regular files) and returns
 * the extracted core ids, sorted for determinism. A missing (or non-directory) [coresDir]
 * yields an empty list.
 *
 * Extraction: strip the `lib` prefix and `.so` suffix, then a trailing `_core` CMake target
 * suffix (`libgambatte_core.so` → `gambatte`). Note the `_core` strip is lossy for the
 * synthetic `test_core` (`libtest_core.so` → `test`); [deriveAllowedCores] resolves that
 * ambiguity against [CoreManifest].
 */
fun scanInstalledCoreIds(coresDir: Path): List<String> {
    if (!Files.isDirectory(coresDir)) return emptyList()
    val names = Files.list(coresDir).use { stream ->
        stream
            .filter { Files.isRegularFile(it) }
            .map { it.fileName.toString() }
            .filter { it.startsWith("lib") && it.endsWith(".so") }
            .toList()
    }
    return names
        .map { it.removePrefix("lib").removeSuffix(".so").removeSuffix("_core") }
        .filter { it.isNotEmpty() }
        .distinct()
        .sorted()
}

/**
 * Derives the `ROMM_PLAYER_ALLOWED_CORES` value from the installed [installedCoreIds]:
 * `coreId=revision` pairs, semicolon-joined, sorted by coreId for determinism.
 *
 * Only cores that exist in [CoreManifest], are approved, AND support [LINUX_X86_64_ABI] are
 * emitted. The revision is the manifest's [com.romm.androidtv.emulation.model.CoreLicenseFinding.releaseTag],
 * falling back to [com.romm.androidtv.emulation.model.CoreLicenseFinding.commitSha] when the tag
 * is blank (gambatte carries no upstream release tags). Unknown ids are dropped.
 */
fun deriveAllowedCores(installedCoreIds: Collection<String>): String =
    installedCoreIds.distinct()
        .mapNotNull { id ->
            // The scan's `_core` strip is lossy for the synthetic test_core (libtest_core.so →
            // "test"); recover it by retrying with the CMake target suffix.
            CoreManifest.findById(id) ?: CoreManifest.findById("${id}_core")
        }
        .filter { it.approved && LINUX_X86_64_ABI in it.supportedAbis }
        .sortedBy { it.coreId }
        .joinToString(";") { core -> "${core.coreId}=${core.releaseTag.ifBlank { core.commitSha }}" }

/**
 * Resolves the on-disk core library for [coreId] under [coresDir]: the first existing
 * candidate from [coreLibraryFileNames]. When nothing is installed, falls back to the
 * canonical `lib<coreId>.so` so the player rejects the request with a clear missing-file
 * error instead of the desktop failing to compose a path.
 */
fun resolveCoreLibraryPath(coresDir: Path, coreId: String): Path =
    coresDir.resolve(
        coreLibraryFileNames(coreId).firstOrNull { Files.exists(coresDir.resolve(it)) }
            ?: "lib$coreId.so",
    )

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
 * [deriveAllowedCores] over [scanInstalledCoreIds] of the cores root — no hard-coded list.
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
        val coresDir = appPaths.dataDir.resolve("cores")
        put("ROMM_PLAYER_CORE_ROOT", coresDir.toString())
        put("ROMM_PLAYER_CACHE_ROOT", appPaths.cacheDir.toString())
        put("ROMM_PLAYER_DATA_ROOT", appPaths.dataDir.toString())
        put("ROMM_PLAYER_STATE_ROOT", appPaths.stateDir.toString())
        put("ROMM_PLAYER_ALLOWED_CORES", deriveAllowedCores(scanInstalledCoreIds(coresDir)))
        request.contentHash.takeIf { it.isNotEmpty() }?.let {
            put("ROMM_PLAYER_EXPECTED_CONTENT_HASH", it)
        }
    }

    companion object {
        /**
         * Resolves the locally built player for development runs; release bundles put
         * `rommulus_player` on PATH through their launcher.
         */
        fun defaultFor(
            journalsRoot: Path,
            appPaths: AppPaths,
            playerBinaryPath: Path = Path.of("rommulus_player"),
        ): ProcessBuilderPlayerLauncher {
            val resolvedPlayer = if (playerBinaryPath == Path.of("rommulus_player")) {
                findExecutableOnPath(playerBinaryPath.fileName.toString())
                    ?: findDevelopmentPlayer()
                    ?: playerBinaryPath
            } else {
                playerBinaryPath
            }
            return ProcessBuilderPlayerLauncher(resolvedPlayer, journalsRoot, appPaths)
        }

        private fun findDevelopmentPlayer(): Path? {
            var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
            while (true) {
                val candidate = directory.resolve("build").resolve("player").resolve("rommulus_player")
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

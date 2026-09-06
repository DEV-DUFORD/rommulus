package com.romm.desktop.player

import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.firmwareDir
import com.romm.desktop.PosixTestSupport
import com.romm.desktop.platform.LinuxNativeArtifactLayout
import com.romm.desktop.platform.WindowsNativeArtifactLayout
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.concurrent.TimeUnit

@DisplayName("ProcessBuilderPlayerLauncher — allowed-cores derivation")
class PlayerProcessLauncherTest {

    // ---------------------------------------------------------------- deriveAllowedCores

    @Test
    fun `deriveAllowedCores emits approved linux-x86_64 cores as coreId=revision pairs in sorted order`() {
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("beetle_pce_fast", "dolphin", "fceumm", "gambatte", "genesis_plus_gx", "handy", "mednafen_ngp", "mednafen_wswan", "mgba", "mupen64plus_next", "pcsx_rearmed", "prosystem", "snes9x", "stella", "test_core")))
            .isEqualTo("beetle_pce_fast=b211204c7026dff6e86e79b00185512e2421fff8;dolphin=841bacadb5d5c3f9acba0dc652d306ecd77a7bbf;fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;genesis_plus_gx=ca93fec870378f3bff65931bcd828d5e756cce75;handy=bc55d462f0b2d6b073ea93dc552ebd73cec60fd1;mednafen_ngp=a50d5ac288a81f2104ddf43195a4efdd15c72227;mednafen_wswan=4b01295838ea89e3f1355bbe4cb5cf98aa6108cd;mgba=32de792178a3662cd0402c8568fccfaad4a764a1;mupen64plus_next=98c1b0d877542b01314b3b04272282ba223b65b3;pcsx_rearmed=da2cb8ecd17fd0932ab6d94774c0522beebce6e3;prosystem=363b6dfbd3e240762e022c2b4897b4fe55722be3;snes9x=1.63;stella=7.0;test_core=1")
        // Input order must not matter: the output is sorted by coreId.
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("test_core", "snes9x", "stella", "prosystem", "pcsx_rearmed", "mupen64plus_next", "mednafen_wswan", "handy", "mgba", "fceumm", "gambatte", "genesis_plus_gx", "beetle_pce_fast", "mednafen_ngp", "dolphin")))
            .isEqualTo("beetle_pce_fast=b211204c7026dff6e86e79b00185512e2421fff8;dolphin=841bacadb5d5c3f9acba0dc652d306ecd77a7bbf;fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;genesis_plus_gx=ca93fec870378f3bff65931bcd828d5e756cce75;handy=bc55d462f0b2d6b073ea93dc552ebd73cec60fd1;mednafen_ngp=a50d5ac288a81f2104ddf43195a4efdd15c72227;mednafen_wswan=4b01295838ea89e3f1355bbe4cb5cf98aa6108cd;mgba=32de792178a3662cd0402c8568fccfaad4a764a1;mupen64plus_next=98c1b0d877542b01314b3b04272282ba223b65b3;pcsx_rearmed=da2cb8ecd17fd0932ab6d94774c0522beebce6e3;prosystem=363b6dfbd3e240762e022c2b4897b4fe55722be3;snes9x=1.63;stella=7.0;test_core=1")
    }

    @Test
    fun `deriveAllowedCores excludes coreIds that are not in the manifest`() {
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("not_a_real_core"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores excludes non-approved cores`() {
        // sameboy is in the manifest but not approved.
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("sameboy"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores excludes cores whose supportedAbis lack linux-x86_64`() {
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(listOf("sameboy"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores emits an empty string when nothing is installed`() {
        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(emptyList())).isEmpty()
    }

    // ---------------------------------------------------------------- scanInstalledCoreIds

    @Test
    fun `scanInstalledCoreIds extracts sorted core ids from core shared libraries`(@TempDir dir: Path) {
        Files.write(dir.resolve("libgambatte_core.so"), byteArrayOf(0))
        Files.write(dir.resolve("libfoo.so"), byteArrayOf(0))
        Files.write(dir.resolve("notes.txt"), byteArrayOf(0))

        assertThat(LinuxNativeArtifactLayout.scanInstalledCoreIds(dir)).containsExactly("foo", "gambatte")
    }

    @Test
    fun `scanInstalledCoreIds returns an empty list when the directory does not exist`() {
        assertThat(LinuxNativeArtifactLayout.scanInstalledCoreIds(Path.of("/nonexistent", "rommulus", "cores"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores recovers test_core from the lossy CMake suffix strip`(@TempDir dir: Path) {
        // The scan strips the CMake `_core` suffix (libtest_core.so → "test"); the derivation
        // must recover the manifest's test_core entry so the fallback stays allowlisted.
        Files.write(dir.resolve("libtest_core.so"), byteArrayOf(0))

        assertThat(LinuxNativeArtifactLayout.deriveAllowedCores(LinuxNativeArtifactLayout.scanInstalledCoreIds(dir))).isEqualTo("test_core=1")
    }

    @Test
    fun `player resolution uses the first executable on PATH`(@TempDir dir: Path) {
        // Execute-bit semantics are POSIX-only; on NTFS every regular file is "executable".
        PosixTestSupport.assumePosixFilesystem(dir)
        val bundled = Files.createDirectories(dir.resolve("bundle-bin")).resolve("rommulus_player")
        val later = Files.createDirectories(dir.resolve("later-bin")).resolve("rommulus_player")
        Files.writeString(bundled, "bundled")
        Files.writeString(later, "later")
        val executablePermissions = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        Files.setPosixFilePermissions(bundled, executablePermissions)
        Files.setPosixFilePermissions(later, executablePermissions)

        val result = findExecutableOnPath(
            "rommulus_player",
            "${bundled.parent}${java.io.File.pathSeparator}${later.parent}",
        )

        assertThat(result).isEqualTo(bundled)
    }

    @Test
    fun `player resolution ignores non-executable PATH entries`(@TempDir dir: Path) {
        // "Non-executable" only exists where POSIX execute bits exist.
        PosixTestSupport.assumePosixFilesystem(dir)
        val nonExecutable = Files.createDirectories(dir.resolve("bin")).resolve("rommulus_player")
        Files.writeString(nonExecutable, "not executable")

        assertThat(findExecutableOnPath("rommulus_player", nonExecutable.parent.toString())).isNull()
    }

    // ---------------------------------------------------------------- launch integration

    @Test
    fun `Windows launch uses packaged core root rather than user cores`(@TempDir dir: Path) {
        val paths = TestAppPaths(dir.resolve("profile"))
        val cores = Files.createDirectories(dir.resolve("installation/native/cores"))
        Files.write(cores.resolve("gambatte_core.dll"), byteArrayOf(0))
        val journals = Files.createDirectories(paths.stateDir.resolve("journals"))
        Files.createDirectories(journals.resolve("session-1"))
        Files.writeString(journals.resolve("session-1/request.json"), "{}")
        var environment: Map<String, String> = emptyMap()
        var command: List<String> = emptyList()
        val executable = dir.resolve("installation/native/rommulus-player.exe")
        val launcher = ProcessBuilderPlayerLauncher(
            playerBinaryPath = executable,
            journalsRoot = journals,
            appPaths = paths,
            layout = WindowsNativeArtifactLayout,
            coresDirectory = cores,
            starter = { args, env ->
                command = args
                environment = env
                FakeProcess(42)
            },
        )
        assertThat(launcher.launch(requestFor(paths, cores))).isEqualTo(LaunchOutcome.Started(42))
        assertThat(command.first()).isEqualTo(executable.toString())
        assertThat(environment["ROMM_PLAYER_CORE_ROOT"]).isEqualTo(cores.toString())
        assertThat(environment["ROMM_PLAYER_DATA_ROOT"]).isEqualTo(paths.dataDir.toString())
    }

    /** Minimal [Process] stand-in for the [ProcessBuilderPlayerLauncher] starter seam. */
    private class FakeProcess(private val pidValue: Long) : Process() {
        override fun destroy() {}
        override fun exitValue(): Int = 0
        override fun isAlive(): Boolean = false
        override fun pid(): Long = pidValue
        override fun waitFor(): Int = 0
        override fun getErrorStream(): InputStream = InputStream.nullInputStream()
        override fun getInputStream(): InputStream = InputStream.nullInputStream()
        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    }

    /** Wires a launcher whose starter seam captures the command + env vars instead of spawning. */
    private fun capturingLauncher(
        dir: Path,
        paths: TestAppPaths,
        journalsRoot: Path,
        capturedEnv: Array<Map<String, String>?>,
    ): ProcessBuilderPlayerLauncher {
        val sessionId = "session-1"
        Files.createDirectories(journalsRoot.resolve(sessionId))
        Files.writeString(journalsRoot.resolve(sessionId).resolve("request.json"), "{}")
        return ProcessBuilderPlayerLauncher(
            playerBinaryPath = dir.resolve("rommulus_player"),
            journalsRoot = journalsRoot,
            appPaths = paths,
            starter = { _, env ->
                capturedEnv[0] = env
                FakeProcess(pidValue = 4242L)
            },
        )
    }

    private fun requestFor(paths: TestAppPaths, coresDir: Path): PlayerRequest {
        val sessionId = "session-1"
        return PlayerRequest(
            sessionId = sessionId,
            coreId = "gambatte",
            coreBuildRevision = "96174369b3c30d9fc57c926fa3379c273dc6a9a5",
            corePath = coresDir.resolve("libgambatte_core.so").toString(),
            contentPath = "",
            contentHash = "",
            systemDir = paths.firmwareDir().toString(),
            savePath = paths.dataDir.resolve("saves").resolve("$sessionId.srm").toString(),
            candidateSavePath = paths.dataDir.resolve("saves").resolve("$sessionId.srm").toString(),
            resultPath = paths.stateDir.resolve("journals").resolve(sessionId).resolve("result.json").toString(),
        )
    }

    @Test
    fun `launch sets ROMM_PLAYER_ALLOWED_CORES from the installed cores directory`(@TempDir dir: Path) {
        val paths = TestAppPaths(dir)
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte_core.so"), byteArrayOf(0))
        val capturedEnv = Array<Map<String, String>?>(1) { null }
        val launcher = capturingLauncher(dir, paths, dir.resolve("journals"), capturedEnv)

        val outcome = launcher.launch(requestFor(paths, coresDir))

        assertThat(outcome).isEqualTo(LaunchOutcome.Started(4242L))
        assertThat(capturedEnv[0]!!["ROMM_PLAYER_ALLOWED_CORES"])
            .contains("gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5")
        assertThat(capturedEnv[0]!!["ROMM_PLAYER_CORE_ROOT"]).isEqualTo(coresDir.toString())
    }

    @Test
    fun `launch allowlists test_core when the test core library is installed alongside gambatte`(@TempDir dir: Path) {
        val paths = TestAppPaths(dir)
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        Files.write(coresDir.resolve("libgambatte_core.so"), byteArrayOf(0))
        Files.write(coresDir.resolve("libtest_core.so"), byteArrayOf(0))
        val capturedEnv = Array<Map<String, String>?>(1) { null }
        val launcher = capturingLauncher(dir, paths, dir.resolve("journals"), capturedEnv)

        val outcome = launcher.launch(requestFor(paths, coresDir))

        assertThat(outcome).isEqualTo(LaunchOutcome.Started(4242L))
        assertThat(capturedEnv[0]!!["ROMM_PLAYER_ALLOWED_CORES"])
            .isEqualTo("gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;test_core=1")
    }

    @Test
    fun `launch emits an empty ROMM_PLAYER_ALLOWED_CORES when no cores are installed`(@TempDir dir: Path) {
        val paths = TestAppPaths(dir)
        val coresDir = paths.dataDir.resolve("cores")
        Files.createDirectories(coresDir)
        val capturedEnv = Array<Map<String, String>?>(1) { null }
        val launcher = capturingLauncher(dir, paths, dir.resolve("journals"), capturedEnv)

        val outcome = launcher.launch(requestFor(paths, coresDir))

        assertThat(outcome).isEqualTo(LaunchOutcome.Started(4242L))
        assertThat(capturedEnv[0]!!["ROMM_PLAYER_ALLOWED_CORES"]).isEmpty()
    }

    @Test
    fun `player output larger than a pipe cannot block exit observation or reconciliation`(@TempDir dir: Path) {
        // The synthetic player is a /bin/sh script — Unix hosts only.
        PosixTestSupport.assumeUnixLikeHost()
        val paths = TestAppPaths(dir)
        val journalsRoot = paths.stateDir.resolve("journals")
        val syntheticPlayer = dir.resolve("synthetic-player.sh")
        Files.writeString(
            syntheticPlayer,
            """
            #!/bin/sh
            head -c 2097152 /dev/zero >&2
            sleep 1
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            syntheticPlayer,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val launcher = ProcessBuilderPlayerLauncher(syntheticPlayer, journalsRoot, paths)
        val supervisor = LaunchJournalSupervisor(journalsRoot, launcher)
        val prepared = supervisor.prepareLaunch(
            PlayerLaunchParams(
                coreId = "test_core",
                coreBuildRevision = "test",
                corePath = paths.dataDir.resolve("cores").resolve("libtest_core.so"),
                systemDir = paths.firmwareDir(),
                savePath = paths.dataDir.resolve("saves").resolve("synthetic.srm"),
            ),
            sessionId = "output-regression",
        )
        assertThat(prepared).isInstanceOf(PrepareLaunchResult.Ready::class.java)
        val ready = prepared as PrepareLaunchResult.Ready
        val pid = (ready.launch as LaunchOutcome.Started).pid
        val handle = ProcessHandle.of(pid).orElseThrow()

        try {
            handle.onExit().get(5, TimeUnit.SECONDS)
            assertThat(supervisor.onPlayerExit(ready.session, -1))
                .isInstanceOf(PlayerExitReport.CrashInterrupted::class.java)
        } finally {
            if (handle.isAlive) handle.destroyForcibly()
        }
    }

    // ---------------------------------------------------------------- player log capture

    @Test
    fun `player log capture rotates at the cap and bounds the on-disk footprint`(@TempDir dir: Path) {
        val logFile = dir.resolve("player.log")
        val capture = PlayerLogCapture(logFile, maxBytes = 256)
        // 1000 bytes > cap: must split across the active file and the rotation slot.
        capture.append("x".repeat(1000).toByteArray())
        capture.appendLine("tail")
        capture.close()

        val active = Files.size(logFile)
        val rotated = Files.size(dir.resolve("player.log.1"))
        assertThat(active).isLessThanOrEqualTo(256L)
        assertThat(rotated).isLessThanOrEqualTo(256L)
        assertThat(active + rotated).isLessThanOrEqualTo(512L)
        // The newest content survives rotation.
        assertThat(Files.readString(logFile)).endsWith("tail\n")
    }

    @Test
    fun `player log capture is a no-op when the session directory was never prepared`(@TempDir dir: Path) {
        val logFile = dir.resolve("missing-session").resolve("player.log")
        val capture = PlayerLogCapture(logFile)
        capture.appendLine("should not be written anywhere")
        capture.close()
        // No orphan session directory is created for a session that was never prepared.
        assertThat(Files.exists(dir.resolve("missing-session"))).isFalse()
    }

    @Test
    fun `a failed spawn is recorded in the per-session player log`(@TempDir dir: Path) {
        val paths = TestAppPaths(dir)
        val journalsRoot = dir.resolve("journals")
        val sessionId = "session-1"
        Files.createDirectories(journalsRoot.resolve(sessionId))
        Files.writeString(journalsRoot.resolve(sessionId).resolve("request.json"), "{}")
        val launcher = ProcessBuilderPlayerLauncher(
            playerBinaryPath = dir.resolve("rommulus_player"),
            journalsRoot = journalsRoot,
            appPaths = paths,
            starter = { _, _ -> throw IOException("spawn refused (test)") },
        )

        val outcome = launcher.launch(requestFor(paths, paths.dataDir.resolve("cores")))

        assertThat(outcome).isEqualTo(LaunchOutcome.Error("failed to spawn player: spawn refused (test)"))
        val logFile = journalsRoot.resolve(sessionId).resolve("player.log")
        assertThat(Files.exists(logFile)).isTrue()
        assertThat(Files.readString(logFile)).contains("[pre-launch]").contains("spawn refused (test)")
        if (PosixTestSupport.isPosixFilesystem(logFile)) {
            assertThat(Files.getPosixFilePermissions(logFile))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        }
    }

    @Test
    fun `player output is captured to the per-session log and stays bounded`(@TempDir dir: Path) {
        // The synthetic player is a /bin/sh script — Unix hosts only.
        PosixTestSupport.assumeUnixLikeHost()
        val paths = TestAppPaths(dir)
        val journalsRoot = paths.stateDir.resolve("journals")
        val syntheticPlayer = dir.resolve("chatty-player.sh")
        // 3 MiB of output: exceeds the 2 MiB active-file cap, so a rotation must happen.
        Files.writeString(
            syntheticPlayer,
            """
            #!/bin/sh
            head -c 3145728 /dev/zero
            exit 0
            """.trimIndent(),
        )
        Files.setPosixFilePermissions(
            syntheticPlayer,
            setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            ),
        )
        val launcher = ProcessBuilderPlayerLauncher(syntheticPlayer, journalsRoot, paths)
        val supervisor = LaunchJournalSupervisor(journalsRoot, launcher)
        val prepared = supervisor.prepareLaunch(
            PlayerLaunchParams(
                coreId = "test_core",
                coreBuildRevision = "test",
                corePath = paths.dataDir.resolve("cores").resolve("libtest_core.so"),
                systemDir = paths.firmwareDir(),
                savePath = paths.dataDir.resolve("saves").resolve("chatty.srm"),
            ),
            sessionId = "log-capture",
        )
        assertThat(prepared).isInstanceOf(PrepareLaunchResult.Ready::class.java)
        val ready = prepared as PrepareLaunchResult.Ready
        val pid = (ready.launch as LaunchOutcome.Started).pid
        val handle = ProcessHandle.of(pid).orElseThrow()

        val sessionDir = journalsRoot.resolve("log-capture")
        val logFile = sessionDir.resolve("player.log")
        val rotatedFile = sessionDir.resolve("player.log.1")
        try {
            handle.onExit().get(10, TimeUnit.SECONDS)
            // Wait for the drain thread to finish (EOF → flush + close): the captured total
            // is monotonic and stabilizes at the full output size.
            val deadline = System.currentTimeMillis() + 10_000
            var total = -1L
            while (System.currentTimeMillis() < deadline) {
                total = (if (Files.exists(logFile)) Files.size(logFile) else 0L) +
                    (if (Files.exists(rotatedFile)) Files.size(rotatedFile) else 0L)
                if (total == 3_145_728L) break
                Thread.sleep(50)
            }
            assertThat(total).isEqualTo(3_145_728L)
            assertThat(Files.size(logFile)).isLessThanOrEqualTo(PlayerLogCapture.DEFAULT_MAX_BYTES)
            assertThat(total).isLessThanOrEqualTo(2 * PlayerLogCapture.DEFAULT_MAX_BYTES)
            // The output exceeded the active-file cap, so a rotation must have happened.
            assertThat(Files.exists(rotatedFile)).isTrue()
            assertThat(supervisor.onPlayerExit(ready.session, -1))
                .isInstanceOf(PlayerExitReport.CrashInterrupted::class.java)
        } finally {
            if (handle.isAlive) handle.destroyForcibly()
        }
    }
}

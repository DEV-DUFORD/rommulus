package com.romm.desktop.player

import com.romm.androidtv.storage.TestAppPaths
import com.romm.androidtv.storage.firmwareDir
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path

@DisplayName("ProcessBuilderPlayerLauncher — allowed-cores derivation")
class PlayerProcessLauncherTest {

    // ---------------------------------------------------------------- deriveAllowedCores

    @Test
    fun `deriveAllowedCores emits approved linux-x86_64 cores as coreId=revision pairs in sorted order`() {
        assertThat(deriveAllowedCores(listOf("beetle_pce_fast", "fceumm", "gambatte", "genesis_plus_gx", "handy", "mednafen_ngp", "mednafen_wswan", "mgba", "pcsx_rearmed", "prosystem", "snes9x", "stella", "test_core")))
            .isEqualTo("beetle_pce_fast=b211204c7026dff6e86e79b00185512e2421fff8;fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;genesis_plus_gx=ca93fec870378f3bff65931bcd828d5e756cce75;handy=bc55d462f0b2d6b073ea93dc552ebd73cec60fd1;mednafen_ngp=a50d5ac288a81f2104ddf43195a4efdd15c72227;mednafen_wswan=4b01295838ea89e3f1355bbe4cb5cf98aa6108cd;mgba=32de792178a3662cd0402c8568fccfaad4a764a1;pcsx_rearmed=da2cb8ecd17fd0932ab6d94774c0522beebce6e3;prosystem=363b6dfbd3e240762e022c2b4897b4fe55722be3;snes9x=1.63;stella=7.0;test_core=1")
        // Input order must not matter: the output is sorted by coreId.
        assertThat(deriveAllowedCores(listOf("test_core", "snes9x", "stella", "prosystem", "mednafen_wswan", "handy", "mgba", "pcsx_rearmed", "fceumm", "gambatte", "genesis_plus_gx", "beetle_pce_fast", "mednafen_ngp")))
            .isEqualTo("beetle_pce_fast=b211204c7026dff6e86e79b00185512e2421fff8;fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;genesis_plus_gx=ca93fec870378f3bff65931bcd828d5e756cce75;handy=bc55d462f0b2d6b073ea93dc552ebd73cec60fd1;mednafen_ngp=a50d5ac288a81f2104ddf43195a4efdd15c72227;mednafen_wswan=4b01295838ea89e3f1355bbe4cb5cf98aa6108cd;mgba=32de792178a3662cd0402c8568fccfaad4a764a1;pcsx_rearmed=da2cb8ecd17fd0932ab6d94774c0522beebce6e3;prosystem=363b6dfbd3e240762e022c2b4897b4fe55722be3;snes9x=1.63;stella=7.0;test_core=1")
    }

    @Test
    fun `deriveAllowedCores excludes coreIds that are not in the manifest`() {
        assertThat(deriveAllowedCores(listOf("not_a_real_core"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores excludes non-approved cores`() {
        // sameboy is in the manifest but not approved.
        assertThat(deriveAllowedCores(listOf("sameboy"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores excludes cores whose supportedAbis lack linux-x86_64`() {
        // mupen64plus_next is approved but ARM-only.
        assertThat(deriveAllowedCores(listOf("mupen64plus_next"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores emits an empty string when nothing is installed`() {
        assertThat(deriveAllowedCores(emptyList())).isEmpty()
    }

    // ---------------------------------------------------------------- scanInstalledCoreIds

    @Test
    fun `scanInstalledCoreIds extracts sorted core ids from core shared libraries`(@TempDir dir: Path) {
        Files.write(dir.resolve("libgambatte_core.so"), byteArrayOf(0))
        Files.write(dir.resolve("libfoo.so"), byteArrayOf(0))
        Files.write(dir.resolve("notes.txt"), byteArrayOf(0))

        assertThat(scanInstalledCoreIds(dir)).containsExactly("foo", "gambatte")
    }

    @Test
    fun `scanInstalledCoreIds returns an empty list when the directory does not exist`() {
        assertThat(scanInstalledCoreIds(Path.of("/nonexistent", "rommulus", "cores"))).isEmpty()
    }

    @Test
    fun `deriveAllowedCores recovers test_core from the lossy CMake suffix strip`(@TempDir dir: Path) {
        // The scan strips the CMake `_core` suffix (libtest_core.so → "test"); the derivation
        // must recover the manifest's test_core entry so the fallback stays allowlisted.
        Files.write(dir.resolve("libtest_core.so"), byteArrayOf(0))

        assertThat(deriveAllowedCores(scanInstalledCoreIds(dir))).isEqualTo("test_core=1")
    }

    // ---------------------------------------------------------------- launch integration

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
}

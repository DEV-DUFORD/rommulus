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
        assertThat(deriveAllowedCores(listOf("fceumm", "gambatte", "test_core")))
            .isEqualTo("fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;test_core=1")
        // Input order must not matter: the output is sorted by coreId.
        assertThat(deriveAllowedCores(listOf("test_core", "fceumm", "gambatte")))
            .isEqualTo("fceumm=b5e3566515c27dc66c9c20572171673126532e06;gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5;test_core=1")
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
        // stella is approved but ARM-only.
        assertThat(deriveAllowedCores(listOf("stella"))).isEmpty()
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

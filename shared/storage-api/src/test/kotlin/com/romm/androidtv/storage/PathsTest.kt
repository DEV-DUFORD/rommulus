package com.romm.androidtv.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class PathsTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `symlink rejection returns failure`() {
        val real = tempDir.resolve("real")
        Files.createDirectories(real)
        val link = tempDir.resolve("link")
        Files.createSymbolicLink(link, real)

        val result = rejectIfSymlink(link)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `non-symlink path is accepted`() {
        val dir = tempDir.resolve("accepted")
        Files.createDirectories(dir)

        val result = rejectIfSymlink(dir)
        assertThat(result.isSuccess).isTrue()
    }

    @Test
    fun `testAppPaths creates expected subdirectories`() {
        val paths = TestAppPaths(tempDir)
        assertTail(paths.configDir, "config")
        assertTail(paths.dataDir, "data")
        assertTail(paths.stateDir, "state")
        assertTail(paths.cacheDir, "cache")
    }

    @Test
    fun `derived paths resolve correctly`() {
        val paths = TestAppPaths(tempDir)
        // Compare name components, never slash-formatted Path.toString(): on Windows the
        // separator is '\', so string suffixes like "config/settings.json" can never match.
        assertTail(paths.settingsFile(), "config", "settings.json")
        assertTail(paths.databaseDir(), "data", "database")
        assertTail(paths.savesDir(), "data", "saves")
        assertTail(paths.journalsDir(), "state", "journals")
        assertTail(paths.logsDir(), "state", "logs")
        assertTail(paths.romCacheDir(), "cache", "roms")
        assertTail(paths.artworkCacheDir(), "cache", "artwork")
    }

    /** Platform-neutral tail check: the last [expected] name components of [path], in order. */
    private fun assertTail(path: Path, vararg expected: String) {
        val names = (0 until path.nameCount).map { path.getName(it).toString() }
        assertThat(names.takeLast(expected.size)).containsExactly(*expected)
    }

    @Test
    fun `isApprovedPath accepts paths under approved roots`() {
        val paths = TestAppPaths(tempDir)
        Files.createDirectories(paths.dataDir)
        val child = paths.dataDir.resolve("subfile.txt")
        assertThat(paths.isApprovedPath(child)).isTrue()
    }

    @Test
    fun `isApprovedPath rejects paths outside approved roots`() {
        val paths = TestAppPaths(tempDir)
        val outside = Path.of("/tmp/outside")
        assertThat(paths.isApprovedPath(outside)).isFalse()
    }

    @Test
    fun `hoisting rules constants are present`() {
        assertThat(StoreHoistingRules.AUTHORITATIVE_NEVER_CACHE).isNotBlank()
        assertThat(StoreHoistingRules.CACHE_IS_REBUILDABLE).isNotBlank()
        assertThat(StoreHoistingRules.ATOMIC_WRITE_REQUIRED).isNotBlank()
        assertThat(StoreHoistingRules.SYMLINKS_REJECTED).isNotBlank()
    }
}

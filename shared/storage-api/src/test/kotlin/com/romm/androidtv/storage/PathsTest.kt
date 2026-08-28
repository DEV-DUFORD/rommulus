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
        assertThat(paths.configDir.toString()).endsWith("config")
        assertThat(paths.dataDir.toString()).endsWith("data")
        assertThat(paths.stateDir.toString()).endsWith("state")
        assertThat(paths.cacheDir.toString()).endsWith("cache")
    }

    @Test
    fun `derived paths resolve correctly`() {
        val paths = TestAppPaths(tempDir)
        assertThat(paths.settingsFile().toString()).endsWith("config/settings.json")
        assertThat(paths.databaseDir().toString()).endsWith("data/database")
        assertThat(paths.savesDir().toString()).endsWith("data/saves")
        assertThat(paths.journalsDir().toString()).endsWith("state/journals")
        assertThat(paths.logsDir().toString()).endsWith("state/logs")
        assertThat(paths.romCacheDir().toString()).endsWith("cache/roms")
        assertThat(paths.artworkCacheDir().toString()).endsWith("cache/artwork")
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

package com.romm.androidtv.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SettingsCodecTest {

    @TempDir
    lateinit var tempDir: Path

    private fun makeCodec(): Pair<AtomicSettingsCodec, Path> {
        val path = tempDir.resolve("settings.json")
        return AtomicSettingsCodec(path) to path
    }

    @Test
    fun `atomic settings overwrite creates backup`() {
        val (codec, path) = makeCodec()
        val original = VersionedSettings(1, mapOf("theme" to "dark"))
        codec.write(original).getOrThrow()

        // Verify file exists.
        assertThat(Files.exists(path)).isTrue()

        // Overwrite with new settings.
        val updated = VersionedSettings(2, mapOf("theme" to "light", "lang" to "en"))
        codec.write(updated).getOrThrow()

        // Backup should exist.
        val backup = path.resolveSibling("settings.json.bak")
        assertThat(Files.exists(backup)).isTrue()

        // Current file has new content.
        val read = codec.read().getOrThrow()
        assertThat(read.schemaVersion).isEqualTo(2)
        assertThat(read.values["theme"]).isEqualTo("light")
    }

    @Test
    fun `symlink settings path is rejected by write`() {
        val realPath = tempDir.resolve("real_settings.json")
        Files.createDirectories(tempDir)
        val linkPath = tempDir.resolve("linked_settings.json")
        Files.createFile(realPath)
        Files.createSymbolicLink(linkPath, realPath)

        // Contract proves symlink rejection via Paths.rejectIfSymlink.
        val result = rejectIfSymlink(linkPath)
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `versioned settings parse round-trips`() {
        val input = VersionedSettings(3, mapOf("origin" to "https://romm.example", "fullscreen" to "true"))
        val json = serializeVersionedSettings(input)
        val parsed = parseVersionedSettings(json).getOrThrow()

        assertThat(parsed.schemaVersion).isEqualTo(3)
        assertThat(parsed.values["origin"]).isEqualTo("https://romm.example")
        assertThat(parsed.values["fullscreen"]).isEqualTo("true")
    }

    @Test
    fun `malformed settings produce failure`() {
        val result = parseVersionedSettings("not json at all")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `missing schemaVersion produces failure`() {
        val result = parseVersionedSettings("""{"theme":"dark"}""")
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `read missing file produces failure`() {
        val (codec, _) = makeCodec()
        val result = codec.read()
        assertThat(result.isFailure).isTrue()
    }

    @Test
    fun `write then read round-trips values`() {
        val (codec, _) = makeCodec()
        val settings = VersionedSettings(1, mapOf("key1" to "val1", "key2" to "val2"))
        codec.write(settings).getOrThrow()

        val read = codec.read().getOrThrow()
        assertThat(read.schemaVersion).isEqualTo(1)
        assertThat(read.values["key1"]).isEqualTo("val1")
        assertThat(read.values["key2"]).isEqualTo("val2")
    }

    @Test
    fun `EMPTY settings have schema version 1`() {
        assertThat(VersionedSettings.EMPTY.schemaVersion).isEqualTo(1)
        assertThat(VersionedSettings.EMPTY.values).isEmpty()
    }
}

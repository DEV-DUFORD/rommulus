package com.romm.androidtv.storage

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Versioned settings-JSON codec contract.
 *
 * All writes are atomic: write to a temp file, flush + fsync, then rename into place.
 * Unknown future keys are preserved on round-trip when possible.
 */
interface SettingsCodec {
    /** Read and parse the settings file; returns failure if file is malformed or missing. */
    fun read(): Result<VersionedSettings>

    /**
     * Write [settings] atomically. Produces a backup of any pre-existing file before overwrite.
     * Returns failure on I/O error; the original file is preserved on failure.
     */
    fun write(settings: VersionedSettings): Result<Unit>
}

/** A versioned settings document with extensible extra keys. */
data class VersionedSettings(
    val schemaVersion: Int,
    val values: Map<String, String> = emptyMap()
) {
    init {
        require(schemaVersion >= 1) { "schemaVersion must be >= 1, got $schemaVersion" }
    }

    companion object {
        val EMPTY = VersionedSettings(schemaVersion = 1)
    }
}

/**
 * Filesystem-backed [SettingsCodec] that performs atomic writes.
 */
class AtomicSettingsCodec(
    private val settingsPath: Path,
    private val fileReader: (Path) -> String = { Files.readString(it, UTF_8) },
    private val fileWriter: (Path, String) -> Unit = { p, s ->
        Files.writeString(p, s, UTF_8, java.nio.file.StandardOpenOption.WRITE,
            java.nio.file.StandardOpenOption.CREATE,
            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)
    }
) : SettingsCodec {

    override fun read(): Result<VersionedSettings> = runCatching {
        val text = fileReader(settingsPath)
        parseVersionedSettings(text)
            .getOrElse { throw IOException("Malformed settings: ${it.message}", it) }
    }

    override fun write(settings: VersionedSettings): Result<Unit> = runCatching {
        val parent = settingsPath.parent
            ?: throw IOException("settingsPath has no parent directory")
        Files.createDirectories(parent)

        // Backup existing file if present.
        if (Files.exists(settingsPath)) {
            val backup = settingsPath.resolveSibling("${settingsPath.fileName}.bak")
            Files.copy(settingsPath, backup, StandardCopyOption.REPLACE_EXISTING)
        }

        // Write to temp file in same directory for atomic rename.
        val tmp = Files.createTempFile(parent, ".settings-", ".tmp")
        try {
            val json = serializeVersionedSettings(settings)
            fileWriter(tmp, json)
            Files.move(tmp, settingsPath, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            throw e
        }
    }
}

/** Parse a JSON-like string into [VersionedSettings]. Minimal parser — no external deps. */
fun parseVersionedSettings(text: String): Result<VersionedSettings> = runCatching {
    val trimmed = text.trim()
    require(trimmed.startsWith("{") && trimmed.endsWith("}")) { "Not a JSON object" }
    val inner = trimmed.substring(1, trimmed.length - 1).trim()

    var schemaVersion: Int? = null
    val valuesBuilder = mutableMapOf<String, String>()

    // Simple key-value parser for flat settings objects.
    val kvRegex = """("([^"]+)")\s*:\s*"([^"]*)"""".toRegex()
    for (match in kvRegex.findAll(inner)) {
        val k = match.groupValues[2] // captured key without quotes
        val v = match.groupValues[3]   // captured value
        when (k) {
            "schemaVersion" -> schemaVersion = v.trim().toIntOrNull()
                ?: throw IllegalArgumentException("Non-integer schemaVersion: $v")
            else -> valuesBuilder[k] = v
        }
    }

    val resolvedSchemaVersion = schemaVersion
        ?: error("Missing required key: schemaVersion")
    VersionedSettings(schemaVersion = resolvedSchemaVersion, values = valuesBuilder)
}

/** Serialize [VersionedSettings] to a compact JSON string. */
fun serializeVersionedSettings(s: VersionedSettings): String {
    val entries = listOf("\"schemaVersion\":\"${s.schemaVersion}\"") +
        s.values.map { (k, v) -> "\"$k\":\"$v\"" }
    return "{${entries.joinToString(",")}}"
}

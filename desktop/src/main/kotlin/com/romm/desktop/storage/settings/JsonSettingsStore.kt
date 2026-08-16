package com.romm.desktop.storage.settings

import com.romm.androidtv.storage.AppPaths
import com.romm.androidtv.storage.AtomicSettingsCodec
import com.romm.androidtv.storage.SettingsCodec
import com.romm.androidtv.storage.VersionedSettings
import com.romm.androidtv.storage.ports.SettingsSnapshot
import com.romm.androidtv.storage.ports.SettingsStore
import com.romm.androidtv.storage.settingsFile
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Carried inside [Result.failure] from [JsonSettingsStore.write] /
 * [JsonSettingsStore.clear] when a malformed settings file was quarantined.
 *
 * This is the actionable reset channel for the caller: the corrupted file is
 * preserved at [backupPath] (never deleted or overwritten), the store now
 * starts fresh, and the caller can surface a "settings were corrupted and
 * reset" state and re-apply values. A subsequent [JsonSettingsStore.write]
 * succeeds against the fresh store.
 */
class SettingsRecoveryException(
    val originalPath: Path,
    val backupPath: Path,
    cause: Throwable? = null,
) : IOException(
    "Settings file $originalPath was malformed and has been quarantined to $backupPath. " +
        "The store now starts fresh; re-apply settings to reset.",
    cause,
)

/**
 * Desktop [SettingsStore] backed by ONE versioned JSON file, written atomically.
 *
 * ## File
 * The file is the [Path] passed to the constructor — in production this is
 * [AppPaths.settingsFile] (`$XDG_CONFIG_HOME/rommulus/settings.json`, see
 * [forPaths]). Only non-secret settings are stored here (server origin, theme,
 * toggles, geometry, firmware IDs, etc.); secrets belong in the platform
 * secret store, never in this file.
 *
 * ## Atomicity
 * All persistence goes through the shared [AtomicSettingsCodec]: write to a
 * temp file in the same directory, fsync, then atomic rename into place. A
 * `.bak` copy of the previous file is produced before every overwrite, and on
 * any write failure the previous file is left intact — the operation returns
 * [Result.failure] instead of corrupting or replacing state. The codec is
 * constructed with an fsync-ing file writer (see [fsyncFileWriter]) so the
 * temp file is durable before the rename.
 *
 * ## Unknown future keys
 * The shared parser retains every string-valued key it does not own
 * (everything except `schemaVersion`), so keys introduced by newer app
 * versions are ignored by this build but preserved across read/write round
 * trips (per plans/LINUX_X64.md §10.1).
 *
 * ## Malformed file recovery
 * If the file exists but cannot be parsed, it is never silently replaced: it
 * is renamed (atomic move) to a timestamped quarantine backup next to it,
 * e.g. `settings.json.bak-1712345678901`, and the store starts fresh from an
 * empty settings set. A warning is logged via `java.util.logging` (no
 * `println`, no extra dependency). The recovery is surfaced through the
 * port's [Result] channel wherever one exists:
 * - [snapshot] (which cannot fail) returns an empty [SettingsSnapshot] after
 *   quarantine; the quarantine itself is logged.
 * - [write] / [clear] return [Result.failure] carrying a
 *   [SettingsRecoveryException] whose [SettingsRecoveryException.backupPath]
 *   points at the quarantined file, so the caller can act on it (show a reset
 *   notice, re-apply defaults) and retry.
 *
 * A path that exists but is not a regular file (e.g. a directory) is treated
 * as an unusable environment error: operations fail with an [IOException] and
 * nothing is renamed or deleted.
 *
 * ## Deliberately not Preferences
 * This implementation does not use `java.util.prefs.Preferences`: the
 * contract is one inspectable, user-owned JSON file under the XDG config root.
 */
class JsonSettingsStore(
    private val settingsPath: Path,
    private val codec: SettingsCodec = AtomicSettingsCodec(settingsPath, fileWriter = fsyncFileWriter),
) : SettingsStore {

    private val lock = Any()
    private val logger = Logger.getLogger(JsonSettingsStore::class.java.name)

    override fun snapshot(): SettingsSnapshot = synchronized(lock) {
        when (val state = loadCurrent()) {
            is LoadState.Values -> SettingsSnapshot(state.values)
            is LoadState.Recovered -> SettingsSnapshot(emptyMap())
            is LoadState.Unusable -> SettingsSnapshot(emptyMap())
        }
    }

    override fun write(updates: Map<String, String>): Result<SettingsSnapshot> = synchronized(lock) {
        when (val state = loadCurrent()) {
            is LoadState.Recovered -> Result.failure(SettingsRecoveryException(settingsPath, state.backupPath))
            is LoadState.Unusable -> Result.failure(state.error)
            is LoadState.Values -> persist(state.values + updates)
        }
    }

    override fun clear(vararg keys: String): Result<SettingsSnapshot> = synchronized(lock) {
        when (val state = loadCurrent()) {
            is LoadState.Recovered -> Result.failure(SettingsRecoveryException(settingsPath, state.backupPath))
            is LoadState.Unusable -> Result.failure(state.error)
            is LoadState.Values -> {
                val remaining = state.values.toMutableMap()
                keys.forEach { remaining.remove(it) }
                persist(remaining)
            }
        }
    }

    /** Persist [values] atomically via the codec; on failure the previous file is intact. */
    private fun persist(values: Map<String, String>): Result<SettingsSnapshot> =
        codec.write(VersionedSettings(SCHEMA_VERSION, values)).fold(
            onSuccess = { Result.success(SettingsSnapshot(values)) },
            onFailure = { Result.failure(it) },
        )

    /**
     * Load the current settings set from disk.
     *
     * - Absent file → empty values (fresh store).
     * - Present, parseable → its values (unknown future keys included).
     * - Present, malformed → quarantined to a timestamped backup, [LoadState.Recovered].
     * - Present, not a regular file → [LoadState.Unusable] (nothing is renamed).
     */
    private fun loadCurrent(): LoadState {
        if (!Files.exists(settingsPath)) return LoadState.Values(emptyMap())
        if (!Files.isRegularFile(settingsPath)) {
            return LoadState.Unusable(IOException("Settings path is not a regular file: $settingsPath"))
        }
        val read = codec.read()
        val parsed = read.getOrNull()
        if (parsed != null) return LoadState.Values(parsed.values)
        val parseError = read.exceptionOrNull() ?: IOException("Settings file could not be read: $settingsPath")
        val quarantine = quarantineMalformed()
        val backup = quarantine.getOrNull()
        if (backup != null) {
            logger.log(
                Level.WARNING,
                "Settings file $settingsPath was malformed (${parseError.message}); " +
                    "quarantined to $backup; starting with empty settings.",
            )
            return LoadState.Recovered(backup)
        }
        return LoadState.Unusable(quarantine.exceptionOrNull() ?: parseError)
    }

    /**
     * Rename the malformed [settingsPath] to a timestamped quarantine backup in
     * the same directory (atomic move). The malformed file is never deleted or
     * overwritten; a numeric suffix is appended if the backup name already
     * exists.
     */
    private fun quarantineMalformed(): Result<Path> {
        val name = settingsPath.fileName.toString()
        val timestamp = System.currentTimeMillis()
        var candidate = settingsPath.resolveSibling("$name.bak-$timestamp")
        var suffix = 1
        while (Files.exists(candidate)) {
            candidate = settingsPath.resolveSibling("$name.bak-$timestamp-$suffix")
            suffix++
        }
        return runCatching { Files.move(settingsPath, candidate) }
    }

    /** Result of loading the settings file from disk (see [loadCurrent]). */
    private sealed interface LoadState {
        /** The file was read (or absent); [values] is the current settings set. */
        data class Values(val values: Map<String, String>) : LoadState

        /** A malformed file was quarantined to [backupPath]; the store is fresh. */
        data class Recovered(val backupPath: Path) : LoadState

        /** The path is unusable (e.g. a directory); no recovery was attempted. */
        data class Unusable(val error: Throwable) : LoadState
    }

    companion object {
        private const val SCHEMA_VERSION = 1

        /**
         * File writer that fsyncs the temp file before the codec renames it
         * into place, making the atomic write durable. Injected into
         * [AtomicSettingsCodec] so the shared codec's default (no explicit
         * fsync) is strengthened without modifying shared code.
         */
        private val fsyncFileWriter: (Path, String) -> Unit = { path, text ->
            FileChannel.open(path, WRITE, CREATE, TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(text.toByteArray(UTF_8))
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        }

        /** Create a store at the canonical settings location for [paths] (`configDir/settings.json`). */
        fun forPaths(paths: AppPaths): JsonSettingsStore = JsonSettingsStore(paths.settingsFile())
    }
}

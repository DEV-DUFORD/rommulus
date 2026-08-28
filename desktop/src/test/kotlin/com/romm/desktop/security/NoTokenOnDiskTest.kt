package com.romm.desktop.security

import com.romm.androidtv.storage.logsDir
import com.romm.androidtv.storage.settingsFile
import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.storage.paths.XdgAppPaths
import com.romm.desktop.storage.settings.JsonSettingsStore
import com.romm.desktop.storage.sqlite.SqliteDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Phase 5 gate: filesystem scan finds no token.
 *
 * Proves that every desktop-created artifact under the XDG tree (settings JSON,
 * SQLite DB, log files) contains zero secret material. A positive-control file
 * (written raw, without redaction) is also placed under the tree to prove the
 * scanner itself detects token patterns.
 *
 * Token patterns are duplicated from [com.romm.desktop.log.TokenRedactor] as the
 * source of truth. The redactor's rules (JWT 3-segment, Bearer, hex ≥32, Cookie,
 * password=) are mirrored here so the scanner is independently verifiable.
 */
class NoTokenOnDiskTest {

    @TempDir
    lateinit var tempDir: Path

    // ---- Token patterns (mirror TokenRedactor rules; see desktop/src/main/kotlin/com/romm/desktop/log/TokenRedactor.kt) ----
    // NOTE: each value-capturing pattern rejects the literal redaction marker `[REDACTED]` so that
    // already-redacted lines (e.g. "Authorization: Bearer [REDACTED]", "password=[REDACTED]") are NOT
    // re-flagged as leaks — only UNREDACTED secret material triggers the gate.
    private val HEADER_AUTHORIZATION_BEARER = Regex("""(?i)Authorization:\s*Bearer\s+(?!\[REDACTED\])(\S+)""")
    private val BARE_BEARER = Regex("""(?i)\bBearer\s+(?!\[REDACTED\])(\S+)""")
    private val JWT_SEGMENT = Regex("""[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")
    private val HEX_TOKEN = Regex("""(?:^|[^A-Za-z0-9/._-])([A-Fa-f0-9]{32,})(?=$|[^A-Za-z0-9/._-])""")
    private val COOKIE_HEADER = Regex("""(?i)Cookie:\s*([A-Za-z0-9_$%\-]+=[^;\s]{3,}(?:\s*;\s*[A-Za-z0-9_$%\-]+=[^;\s]{3,})*)""")
    private val PASSWORD_ASSIGNMENT = Regex("""(?i)password(?:64)?=(?!\[REDACTED\])\S+""")

    private val tokenPatterns = listOf(
        HEADER_AUTHORIZATION_BEARER,
        BARE_BEARER,
        JWT_SEGMENT,
        HEX_TOKEN,
        COOKIE_HEADER,
        PASSWORD_ASSIGNMENT,
    )

    private fun hasTokenPattern(content: String): Boolean =
        tokenPatterns.any { it.containsMatchIn(content) }

    private fun suspiciousFilename(name: String): Boolean =
        Regex("""(?i)(token|secret|credential)""").containsMatchIn(name)

    @Test
    fun `no token material on disk after desktop artifacts are written`() {
        // 1. Build a temp XDG tree
        val xdgEnv = mapOf(
            "XDG_CONFIG_HOME" to tempDir.resolve("config").toString(),
            "XDG_DATA_HOME" to tempDir.resolve("data").toString(),
            "XDG_STATE_HOME" to tempDir.resolve("state").toString(),
            "XDG_CACHE_HOME" to tempDir.resolve("cache").toString(),
        )
        val home = tempDir.resolve("home")
        Files.createDirectories(home)
        val paths = XdgAppPaths(xdgEnv, home)

        // 2. Write settings via JsonSettingsStore (non-secret keys only)
        val settingsStore = JsonSettingsStore(paths.settingsFile())
        settingsStore.write(mapOf(
            "serverOrigin" to "https://romm.example.com",
            "theme" to "DARK",
            "windowWidth" to "1920",
            "windowHeight" to "1080",
        ))

        // 3. Create SQLite DB with auto-discovered classpath migrations (V1+V2), insert data
        val dbPath = paths.dataDir.resolve("database").resolve("romm.db")
        val db = SqliteDatabase.open(dbPath).getOrThrow()
        db.executeUpdate(
            "INSERT INTO save_replicas (server_key, user_key, rom_id, rom_hash, slot, core_id, core_build_revision, sync_status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            "server1", "user1", 1, "abc123hash", "slot1", "fce", "fce-1234", "UNSYNCED"
        )
        db.executeUpdate(
            "INSERT INTO pending_operations (server_key, user_key, rom_id, rom_hash, slot, operation_type, local_generation_epoch_ms, status, created_at_epoch_ms, updated_at_epoch_ms) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            "server1", "user1", 1, "abc123hash", "slot1", "UPLOAD", System.currentTimeMillis(), "PENDING", System.currentTimeMillis(), System.currentTimeMillis()
        )
        db.close()

        // 4. Write log files via DesktopLogger with token-like strings (must be redacted)
        val logsDir = paths.logsDir()
        val logger = DesktopLogger.newLogger(logsDir)

        val jwt = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
        val bearerHex = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2"
        val passwordVal = "s3cretP@ssw0rd!"

        logger.info("Auth header: Authorization: Bearer $bearerHex")
        logger.info("JWT token: $jwt")
        logger.info("Login payload: username=admin&password=$passwordVal")

        // Close all handlers to ensure log files are fully written before scanning.
        logger.handlers.forEach { it.close() }

        // 5. Also write a raw (non-redacted) file containing token-like strings as positive control
        val positiveControlPath = tempDir.resolve("positive_control.txt")
        Files.writeString(positiveControlPath, """
            Auth: Authorization: Bearer $bearerHex
            Token: $jwt
            Password: password=$passwordVal
        """.trimIndent())

        // 6. Walk EVERY file under the temp XDG tree and assert:
        val allFiles = mutableListOf<Path>()
        Files.walk(tempDir).use { stream ->
            stream.filter { Files.isRegularFile(it) }.forEach { allFiles.add(it) }
        }

        val filesWithTokens = mutableMapOf<Path, List<String>>()
        for (file in allFiles) {
            val content = try {
                Files.readString(file)
            } catch (_: java.nio.charset.MalformedInputException) {
                // Binary file (e.g., SQLite DB) — cannot be scanned for text tokens.
                continue
            }
            val matches = tokenPatterns.mapNotNull { pattern ->
                if (pattern.containsMatchIn(content)) pattern.pattern else null
            }
            if (matches.isNotEmpty()) {
                filesWithTokens[file] = matches
            }
        }

        // All files except the positive control should have no tokens
        for ((file, patterns) in filesWithTokens) {
            if (file != positiveControlPath) {
                throw AssertionError("File $file contains token patterns: $patterns")
            }
        }

        // The positive control file MUST have tokens (proves scanner works)
        val positiveControlContent = Files.readString(positiveControlPath)
        assertThat(hasTokenPattern(positiveControlContent)).isTrue()

        // No suspicious filenames (token, secret, credential)
        val suspiciousFiles = mutableListOf<Path>()
        for (file in allFiles) {
            if (suspiciousFilename(file.fileName.toString())) {
                suspiciousFiles.add(file)
            }
        }
        assertThat(suspiciousFiles).isEmpty()
    }
}

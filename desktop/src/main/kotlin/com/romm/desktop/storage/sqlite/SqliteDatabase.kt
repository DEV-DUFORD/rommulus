package com.romm.desktop.storage.sqlite

import com.romm.desktop.platform.security.FileSecurityPolicies
import com.romm.desktop.platform.security.FileSecurityPolicy
import com.romm.desktop.platform.security.FileSensitivity
import com.romm.desktop.platform.security.PathPermissionProfile
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.net.JarURLConnection
import java.sql.Types
import java.util.zip.ZipFile

/** One numbered, forward-only migration script (file format `V<version>__name.sql`). */
data class Migration(val version: Int, val sql: String)

/**
 * A desktop-schema migration failed. The pre-migration backup has been restored so the prior
 * database file is left untouched; callers must refuse writable startup on this failure
 * (plans/LINUX_X64.md §10.2 rule 4).
 */
class MigrationFailedException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Owns the single JDBC [Connection] to the desktop SQLite database at [path].
 *
 * - Opens the DB file at an injectable [Path] (callers pass the data dir; no AppPaths dependency).
 * - Enables `PRAGMA foreign_keys` and books schema progress in `PRAGMA user_version`.
 * - Applies numbered, forward-only migrations, each in its own transaction, after an atomic
 *   pre-migration backup to a `.bak` sibling. On any migration failure the prior database is
 *   restored from that backup and [MigrationFailedException] is surfaced so writable startup
 *   can be refused (plans/LINUX_X64.md §10.2 rules 2-4).
 * - The DB file and its parent directory get user-only permissions where POSIX is supported
 *   (plans/LINUX_X64.md §9 rule 4). No plaintext tokens are ever stored here.
 *
 * A [SqliteDatabase] is owned by exactly one store; all helpers are synchronized on a single
 * lock because a JDBC [Connection] is not thread-safe.
 */
class SqliteDatabase private constructor(
    val path: Path,
    val connection: Connection,
    private val securityPolicy: FileSecurityPolicy,
) : AutoCloseable {

    @Volatile
    private var _schemaVersion = 0

    /** Schema version (`PRAGMA user_version`) after successful migration. */
    val schemaVersion: Int get() = _schemaVersion

    private val lock = Any()

    // ---- query helpers (synchronized: a JDBC Connection is not thread-safe) ----

    fun executeUpdate(sql: String, vararg args: Any?): Int = synchronized(lock) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }
    }

    fun <T> query(sql: String, mapper: (ResultSet) -> T, vararg args: Any?): List<T> = synchronized(lock) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(mapper(rs)) }
            }
        }
    }

    fun <T> queryOne(sql: String, mapper: (ResultSet) -> T, vararg args: Any?): T? =
        query(sql, mapper, *args).firstOrNull()

    /** Executes an `INSERT ... RETURNING id` and returns the new row id. */
    fun insertReturningId(sql: String, vararg args: Any?): Long = synchronized(lock) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                require(rs.next()) { "INSERT ... RETURNING id produced no row" }
                rs.getLong(1)
            }
        }
    }

    fun scalarLong(sql: String, vararg args: Any?): Long? = synchronized(lock) {
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                if (rs.next()) {
                    val value = rs.getLong(1)
                    if (rs.wasNull()) null else value
                } else {
                    null
                }
            }
        }
    }

    /** Runs [block] in a JDBC transaction; commits on success, rolls back and rethrows on failure. */
    fun <T> inSqlTransaction(block: () -> T): T = synchronized(lock) {
        val wasAutoCommit = connection.autoCommit
        if (wasAutoCommit) connection.autoCommit = false
        try {
            val result = block()
            if (wasAutoCommit) connection.commit()
            result
        } catch (e: Exception) {
            if (wasAutoCommit) runCatching { connection.rollback() }
            throw e
        } finally {
            if (wasAutoCommit) connection.autoCommit = true
        }
    }

    override fun close() {
        synchronized(lock) { runCatching { connection.close() } }
    }

    private fun bind(ps: PreparedStatement, args: Array<out Any?>) {
        for ((index, arg) in args.withIndex()) {
            when (arg) {
                null -> ps.setNull(index + 1, Types.NULL)
                is Long -> ps.setLong(index + 1, arg)
                is Int -> ps.setInt(index + 1, arg)
                else -> ps.setString(index + 1, arg.toString())
            }
        }
    }

    companion object {
        private const val MIGRATIONS_RESOURCE_DIR = "db/migrations"
        private val MIGRATION_FILE_NAME = Regex("^V(\\d+)__[^/]+\\.sql$")

        /** Opens [path], applies any pending classpath migrations, and returns the ready database. */
        fun open(path: Path): Result<SqliteDatabase> = open(path, discoverClasspathMigrations())

        /**
         * Opens [path] with an explicit migration list (test seam; production uses the classpath).
         * On failure the prior DB file is restored from the pre-migration backup and the
         * connection is closed; callers must treat a failed result as "refuse writable startup".
         *
         * The database and its parent directory are hardened user-only (0700/0600 on Linux)
         * through [securityPolicy] — the database holds sensitive local data, so a filesystem
         * that cannot establish that security fails explicitly (plans/WINDOWS_IMPL.md §4.2).
         */
        fun open(
            path: Path,
            migrations: List<Migration>,
            securityPolicy: FileSecurityPolicy = FileSecurityPolicies.default(),
        ): Result<SqliteDatabase> = runCatching {
            val dbPath = path.toAbsolutePath().normalize()
            dbPath.parent?.let { parent ->
                // Create + always re-apply (historical behavior): user-only directory.
                securityPolicy.ensureDirectory(parent, PathPermissionProfile.USER_ONLY_DIRECTORY, FileSensitivity.SENSITIVE)
            }
            val connection = DriverManager.getConnection("jdbc:sqlite:$dbPath")
            try {
                // PRAGMA foreign_keys is a no-op inside a transaction, so enable it before any migration.
                connection.createStatement().use { st ->
                    st.execute("PRAGMA foreign_keys = ON")
                    st.execute("PRAGMA busy_timeout = 5000")
                }
                if (Files.exists(dbPath)) {
                    securityPolicy.hardenFile(dbPath, PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.SENSITIVE)
                }
                val appliedVersion = SqliteMigrationRunner(dbPath).apply(connection, migrations)
                SqliteDatabase(dbPath, connection, securityPolicy).also { it._schemaVersion = appliedVersion }
            } catch (e: Exception) {
                runCatching { connection.close() }
                throw e
            }
        }

        /** Discovers `V<n>__name.sql` scripts under `db/migrations/` on the classpath, ordered by version. */
        fun discoverClasspathMigrations(): List<Migration> {
            val byVersion = LinkedHashMap<Int, String>()
            for (url in SqliteDatabase::class.java.classLoader.getResources(MIGRATIONS_RESOURCE_DIR)) {
                when (url.protocol) {
                    "file" -> {
                        val dir = Path.of(url.toURI())
                        if (Files.isDirectory(dir)) {
                            Files.newDirectoryStream(dir).use { entries ->
                                for (entry in entries) {
                                    parseMigration(entry.fileName.toString(), Files.readString(entry))?.let { put(byVersion, it) }
                                }
                            }
                        }
                    }

                    "jar" -> {
                        val jarConn = url.openConnection() as? JarURLConnection ?: continue
                        ZipFile(Path.of(jarConn.jarFileURL.toURI()).toFile()).use { zip ->
                            val prefix = (jarConn.entryName ?: MIGRATIONS_RESOURCE_DIR).removeSuffix("/") + "/"
                            for (entry in zip.entries()) {
                                if (!entry.name.startsWith(prefix)) continue
                                val name = entry.name.removePrefix(prefix)
                                if (name.contains("/")) continue
                                parseMigration(name, zip.getInputStream(entry).readBytes().decodeToString())?.let { put(byVersion, it) }
                            }
                        }
                    }
                }
            }
            return byVersion.entries.sortedBy { it.key }.map { Migration(it.key, it.value) }
        }

        private fun parseMigration(fileName: String, sql: String): Migration? {
            val match = MIGRATION_FILE_NAME.find(fileName) ?: return null
            return Migration(match.groupValues[1].toInt(), sql)
        }

        private fun put(byVersion: MutableMap<Int, String>, migration: Migration) {
            val existing = byVersion[migration.version]
            require(existing == null || existing == migration.sql) {
                "conflicting migration scripts for version ${migration.version}"
            }
            byVersion[migration.version] = migration.sql
        }
    }
}

/**
 * Applies numbered, forward-only migrations, each in its own transaction.
 *
 * Before the first pending migration runs, the DB file is atomically copied to a `.bak`
 * sibling (copy + fsync + atomic rename). If any migration fails, the pre-run backup is
 * restored so the prior database is left byte-identical, and [MigrationFailedException] is
 * thrown so writable startup can be refused (plans/LINUX_X64.md §10.2 rules 2-4). A database
 * whose `user_version` exceeds the latest known migration is rejected outright: migrations
 * are forward-only.
 */
internal class SqliteMigrationRunner(
    private val path: Path,
    private val securityPolicy: FileSecurityPolicy = FileSecurityPolicies.default(),
) {

    private val backupFile: Path get() = path.resolveSibling(path.fileName.toString() + ".bak")

    /** Applies pending [migrations]; returns the schema version after the run. */
    fun apply(connection: Connection, migrations: List<Migration>): Int {
        require(migrations.isEmpty() || migrations == migrations.sortedBy { it.version }) {
            "migrations must be sorted by ascending version"
        }
        val current = userVersion(connection)
        val latest = migrations.lastOrNull()?.version ?: 0
        if (current > latest) {
            throw MigrationFailedException(
                "database at schema version $current is newer than the latest supported version $latest; " +
                    "refusing to open (migrations are forward-only)"
            )
        }
        val pending = migrations.filter { it.version > current }
        if (pending.isEmpty()) return current

        backup()
        var applied = current
        for (migration in pending) {
            try {
                connection.runInTransaction {
                    executeSql(connection, migration.sql)
                    connection.createStatement().use { it.execute("PRAGMA user_version = ${migration.version}") }
                }
                applied = migration.version
            } catch (e: Exception) {
                // The transaction above rolled back; restore the pre-run backup so the file is
                // byte-identical to the prior database, then surface the failure.
                runCatching { connection.close() }
                restoreFromBackup()
                throw MigrationFailedException(
                    "migration V${migration.version} failed; prior database restored from $backupFile", e
                )
            }
        }
        return applied
    }

    /** Atomic backup: copy to a temp sibling, fsync, then atomic-rename over `.bak`. */
    private fun backup() {
        val tmp = path.resolveSibling(path.fileName.toString() + ".bak.tmp")
        Files.copy(path, tmp, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(tmp, StandardOpenOption.READ).use { it.force(true) }
        try {
            Files.move(tmp, backupFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp, backupFile, StandardCopyOption.REPLACE_EXISTING)
        }
        securityPolicy.hardenFile(backupFile, PathPermissionProfile.USER_ONLY_FILE, FileSensitivity.SENSITIVE)
    }

    private fun restoreFromBackup() {
        if (!Files.exists(backupFile)) return
        for (suffix in listOf("-journal", "-wal", "-shm")) {
            runCatching { Files.deleteIfExists(path.resolveSibling(path.fileName.toString() + suffix)) }
        }
        Files.copy(backupFile, path, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(path, StandardOpenOption.READ).use { it.force(true) }
    }

    /**
     * Executes a migration script statement by statement. sqlite-jdbc's `Statement.execute`
     * only runs the FIRST statement of a multi-statement string, so the script is split here
     * (quote- and comment-aware); every statement runs inside the caller's transaction.
     */
    private fun executeSql(connection: Connection, sql: String) {
        for (statement in splitSqlStatements(sql)) {
            connection.createStatement().use { it.execute(statement) }
        }
    }

    private fun userVersion(connection: Connection): Int =
        connection.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs -> if (rs.next()) rs.getInt(1) else 0 }
        }
}

/** Runs [block] in a JDBC transaction; commits on success, rolls back and rethrows on failure. */
internal fun Connection.runInTransaction(block: () -> Unit) {
    val wasAutoCommit = autoCommit
    if (wasAutoCommit) autoCommit = false
    try {
        block()
        if (wasAutoCommit) commit()
    } catch (e: Exception) {
        if (wasAutoCommit) runCatching { rollback() }
        throw e
    } finally {
        if (wasAutoCommit) autoCommit = true
    }
}

/**
 * Splits a SQL script on top-level semicolons, respecting single-quoted strings (doubled-quote
 * escapes), double-quoted identifiers, line comments, and block comments.
 */
internal fun splitSqlStatements(sql: String): List<String> {
    val statements = mutableListOf<String>()
    val current = StringBuilder()
    var inSingleQuote = false
    var inDoubleQuote = false
    var inLineComment = false
    var inBlockComment = false
    var i = 0
    while (i < sql.length) {
        val c = sql[i]
        val next = if (i + 1 < sql.length) sql[i + 1] else ' '
        when {
            inLineComment -> {
                current.append(c)
                if (c == '\n') inLineComment = false
            }

            inBlockComment -> {
                current.append(c)
                if (c == '*' && next == '/') {
                    current.append(next)
                    i++
                    inBlockComment = false
                }
            }

            inSingleQuote -> {
                current.append(c)
                if (c == '\'') {
                    if (next == '\'') {
                        current.append(next)
                        i++
                    } else {
                        inSingleQuote = false
                    }
                }
            }

            inDoubleQuote -> {
                current.append(c)
                if (c == '"') inDoubleQuote = false
            }

            c == '-' && next == '-' -> {
                inLineComment = true
                current.append(c)
            }

            c == '/' && next == '*' -> {
                inBlockComment = true
                current.append(c)
                current.append(next)
                i++
            }

            c == '\'' -> {
                inSingleQuote = true
                current.append(c)
            }

            c == '"' -> {
                inDoubleQuote = true
                current.append(c)
            }

            c == ';' -> {
                val statement = current.toString().trim()
                if (statement.isNotEmpty()) statements.add(statement)
                current.clear()
            }

            else -> current.append(c)
        }
        i++
    }
    val tail = current.toString().trim()
    if (tail.isNotEmpty()) statements.add(tail)
    return statements
}

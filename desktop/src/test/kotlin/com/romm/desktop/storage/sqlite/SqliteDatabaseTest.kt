package com.romm.desktop.storage.sqlite

import com.romm.androidtv.storage.ports.SaveReplicaScope
import com.romm.androidtv.storage.records.PendingOperationRecord
import com.romm.androidtv.storage.records.PendingOperationStatus
import com.romm.androidtv.storage.records.PendingOperationType
import com.romm.androidtv.storage.records.SaveReplicaRecord
import com.romm.androidtv.storage.records.SaveSyncStatus
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.sql.SQLException

/**
 * Focused tests for the desktop SQLite layer: migration runner (forward-only, backup,
 * failure recovery), schema constraints (unique identity index, FK enforcement),
 * upsert-replace / scope-dedupe semantics, and user-only file permissions (§9 rule 4).
 */
class SqliteDatabaseTest {

    @TempDir
    lateinit var tempDir: Path

    private fun v1Migration(): Migration {
        val url = javaClass.classLoader.getResource("db/migrations/V1__init.sql")
            ?: error("V1__init.sql not on test classpath")
        return Migration(1, url.readText())
    }

    private fun openDb(name: String = "rommulus.db"): SqliteDatabase =
        SqliteDatabase.open(tempDir.resolve(name)).getOrThrow()

    // ── migrations ─────────────────────────────────────────────────────────────

    @Test
    fun `fresh open applies V1 and books user_version`() {
        val db = openDb()
        assertThat(db.schemaVersion).isEqualTo(3)
        val tables = db.query<String>("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", { rs -> rs.getString(1) })
        assertThat(tables).contains(
            "save_replicas", "pending_operations", "controller_bindings", "scheduler_state",
            "session_records", "device_identity",
        )
        // Foreign keys enabled (PRAGMA foreign_keys must be 1 on the live connection).
        db.connection.createStatement().use { st ->
            st.executeQuery("PRAGMA foreign_keys").use { rs ->
                assertThat(rs.next()).isTrue()
                assertThat(rs.getInt(1)).isEqualTo(1)
            }
        }
    }

    @Test
    fun `reopen is idempotent and takes no new backup when nothing is pending`() {
        val path = tempDir.resolve("rommulus.db")
        SqliteDatabase.open(path).getOrThrow().use { db ->
            SqliteSaveStateStore(db).upsert(replica("s", "u", 1L, "h")).getOrThrow()
        }
        // The first open (V1 pending) took a backup; a second open must not retake one.
        val bakAfterFirstOpen = Files.readAllBytes(path.resolveSibling("rommulus.db.bak"))
        SqliteDatabase.open(path).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(3)
            assertThat(SqliteSaveStateStore(db).findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))).isNotNull()
        }
        assertThat(Files.readAllBytes(path.resolveSibling("rommulus.db.bak"))).isEqualTo(bakAfterFirstOpen)
    }

    @Test
    fun `migration failure preserves the prior database and leaves a backup file`() {
        val path = tempDir.resolve("rommulus.db")
        // Seed real data at schema v1.
        SqliteDatabase.open(path, listOf(v1Migration())).getOrThrow().use { db ->
            SqliteSaveStateStore(db).upsert(replica("s", "u", 1L, "h")).getOrThrow()
        }

        val brokenV2 = Migration(2, "CREATE TABLE definitely_broken (")
        val failed = SqliteDatabase.open(path, listOf(v1Migration(), brokenV2))

        assertThat(failed.isFailure).isTrue()
        assertThat(failed.exceptionOrNull()).isInstanceOf(MigrationFailedException::class.java)
        assertThat(failed.exceptionOrNull()!!.message).contains("migration V2 failed")
        // The pre-migration backup from the failed run exists.
        assertThat(Files.exists(path.resolveSibling("rommulus.db.bak"))).isTrue()

        // Prior DB untouched: reopens cleanly at v1 with the seeded row still present.
        SqliteDatabase.open(path, listOf(v1Migration())).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(1)
            assertThat(SqliteSaveStateStore(db).findByScope(SaveReplicaScope("s", "u", 1L, "h", "a"))).isNotNull()
        }
    }

    @Test
    fun `migrations apply forward-only and in order`() {
        val path = tempDir.resolve("rommulus.db")
        val v2 = Migration(2, "CREATE TABLE v2_marker (id INTEGER PRIMARY KEY);")
        SqliteDatabase.open(path, listOf(v1Migration(), v2)).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(2)
            val tables = db.query<String>("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", { rs -> rs.getString(1) })
            assertThat(tables).contains("save_replicas", "v2_marker")
        }

        // A database newer than the known migrations is refused (no downgrades).
        val downgraded = SqliteDatabase.open(path, listOf(v1Migration()))
        assertThat(downgraded.isFailure).isTrue()
        assertThat(downgraded.exceptionOrNull()).hasMessageContaining("forward-only")
    }

    // ── constraints ────────────────────────────────────────────────────────────

    @Test
    fun `unique scope index rejects a duplicate raw insert`() {
        val db = openDb()
        val insert = "INSERT INTO save_replicas (server_key, user_key, rom_id, rom_hash, slot, core_id, core_build_revision) VALUES ('s','u',1,'h','a','c','r')"
        db.executeUpdate(insert)
        assertThatThrownBy { db.executeUpdate(insert) }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("UNIQUE constraint failed")
    }

    @Test
    fun `pending operation scope index is non-unique mirroring Room`() {
        val db = openDb()
        val insert = "INSERT INTO pending_operations (server_key, user_key, rom_id, rom_hash, slot, operation_type, local_generation_epoch_ms, status, attempt_count, created_at_epoch_ms, updated_at_epoch_ms) VALUES ('s','u',1,'h','a','UPLOAD',100,'PENDING',0,100,100)"
        db.executeUpdate(insert)
        // Allowed: multiple generations of the same scope+type coexist until deleteStaleForScope prunes them.
        db.executeUpdate(insert)
        assertThat(db.scalarLong("SELECT COUNT(*) FROM pending_operations")).isEqualTo(2)
    }

    @Test
    fun `foreign key enforcement is enabled and rejects violations`() {
        val path = tempDir.resolve("rommulus.db")
        // Also proves multi-statement migration scripts apply in order inside one transaction.
        val fkMigration = Migration(
            2,
            """
            CREATE TABLE fk_parent (id INTEGER PRIMARY KEY);
            CREATE TABLE fk_child (id INTEGER PRIMARY KEY, parent_id INTEGER NOT NULL REFERENCES fk_parent(id));
            """.trimIndent(),
        )
        val db = SqliteDatabase.open(path, listOf(v1Migration(), fkMigration)).getOrThrow()
        assertThat(db.scalarLong("SELECT COUNT(*) FROM fk_parent")).isZero()
        assertThatThrownBy { db.executeUpdate("INSERT INTO fk_child (id, parent_id) VALUES (1, 42)") }
            .isInstanceOf(SQLException::class.java)
            .hasMessageContaining("FOREIGN KEY constraint failed")
    }

    // ── upsert-replace and scope dedupe semantics ─────────────────────────────

    @Test
    fun `upsert replaces in place without duplicating the row`() {
        val db = openDb()
        val store = SqliteSaveStateStore(db)
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")

        val firstId = store.upsert(replica("s", "u", 1L, "h", coreId = "c1", status = SaveSyncStatus.UNSYNCED)).getOrThrow()
        val secondId = store.upsert(replica("s", "u", 1L, "h", coreId = "c2", status = SaveSyncStatus.SYNCED)).getOrThrow()

        val found = store.findByScope(scope)!!
        assertThat(found.coreId).isEqualTo("c2")
        assertThat(found.syncStatus).isEqualTo(SaveSyncStatus.SYNCED)
        assertThat(db.scalarLong("SELECT COUNT(*) FROM save_replicas")).isEqualTo(1)
        // SQLite keeps the rowid stable across replaces (the InMemory fake regenerates it;
        // the contract does not depend on either behavior).
        assertThat(secondId).isEqualTo(firstId)
    }

    @Test
    fun `scope dedupe of active operations prunes only older generations`() {
        val db = openDb()
        val store = SqliteSaveStateStore(db)
        val scope = SaveReplicaScope("s", "u", 1L, "h", "a")
        val base = System.currentTimeMillis()

        store.enqueue(op(scope, PendingOperationType.UPLOAD, base - 2000, PendingOperationStatus.PENDING))
        store.enqueue(op(scope, PendingOperationType.UPLOAD, base, PendingOperationStatus.RUNNING))
        store.enqueue(op(scope, PendingOperationType.UPLOAD, base + 500, PendingOperationStatus.PENDING))
        assertThat(store.findActiveByScope(scope, PendingOperationType.UPLOAD)).hasSize(3)

        val deleted = store.deleteStaleForScope(scope, PendingOperationType.UPLOAD, base)
        assertThat(deleted).isEqualTo(1)

        val remaining = store.findActiveByScope(scope, PendingOperationType.UPLOAD)
        assertThat(remaining.map { it.localGenerationEpochMs }).containsExactly(base, base + 500)
    }

    // ── filesystem rules (§9) ──────────────────────────────────────────────────

    @Test
    fun `database file and parent dir get user-only permissions`() {
        assumeTrue(
            Files.getFileStore(tempDir).supportsFileAttributeView("posix"),
            "POSIX permissions not supported on this filesystem",
        )
        val db = openDb("dbdir/rommulus.db")
        assertThat(Files.getPosixFilePermissions(db.path))
            .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertThat(Files.getPosixFilePermissions(db.path.parent!!))
            .containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
            )
    }

    // ── fixtures ───────────────────────────────────────────────────────────────

    private fun replica(
        serverKey: String,
        userKey: String,
        romId: Long,
        romHash: String,
        slot: String = "a",
        coreId: String = "c",
        status: SaveSyncStatus = SaveSyncStatus.UNSYNCED,
    ) = SaveReplicaRecord(
        serverKey = serverKey, userKey = userKey, romId = romId, romHash = romHash, slot = slot,
        coreId = coreId, coreBuildRevision = "r1", syncStatus = status,
    )

    private fun op(scope: SaveReplicaScope, type: PendingOperationType, generation: Long, status: PendingOperationStatus) =
        PendingOperationRecord(
            serverKey = scope.serverKey, userKey = scope.userKey, romId = scope.romId,
            romHash = scope.romHash, slot = scope.slot, operationType = type,
            localGenerationEpochMs = generation, status = status,
            createdAtEpochMs = generation, updatedAtEpochMs = generation,
        )
}

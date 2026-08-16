package com.romm.desktop.storage.sqlite

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * V3 migration tests (plans/PHASE6.md §5 decision 2): a fresh database migrates
 * V1→V2→V3 forward-only with `user_version`=3 and the new session/device-identity tables;
 * a V3 database is refused when opened against an older migration list (no downgrades).
 */
class SqliteV3MigrationTest {

    @TempDir
    lateinit var tempDir: Path

    private fun migration(version: Int, fileName: String): Migration {
        val url = javaClass.classLoader.getResource("db/migrations/$fileName")
            ?: error("$fileName not on test classpath")
        return Migration(version, url.readText())
    }

    private fun v1() = migration(1, "V1__init.sql")
    private fun v2() = migration(2, "V2__scheduler_state.sql")
    private fun v3() = migration(3, "V3__session_and_device_identity.sql")

    @Test
    fun `fresh database migrates V1 to V2 to V3 and books user_version 3`() {
        val path = tempDir.resolve("rommulus.db")
        SqliteDatabase.open(path, listOf(v1(), v2(), v3())).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(3)
            val tables = db.query<String>(
                "SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name",
                { rs -> rs.getString(1) },
            )
            // V1 + V2 tables still present; V3 adds the two new ones.
            assertThat(tables).contains(
                "save_replicas", "pending_operations", "controller_bindings", "scheduler_state",
                "session_records", "device_identity",
            )
        }
    }

    @Test
    fun `classpath discovery finds all three migrations in order`() {
        val discovered = SqliteDatabase.discoverClasspathMigrations()
        assertThat(discovered.map { it.version }).containsExactly(1, 2, 3)
    }

    @Test
    fun `v3 database is refused when opened against an older migration list`() {
        val path = tempDir.resolve("rommulus.db")
        SqliteDatabase.open(path, listOf(v1(), v2(), v3())).getOrThrow().use { db ->
            // Seed a row so the refusal is observable as data preservation, not just version.
            db.executeUpdate(
                "INSERT INTO session_records (origin, username, verified_at_epoch_millis, kiosk_mode) VALUES ('o','u',1,0)",
            )
        }

        val downgraded = SqliteDatabase.open(path, listOf(v1(), v2()))
        assertThat(downgraded.isFailure).isTrue()
        assertThat(downgraded.exceptionOrNull()).hasMessageContaining("forward-only")

        // The V3 database reopens cleanly with the row intact.
        SqliteDatabase.open(path, listOf(v1(), v2(), v3())).getOrThrow().use { db ->
            assertThat(db.schemaVersion).isEqualTo(3)
            assertThat(db.scalarLong("SELECT COUNT(*) FROM session_records")).isEqualTo(1)
        }
    }

    @Test
    fun `v3 tables enforce their constraints`() {
        val path = tempDir.resolve("rommulus.db")
        SqliteDatabase.open(path, listOf(v1(), v2(), v3())).getOrThrow().use { db ->
            // session_records: single-row store (origin PK); kiosk_mode defaults to 0.
            db.executeUpdate(
                "INSERT INTO session_records (origin, username, verified_at_epoch_millis) VALUES ('o','u',1)",
            )
            org.assertj.core.api.Assertions.assertThatThrownBy {
                db.executeUpdate(
                    "INSERT INTO session_records (origin, username, verified_at_epoch_millis, kiosk_mode) VALUES ('o','u2',2,1)",
                )
            }.hasMessageContaining("UNIQUE constraint failed")
            assertThat(db.scalarLong("SELECT kiosk_mode FROM session_records WHERE origin = 'o'")).isZero()

            // device_identity: installation_id is NOT NULL.
            org.assertj.core.api.Assertions.assertThatThrownBy {
                db.executeUpdate(
                    "INSERT INTO device_identity (origin, username) VALUES ('o','u')",
                )
            }.hasMessageContaining("NOT NULL constraint failed")
        }
    }
}

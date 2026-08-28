package com.romm.desktop.storage

import com.romm.androidtv.auth.SessionStorage
import com.romm.desktop.storage.sqlite.SqliteDatabase
import com.romm.desktop.storage.sqlite.SqliteSessionRecordStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Focused tests for [DesktopSessionStorage] over the V3 `session_records` table:
 * save/coherentRecord/clear, including kiosk sessions, null usernames, and canonical
 * origin matching (mirrors Android SessionStore.coherentRecord).
 */
class DesktopSessionStorageTest {

    @TempDir
    lateinit var tempDir: Path

    private fun openStorage(): Pair<SqliteDatabase, SessionStorage> {
        val db = SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()
        return db to DesktopSessionStorage(SqliteSessionRecordStore(db))
    }

    @Test
    fun `save persists and coherent record returns it for the same origin`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://romm.example.com", "player1")).isTrue()

        val record = storage.coherentRecord("https://romm.example.com")
        assertThat(record).isNotNull
        assertThat(record!!.origin).isEqualTo("https://romm.example.com")
        assertThat(record.username).isEqualTo("player1")
        assertThat(record.kioskMode).isFalse()
        assertThat(record.verifiedAtEpochMillis).isPositive()
        db.close()
    }

    @Test
    fun `kiosk session round-trips with kiosk flag set`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://demo.example.com", "kiosk", kioskMode = true)).isTrue()

        val record = storage.coherentRecord("https://demo.example.com")
        assertThat(record).isNotNull
        assertThat(record!!.kioskMode).isTrue()
        assertThat(record.username).isEqualTo("kiosk")
        db.close()
    }

    @Test
    fun `null username is persisted but incoherent for profile lookup`() {
        val (db, storage) = openStorage()
        // save still succeeds (durable write); coherence requires a non-blank username.
        assertThat(storage.save("https://romm.example.com", null)).isTrue()
        assertThat(storage.coherentRecord("https://romm.example.com")).isNull()
        db.close()
    }

    @Test
    fun `coherent record is null when origins disagree`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://a.example.com", "player1")).isTrue()

        assertThat(storage.coherentRecord("https://b.example.com")).isNull()
        // Different port/base path also disagrees.
        assertThat(storage.coherentRecord("https://a.example.com:8443")).isNull()
        db.close()
    }

    @Test
    fun `canonical equivalent origins are coherent`() {
        val (db, storage) = openStorage()
        // Host case, explicit default port, and trailing slash all normalize away.
        assertThat(storage.save("https://RomM.Example.com:443/", "player1")).isTrue()

        val record = storage.coherentRecord("https://romm.example.com")
        assertThat(record).isNotNull
        assertThat(record!!.username).isEqualTo("player1")
        db.close()
    }

    @Test
    fun `coherent record is null for blank or unparseable profile origin`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://romm.example.com", "player1")).isTrue()

        assertThat(storage.coherentRecord(null)).isNull()
        assertThat(storage.coherentRecord("   ")).isNull()
        assertThat(storage.coherentRecord("not a url at all")).isNull()
        db.close()
    }

    @Test
    fun `clear removes the persisted record`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://romm.example.com", "player1")).isTrue()
        assertThat(storage.coherentRecord("https://romm.example.com")).isNotNull

        storage.clear()
        assertThat(storage.coherentRecord("https://romm.example.com")).isNull()
        db.close()
    }

    @Test
    fun `record survives reopening the database`() {
        val (db, storage) = openStorage()
        assertThat(storage.save("https://romm.example.com", "player1", verifiedAtEpochMillis = 1234L)).isTrue()
        db.close()

        val db2 = SqliteDatabase.open(tempDir.resolve("rommulus.db")).getOrThrow()
        try {
            val storage2 = DesktopSessionStorage(SqliteSessionRecordStore(db2))
            val record = storage2.coherentRecord("https://romm.example.com")
            assertThat(record).isNotNull
            assertThat(record!!.verifiedAtEpochMillis).isEqualTo(1234L)
        } finally {
            db2.close()
        }
    }
}

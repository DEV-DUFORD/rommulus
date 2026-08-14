package com.romm.androidtv.storage

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RecordStoreTest {

    @Test
    fun `record identity preserved`() {
        val store = InMemoryRecordStore()
        val record = Record("alpha", 1, mapOf("color" to "red"))
        store.begin().apply { put(record); commit() }

        val read = store.get("alpha")
        assertThat(read).isNotNull()
        assertThat(read!!.key).isEqualTo("alpha")
        assertThat(read.version).isEqualTo(1L)
        assertThat(read.payload["color"]).isEqualTo("red")
    }

    @Test
    fun `duplicate key within transaction preserves original then last-write-wins`() {
        val store = InMemoryRecordStore()
        val v1 = Record("dup", 1, mapOf("val" to "one"))
        val v2 = Record("dup", 2, mapOf("val" to "two"))

        store.begin().apply {
            put(v1)
            put(v2) // last-write-wins: v2 replaces v1
            commit()
        }

        val read = store.get("dup")
        assertThat(read).isNotNull()
        assertThat(read!!.version).isEqualTo(2L)
        assertThat(read.payload["val"]).isEqualTo("two")
    }

    @Test
    fun `duplicate key across commits preserves latest`() {
        val store = InMemoryRecordStore()
        store.begin().apply { put(Record("k", 1, mapOf("v" to "a"))); commit() }
        store.begin().apply { put(Record("k", 2, mapOf("v" to "b"))); commit() }

        val read = store.get("k")
        assertThat(read).isNotNull()
        assertThat(read!!.version).isEqualTo(2L)
        assertThat(read.payload["v"]).isEqualTo("b")
    }

    @Test
    fun `rollback discards uncommitted changes`() {
        val store = InMemoryRecordStore()
        store.begin().apply { put(Record("persisted", 1, mapOf())); commit() }

        store.begin().apply {
            put(Record("ephemeral", 1, mapOf()))
            rollback()
        }

        assertThat(store.get("persisted")).isNotNull()
        assertThat(store.get("ephemeral")).isNull()
    }

    @Test
    fun `delete removes record`() {
        val store = InMemoryRecordStore()
        store.begin().apply { put(Record("gone", 1, mapOf())); commit() }
        assertThat(store.size).isEqualTo(1)

        store.begin().apply { delete("gone"); commit() }
        assertThat(store.get("gone")).isNull()
        assertThat(store.size).isEqualTo(0)
    }

    @Test
    fun `keys returns all stored keys`() {
        val store = InMemoryRecordStore()
        store.begin().apply {
            put(Record("a", 1, mapOf()))
            put(Record("b", 1, mapOf()))
            commit()
        }
        assertThat(store.keys()).containsExactlyInAnyOrder("a", "b")
    }

    @Test
    fun `size reflects committed records`() {
        val store = InMemoryRecordStore()
        assertThat(store.size).isEqualTo(0)

        store.begin().apply { put(Record("x", 1, mapOf())); commit() }
        assertThat(store.size).isEqualTo(1)
    }

    @Test
    fun `failed migration leaves old data intact`() {
        val store = InMemoryRecordStore(config = StoreConfig(schemaVersion = 1))
        // Seed some data at v1.
        store.begin().apply {
            put(Record("original", 1, mapOf("data" to "keep")))
            commit()
        }
        assertThat(store.size).isEqualTo(1)
        assertThat(store.get("original")?.payload?.get("data")).isEqualTo("keep")

        // Run a migration that fails.
        val result = store.migrate(targetVersion = 2) { _ ->
            Result.failure(RuntimeException("Migration deliberately failed"))
        }
        assertThat(result.isFailure).isTrue()

        // Old data must be intact.
        assertThat(store.schemaVersion).isEqualTo(1L)
        assertThat(store.size).isEqualTo(1)
        assertThat(store.get("original")?.payload?.get("data")).isEqualTo("keep")
    }

    @Test
    fun `successful migration updates schema version`() {
        val store = InMemoryRecordStore(config = StoreConfig(schemaVersion = 1))
        store.begin().apply { put(Record("r", 1, mapOf())); commit() }

        val result = store.migrate(targetVersion = 2) { records ->
            Result.success(records.map { it.copy(version = it.version + 100) })
        }
        assertThat(result.isSuccess).isTrue()
        assertThat(store.schemaVersion).isEqualTo(2L)
        assertThat(store.get("r")?.version).isEqualTo(101L)
    }

    @Test
    fun `transaction is closed after commit`() {
        val store = InMemoryRecordStore()
        val tx = store.begin()
        tx.put(Record("x", 1, mapOf()))
        tx.commit()
        assertThat(tx.isClosed).isTrue()
    }

    @Test
    fun `transaction is closed after rollback`() {
        val store = InMemoryRecordStore()
        val tx = store.begin()
        tx.rollback()
        assertThat(tx.isClosed).isTrue()
    }

    @Test
    fun `cannot put after commit`() {
        val store = InMemoryRecordStore()
        val tx = store.begin()
        tx.commit()
        try {
            tx.put(Record("fail", 1, mapOf()))
            org.junit.jupiter.api.Assertions.fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected — require() throws IllegalArgumentException
        }
    }

    @Test
    fun `contract tests pass against InMemoryRecordStore`() {
        val contract = StoreContract { InMemoryRecordStore() }
        contract.`record identity is preserved`()
        contract.`duplicate key within transaction preserves last-write-wins`()
        contract.`duplicate key across commits overwrites`()
        contract.`rollback discards uncommitted changes`()
        contract.`delete removes record`()
        contract.`keys returns all stored keys`()
        contract.`size reflects committed records`()
    }
}

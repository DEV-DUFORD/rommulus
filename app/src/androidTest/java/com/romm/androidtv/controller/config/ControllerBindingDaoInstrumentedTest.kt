package com.romm.androidtv.controller.config

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies [ControllerBindingDao] against a real, in-memory SQLite database (Room
 * requires an actual Android SQLite implementation, which only an
 * instrumented test provides in this project).
 *
 * Plain JUnit4 `@RunWith(AndroidJUnit4::class)` instrumented test, matching the
 * existing androidTest convention.
 */
@RunWith(AndroidJUnit4::class)
class ControllerBindingDaoInstrumentedTest {

    private lateinit var db: ControllerConfigDatabase
    private lateinit var dao: ControllerBindingDao

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, ControllerConfigDatabase::class.java).build()
        dao = db.controllerBindingDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // -- helpers ---------------------------------------------------------------------------

    private fun entity(
        coreId: String = "snes9x",
        playerIndex: Int = 0,
        controlId: String = CoreControlId.BUTTON_A.id,
        bindingSlot: Int = BindingSlot.PRIMARY.index,
        bindingType: String = ControllerBindingCodec.TYPE_KEY,
        inputCode: Int = 84,
        polarity: Int? = null,
        schemaVersion: Int = ControllerBindingCodec.SCHEMA_VERSION,
    ) = ControllerBindingEntity(
        coreId = coreId,
        playerIndex = playerIndex,
        controlId = controlId,
        bindingSlot = bindingSlot,
        bindingType = bindingType,
        inputCode = inputCode,
        polarity = polarity,
        schemaVersion = schemaVersion,
    )

    // -- tests -----------------------------------------------------------------------------

    @Test
    fun upsertThenLoadForCoreRoundTrips() {
        runBlocking {
            val e = entity()
            dao.upsert(e)
            val rows = dao.loadForCore("snes9x")
            assertEquals(1, rows.size)
            assertEquals(e, rows[0])
        }
    }

    @Test
    fun upsertAllInsertsMultipleRows() {
        runBlocking {
            val rows = listOf(
                entity(controlId = CoreControlId.BUTTON_A.id),
                entity(controlId = CoreControlId.BUTTON_B.id),
                entity(controlId = CoreControlId.START.id),
            )
            dao.upsertAll(rows)
            val loaded = dao.loadForCore("snes9x")
            assertEquals(3, loaded.size)
            assertTrue(loaded.containsAll(rows))
        }
    }

    @Test
    fun primaryAndSecondaryRowsCoexistForOneControl() {
        runBlocking {
            dao.upsert(entity(bindingSlot = BindingSlot.PRIMARY.index, inputCode = 96))
            dao.upsert(entity(bindingSlot = BindingSlot.SECONDARY.index, inputCode = 100))

            val rows = dao.loadForCore("snes9x")

            assertEquals(2, rows.size)
            assertTrue(rows.any { it.bindingSlot == BindingSlot.PRIMARY.index && it.inputCode == 96 })
            assertTrue(rows.any { it.bindingSlot == BindingSlot.SECONDARY.index && it.inputCode == 100 })
        }
    }

    @Test
    fun loadForPlayerFiltersByPlayerIndex() {
        runBlocking {
            dao.upsert(entity(playerIndex = 0, controlId = CoreControlId.BUTTON_A.id))
            dao.upsert(entity(playerIndex = 1, controlId = CoreControlId.BUTTON_B.id))

            val p0 = dao.loadForPlayer("snes9x", 0)
            val p1 = dao.loadForPlayer("snes9x", 1)

            assertEquals(1, p0.size)
            assertEquals(CoreControlId.BUTTON_A.id, p0[0].controlId)
            assertEquals(1, p1.size)
            assertEquals(CoreControlId.BUTTON_B.id, p1[0].controlId)
        }
    }

    @Test
    fun deleteRemovesSingleRow() {
        runBlocking {
            dao.upsert(entity(controlId = CoreControlId.BUTTON_A.id))
            dao.upsert(entity(controlId = CoreControlId.BUTTON_B.id))

            dao.delete(
                "snes9x",
                0,
                CoreControlId.BUTTON_A.id,
                BindingSlot.PRIMARY.index,
            )

            val remaining = dao.loadForCore("snes9x")
            assertEquals(1, remaining.size)
            assertEquals(CoreControlId.BUTTON_B.id, remaining[0].controlId)
        }
    }

    @Test
    fun deletePlayerRemovesAllRowsForPlayer() {
        runBlocking {
            dao.upsert(entity(playerIndex = 0, controlId = CoreControlId.BUTTON_A.id))
            dao.upsert(entity(playerIndex = 0, controlId = CoreControlId.START.id))
            dao.upsert(entity(playerIndex = 1, controlId = CoreControlId.BUTTON_A.id))

            dao.deletePlayer("snes9x", 0)

            val all = dao.loadForCore("snes9x")
            assertEquals(1, all.size)
            assertEquals(1, all[0].playerIndex)
        }
    }

    @Test
    fun deleteCoreRemovesAllRowsForCore() {
        runBlocking {
            dao.upsert(entity(playerIndex = 0, controlId = CoreControlId.BUTTON_A.id))
            dao.upsert(entity(playerIndex = 1, controlId = CoreControlId.BUTTON_B.id))
            dao.upsert(entity(coreId = "fceumm", controlId = CoreControlId.BUTTON_A.id))

            dao.deleteCore("snes9x")

            val snes = dao.loadForCore("snes9x")
            val fce = dao.loadForCore("fceumm")

            assertTrue(snes.isEmpty())
            assertEquals(1, fce.size)
        }
    }

    @Test
    fun observeCoreEmitsCurrentRows() = runBlocking {
        dao.upsert(entity(controlId = CoreControlId.BUTTON_A.id))
        dao.upsert(entity(controlId = CoreControlId.BUTTON_B.id))

        val emitted = dao.observeCore("snes9x").take(1).first()
        assertEquals(2, emitted.size)
        assertTrue(emitted.any { it.controlId == CoreControlId.BUTTON_A.id })
        assertTrue(emitted.any { it.controlId == CoreControlId.BUTTON_B.id })
    }
}

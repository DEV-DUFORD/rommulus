package com.romm.androidtv.controller.config

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies multi-instance invalidation on [ControllerConfigDatabase].
 *
 * Simulates the main-process and `:emulation` process both opening the same
 * file-backed database. A row inserted via instance A must become visible to
 * instance B's Flow queries via Room's shared-cache invalidation mechanism.
 *
 * Uses a real file database (not in-memory) because in-memory DBs do not share
 * state across instances.
 */
@RunWith(AndroidJUnit4::class)
class ControllerConfigDatabaseMultiInstanceTest {

    private val testDbFile: String = "multi_instance_test.db"

    private lateinit var dbA: ControllerConfigDatabase
    private lateinit var dbB: ControllerConfigDatabase

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dbPath = context.getDatabasePath(testDbFile)

        // Clean slate: delete any leftover file from a previous run.
        dbPath.delete()

        dbA = Room.databaseBuilder(context, ControllerConfigDatabase::class.java, dbPath.absolutePath)
            .enableMultiInstanceInvalidation()
            .build()

        dbB = Room.databaseBuilder(context, ControllerConfigDatabase::class.java, dbPath.absolutePath)
            .enableMultiInstanceInvalidation()
            .build()
    }

    @After
    fun tearDown() {
        dbA.close()
        dbB.close()
        InstrumentationRegistry.getInstrumentation().targetContext
            .getDatabasePath(testDbFile)
            .delete()
    }

    @Test
    fun insertViaA_visibleViaB() = runBlocking {
        val daoA = dbA.controllerBindingDao()
        val daoB = dbB.controllerBindingDao()

        // Verify B starts empty.
        val initial = daoB.loadForCore("snes9x")
        assertTrue(initial.isEmpty())

        // Insert via instance A.
        val entity = ControllerBindingEntity(
            coreId = "snes9x",
            playerIndex = 0,
            controlId = "button_a",
            bindingType = ControllerBindingCodec.TYPE_KEY,
            inputCode = 84,
            polarity = null,
            schemaVersion = ControllerBindingCodec.SCHEMA_VERSION,
        )
        daoA.upsert(entity)

        // Observe via instance B's Flow with a timeout (multi-instance invalidation
        // should cause the Flow to re-emit).
        val emitted = withTimeout(5_000) {
            daoB.observeCore("snes9x").take(1).first()
        }

        assertEquals(1, emitted.size)
        assertEquals(entity, emitted[0])

        // Also verify a one-shot load via B.
        val loaded = daoB.loadForCore("snes9x")
        assertEquals(1, loaded.size)
        assertEquals(entity, loaded[0])
    }
}

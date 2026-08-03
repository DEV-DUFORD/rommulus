package com.romm.androidtv.controller.config

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for [ControllerBindingEntity] (CONTROLLER_SETTINGS.md Architecture section 2).
 *
 * All reads are scoped by [coreId], optionally narrowing to a single [playerIndex] — there is
 * deliberately no "list everything" query. The primary key triple
 * ([coreId], [playerIndex], [controlId]) means [upsert]/[upsertAll] overwrite the full row for
 * an existing tuple, which is the desired override-replace semantics.
 */
@Dao
interface ControllerBindingDao {

    /** Emits the current overrides for [coreId], then re-emits on any change (multi-process aware). */
    @Query("SELECT * FROM controller_bindings WHERE coreId = :coreId")
    fun observeCore(coreId: String): Flow<List<ControllerBindingEntity>>

    /** One-shot load of every override row for [coreId]. */
    @Query("SELECT * FROM controller_bindings WHERE coreId = :coreId")
    suspend fun loadForCore(coreId: String): List<ControllerBindingEntity>

    /** One-shot load of every override row for [coreId] and [playerIndex]. */
    @Query("SELECT * FROM controller_bindings WHERE coreId = :coreId AND playerIndex = :playerIndex")
    suspend fun loadForPlayer(coreId: String, playerIndex: Int): List<ControllerBindingEntity>

    /** Insert a row or replace the existing row with the same primary key triple. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ControllerBindingEntity)

    /** Insert or replace every row in [entities] in a single statement batch. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ControllerBindingEntity>)

    /** Delete the single binding identified by the primary key triple. */
    @Query(
        "DELETE FROM controller_bindings " +
            "WHERE coreId = :coreId AND playerIndex = :playerIndex AND controlId = :controlId",
    )
    suspend fun delete(coreId: String, playerIndex: Int, controlId: String)

    /** Delete every override for one player port of [coreId] (reset-player). */
    @Query("DELETE FROM controller_bindings WHERE coreId = :coreId AND playerIndex = :playerIndex")
    suspend fun deletePlayer(coreId: String, playerIndex: Int)

    /** Delete every override for [coreId] across all players (reset-core). */
    @Query("DELETE FROM controller_bindings WHERE coreId = :coreId")
    suspend fun deleteCore(coreId: String)
}

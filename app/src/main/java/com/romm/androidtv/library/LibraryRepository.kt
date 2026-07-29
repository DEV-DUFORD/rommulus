package com.romm.androidtv.library

import com.romm.androidtv.romm.RommApiError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * Wraps [LibraryApi] for the native browsing UI (UI_REFACTOR.md). Each
 * function is called independently by [HomeViewModel] so that one shelf's
 * failure never blocks the others from loading.
 */
sealed interface LibraryResult<out T> {
    data class Success<T>(val data: T) : LibraryResult<T>
    data class Failure(val error: RommApiError) : LibraryResult<Nothing>
}

interface LibraryRepository {
    suspend fun fetchRecentlyAdded(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchContinuePlaying(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchFavorites(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>>
    suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>>
}

class LibraryRepositoryImpl(
    private val client: OkHttpClient,
    /** Returns the currently configured RomM origin at call time (may change if settings are edited). */
    private val originProvider: () -> String,
) : LibraryRepository {

    override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.RecentlyAdded, limit)

    override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.ContinuePlaying, limit)

    override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.Favorites, limit)

    private suspend fun fetchRoms(query: RomQuery, limit: Int): LibraryResult<List<LibraryRom>> =
        withContext(Dispatchers.IO) {
            when (val result = LibraryApi.fetchRoms(client, originProvider(), query, limit)) {
                is RomListResult.Success -> LibraryResult.Success(result.roms)
                is RomListResult.Failure -> LibraryResult.Failure(result.error)
            }
        }

    override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> = withContext(Dispatchers.IO) {
        when (val result = LibraryApi.fetchPlatforms(client, originProvider())) {
            is PlatformListResult.Success -> LibraryResult.Success(result.platforms)
            is PlatformListResult.Failure -> LibraryResult.Failure(result.error)
        }
    }

    override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> = withContext(Dispatchers.IO) {
        when (val result = LibraryApi.fetchCollections(client, originProvider())) {
            is CollectionListResult.Success -> LibraryResult.Success(result.collections)
            is CollectionListResult.Failure -> LibraryResult.Failure(result.error)
        }
    }
}

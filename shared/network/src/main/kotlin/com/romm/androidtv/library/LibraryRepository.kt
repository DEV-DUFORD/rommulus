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
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : LibraryResult<Nothing>
}

interface LibraryRepository {
    suspend fun fetchRecentlyAdded(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchContinuePlaying(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchFavorites(limit: Int = 20): LibraryResult<List<LibraryRom>>
    suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>>
    suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>>
    /** Generic paginated ROM query — powers `PlatformDetailScreen`/`CollectionDetailScreen`. */
    suspend fun fetchRomsPage(query: RomQuery, limit: Int = 20, offset: Int = 0): LibraryResult<RomPage>
    /** Full detail for `GameDetailScreen`. */
    suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail>

    /** Owned-writable collections (filters server list by authenticated username). */
    suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
        LibraryResult.Failure(RommApiError.SERVER_ERROR)
    /** Creates a new collection on the server. */
    suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
        LibraryResult.Failure(RommApiError.SERVER_ERROR)
    /** Adds a ROM to an existing collection. */
    suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
        LibraryResult.Failure(RommApiError.SERVER_ERROR)
    /** Removes a ROM from an existing collection. */
    suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
        LibraryResult.Failure(RommApiError.SERVER_ERROR)
}

class LibraryRepositoryImpl(
    private val client: OkHttpClient,
    /** Returns the currently configured RomM origin at call time (may change if settings are edited). */
    private val originProvider: () -> String,
    /** Returns the authenticated username; null/blank means no coherent session. Defaults to null for callers that don't yet supply it. */
    private val usernameProvider: () -> String? = { null },
) : LibraryRepository {

    override suspend fun fetchRecentlyAdded(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.RecentlyAdded, limit)

    override suspend fun fetchContinuePlaying(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.ContinuePlaying, limit)

    override suspend fun fetchFavorites(limit: Int): LibraryResult<List<LibraryRom>> =
        fetchRoms(RomQuery.Favorites, limit)

    private suspend fun fetchRoms(query: RomQuery, limit: Int): LibraryResult<List<LibraryRom>> =
        when (val result = fetchRomsPage(query, limit, offset = 0)) {
            is LibraryResult.Success -> LibraryResult.Success(result.data.roms)
            is LibraryResult.Failure -> result
        }

    override suspend fun fetchRomsPage(query: RomQuery, limit: Int, offset: Int): LibraryResult<RomPage> =
        withContext(Dispatchers.IO) {
            when (val result = LibraryApi.fetchRoms(client, originProvider(), query, limit, offset)) {
                is RomListResult.Success -> LibraryResult.Success(RomPage(result.roms, result.total))
                is RomListResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
            }
        }

    override suspend fun fetchRomDetail(romId: Long): LibraryResult<RomDetail> = withContext(Dispatchers.IO) {
        when (val result = LibraryApi.fetchRomDetail(client, originProvider(), romId)) {
            is RomDetailResult.Success -> LibraryResult.Success(result.rom)
            is RomDetailResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
        }
    }

    override suspend fun fetchPlatforms(): LibraryResult<List<PlatformSummary>> = withContext(Dispatchers.IO) {
        when (val result = LibraryApi.fetchPlatforms(client, originProvider())) {
            // Empty platforms (romCount == 0) are typically stale/duplicate entries left
            // behind on the server (e.g. a platform created before its slug was corrected
            // by a rescan) and have nothing for the user to browse, so they're hidden here.
            is PlatformListResult.Success -> LibraryResult.Success(result.platforms.filter { it.romCount > 0 })
            is PlatformListResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
        }
    }

    override suspend fun fetchCollections(): LibraryResult<List<CollectionSummary>> = withContext(Dispatchers.IO) {
        when (val result = LibraryApi.fetchCollections(client, originProvider())) {
            is CollectionListResult.Success -> LibraryResult.Success(result.collections)
            is CollectionListResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
        }
    }

    override suspend fun fetchOwnedWritableCollections(): LibraryResult<List<CollectionSummary>> =
        withContext(Dispatchers.IO) {
            val username = usernameProvider()?.trim()
            if (username.isNullOrBlank()) {
                return@withContext LibraryResult.Failure(RommApiError.AUTH_EXPIRED)
            }
            when (val result = LibraryApi.fetchCollections(client, originProvider())) {
                is CollectionListResult.Success -> {
                    val owned = result.collections.filter { it.ownerUsername.trim() == username }
                    LibraryResult.Success(owned)
                }
                is CollectionListResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
            }
        }

    override suspend fun createCollection(name: String, isFavorite: Boolean): LibraryResult<CollectionSummary> =
        withContext(Dispatchers.IO) {
            when (val result = LibraryApi.createCollection(client, originProvider(), name, isFavorite)) {
                is CollectionMutationResult.Success -> LibraryResult.Success(result.collection)
                is CollectionMutationResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
            }
        }

    override suspend fun addRomToCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
        withContext(Dispatchers.IO) {
            when (val result = LibraryApi.addRomToCollection(client, originProvider(), collectionId, romId)) {
                is CollectionMutationResult.Success -> LibraryResult.Success(result.collection)
                is CollectionMutationResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
            }
        }

    override suspend fun removeRomFromCollection(collectionId: Long, romId: Long): LibraryResult<CollectionSummary> =
        withContext(Dispatchers.IO) {
            when (val result = LibraryApi.removeRomFromCollection(client, originProvider(), collectionId, romId)) {
                is CollectionMutationResult.Success -> LibraryResult.Success(result.collection)
                is CollectionMutationResult.Failure -> LibraryResult.Failure(result.error, result.httpCode)
            }
        }
}

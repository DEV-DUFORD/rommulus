@file:OptIn(ExperimentalStdlibApi::class)

package com.romm.androidtv.library

import com.romm.androidtv.network.RommOrigin
import com.romm.androidtv.romm.RommApiError
import com.squareup.moshi.JsonClass
import com.squareup.moshi.KotlinJsonAdapterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapter
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Typed models and network calls for RomM's library-listing endpoints
 * (`GET /api/platforms`, `GET /api/roms`, `GET /api/collections`), used by
 * the native browsing UI (UI_REFACTOR.md). Response shapes were audited
 * against a live RomM 5+ server (`PlatformSchema`, `SimpleRomSchema`,
 * `CollectionSchema` in the reference backend) — only the fields this client
 * actually needs are modeled; every other field RomM returns is ignored by
 * Moshi's default lenient parsing.
 *
 * Deliberately separate from [com.romm.androidtv.romm.RommApi], which is
 * scoped to single-ROM metadata/launch, not list browsing.
 *
 * Following [com.romm.androidtv.romm.RommApi]'s existing convention: pure
 * JSON-parsing functions are separated from the network-call functions so
 * parsing logic is unit-testable without a live/mock server.
 */

sealed interface PlatformListResult {
    data class Success(val platforms: List<PlatformSummary>) : PlatformListResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : PlatformListResult
}

sealed interface RomListResult {
    data class Success(val roms: List<LibraryRom>, val total: Int) : RomListResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : RomListResult
}

sealed interface CollectionListResult {
    data class Success(val collections: List<CollectionSummary>) : CollectionListResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : CollectionListResult
}

sealed interface RomDetailResult {
    data class Success(val rom: RomDetail) : RomDetailResult
    data class Failure(val error: RommApiError, val httpCode: Int? = null) : RomDetailResult
}

/** Selects which `GET /api/roms` shelf/query to run. */
sealed interface RomQuery {
    /** `order_by=created_at&order_dir=desc` */
    object RecentlyAdded : RomQuery

    /** `last_played=true&order_by=last_played&order_dir=desc` */
    object ContinuePlaying : RomQuery

    /** `favorite=true` */
    object Favorites : RomQuery

    /** `search_term=<query>` (not yet surfaced in the UI; reserved for a future search screen). */
    data class Search(val term: String) : RomQuery

    /** `platform_ids=<id>` — powers `PlatformDetailScreen`. */
    data class ByPlatform(val platformId: Long) : RomQuery

    /** `collection_id=<id>` — powers `CollectionDetailScreen`. Verified live: distinct from the `collections`/`collection_ids` filters, which mean something else. */
    data class ByCollection(val collectionId: Long) : RomQuery
}

@JsonClass(generateAdapter = false)
internal data class PlatformJson(
    val id: Long = 0,
    val name: String = "",
    val custom_name: String? = null,
    val display_name: String? = null,
    val rom_count: Int = 0,
    val url_logo: String? = null,
)

@JsonClass(generateAdapter = false)
internal data class RomUserJson(
    val last_played: String? = null,
    val now_playing: Boolean = false,
)

@JsonClass(generateAdapter = false)
internal data class LibraryRomJson(
    val id: Long = 0,
    val name: String? = null,
    val fs_name_no_tags: String = "",
    val platform_display_name: String = "",
    val path_cover_small: String? = null,
    val path_cover_large: String? = null,
    val url_cover: String? = null,
    val rom_user: RomUserJson? = null,
)

@JsonClass(generateAdapter = false)
internal data class RomsPageJson(
    val items: List<LibraryRomJson>? = null,
    val total: Int = 0,
)

@JsonClass(generateAdapter = false)
internal data class CollectionJson(
    val id: Long = 0,
    val name: String = "",
    val rom_count: Int = 0,
    val path_cover_large: String? = null,
    val path_covers_large: List<String>? = null,
)

@JsonClass(generateAdapter = false)
internal data class RomMetadatumJson(
    val genres: List<String> = emptyList(),
    val companies: List<String> = emptyList(),
    val game_modes: List<String> = emptyList(),
    val player_count: String? = null,
    val first_release_date: Long? = null,
    val average_rating: Double? = null,
)

@JsonClass(generateAdapter = false)
internal data class RomDetailJson(
    val id: Long = 0,
    val name: String? = null,
    val fs_name_no_tags: String = "",
    val platform_display_name: String = "",
    val summary: String? = null,
    val path_cover_small: String? = null,
    val path_cover_large: String? = null,
    val url_cover: String? = null,
    val merged_screenshots: List<String>? = null,
    val metadatum: RomMetadatumJson? = null,
    val regions: List<String>? = null,
    val languages: List<String>? = null,
    val fs_size_bytes: Long = 0,
    val rom_user: RomUserJson? = null,
)

object LibraryApi {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()
    private val platformListAdapter = moshi.adapter<List<PlatformJson>>()
    private val romsPageAdapter = moshi.adapter<RomsPageJson>()
    private val collectionListAdapter = moshi.adapter<List<CollectionJson>>()
    private val romDetailAdapter = moshi.adapter<RomDetailJson>()

    // ---- Pure JSON parsing (unit-testable without a live server) ----

    /** Parses the JSON body of `GET /api/platforms`. Returns null on any malformed input. */
    fun parsePlatformList(body: String): List<PlatformSummary>? {
        return try {
            val json = platformListAdapter.fromJson(body.trim()) ?: return null
            json.map {
                PlatformSummary(
                    id = it.id,
                    displayName = it.display_name?.takeIf { name -> name.isNotBlank() }
                        ?: it.custom_name?.takeIf { name -> name.isNotBlank() }
                        ?: it.name,
                    romCount = it.rom_count,
                    logoUrl = it.url_logo,
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses the JSON body of `GET /api/roms` (a `CustomLimitOffsetPage`). Cover
     * paths are resolved against [origin] since RomM returns them origin-relative
     * (e.g. `/assets/romm/resources/roms/14/277/cover/small.png?ts=...`).
     * Returns null on any malformed input.
     */
    fun parseRomsPage(body: String, origin: String): RomListResult.Success? {
        return try {
            val json = romsPageAdapter.fromJson(body.trim()) ?: return null
            val roms = json.items.orEmpty().map {
                LibraryRom(
                    id = it.id,
                    title = it.name?.takeIf { name -> name.isNotBlank() } ?: it.fs_name_no_tags,
                    platformDisplayName = it.platform_display_name,
                    coverUrl = resolveCoverUrl(origin, it.path_cover_large ?: it.path_cover_small, it.url_cover),
                    lastPlayedIso = it.rom_user?.last_played,
                    nowPlaying = it.rom_user?.now_playing ?: false,
                )
            }
            RomListResult.Success(roms, json.total)
        } catch (_: Exception) {
            null
        }
    }

    /** Parses the JSON body of `GET /api/collections`. Returns null on any malformed input. */
    fun parseCollectionList(body: String, origin: String): List<CollectionSummary>? {
        return try {
            val json = collectionListAdapter.fromJson(body.trim()) ?: return null
            json.map {
                CollectionSummary(
                    id = it.id,
                    name = it.name,
                    romCount = it.rom_count,
                    coverUrl = resolveCoverUrl(
                        origin,
                        it.path_cover_large ?: it.path_covers_large?.firstOrNull(),
                        null,
                    ),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Resolves a possibly origin-relative cover path to an absolute URL.
     * [relativePath] wins if present (RomM's own served asset); [absoluteFallback]
     * (e.g. an external metadata-provider CDN URL) is used only if there is no
     * relative path on file. Returns null if neither is present.
     */
    internal fun resolveCoverUrl(origin: String, relativePath: String?, absoluteFallback: String?): String? {
        if (!relativePath.isNullOrBlank()) {
            if (relativePath.startsWith("http://") || relativePath.startsWith("https://")) return relativePath
            val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
            val path = if (relativePath.startsWith("/")) relativePath else "/$relativePath"
            return "$normalizedOrigin$path"
        }
        return absoluteFallback?.takeIf { it.isNotBlank() }
    }

    /**
     * Parses the JSON body of `GET /api/roms/{id}` (the full `RomSchema`).
     * Returns null on any malformed input.
     */
    fun parseRomDetail(body: String, origin: String): RomDetail? {
        return try {
            val json = romDetailAdapter.fromJson(body.trim()) ?: return null
            val metadatum = json.metadatum
            RomDetail(
                id = json.id,
                title = json.name?.takeIf { it.isNotBlank() } ?: json.fs_name_no_tags,
                platformDisplayName = json.platform_display_name,
                summary = json.summary?.takeIf { it.isNotBlank() },
                coverUrl = resolveCoverUrl(origin, json.path_cover_large ?: json.path_cover_small, json.url_cover),
                screenshotUrls = json.merged_screenshots.orEmpty().mapNotNull { resolveCoverUrl(origin, it, null) },
                genres = metadatum?.genres.orEmpty(),
                companies = metadatum?.companies.orEmpty(),
                gameModes = metadatum?.game_modes.orEmpty(),
                playerCount = metadatum?.player_count?.takeIf { it.isNotBlank() },
                firstReleaseDateEpochMillis = metadatum?.first_release_date,
                averageRating = metadatum?.average_rating?.toFloat(),
                regions = json.regions.orEmpty(),
                languages = json.languages.orEmpty(),
                fileSizeBytes = json.fs_size_bytes,
                lastPlayedIso = json.rom_user?.last_played,
                nowPlaying = json.rom_user?.now_playing ?: false,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ---- Network calls ----

    /** `GET /api/platforms`. */
    fun fetchPlatforms(client: OkHttpClient, origin: String): PlatformListResult {
        if (origin.isBlank()) return PlatformListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = originUrl(origin)?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("platforms")
            ?.build()
            ?: return PlatformListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return PlatformListResult.Failure(it, response.code) }
                val body = response.body?.string()
                val platforms = body?.let { parsePlatformList(it) }
                if (platforms == null) PlatformListResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else PlatformListResult.Success(platforms)
            }
        } catch (e: IOException) {
            PlatformListResult.Failure(classifyIOException(e))
        }
    }

    /** `GET /api/roms`, shaped by [query] (see [RomQuery]). [offset] enables pagination for detail-screen grids. */
    fun fetchRoms(client: OkHttpClient, origin: String, query: RomQuery, limit: Int = 20, offset: Int = 0): RomListResult {
        if (origin.isBlank()) return RomListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val base = originUrl(origin)
            ?: return RomListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val builder = base.newBuilder()
            .addPathSegment("api")
            .addPathSegment("roms")
            .addQueryParameter("with_char_index", "false")
            .addQueryParameter("with_filter_values", "false")
            .addQueryParameter("with_rom_id_index", "false")
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("offset", offset.toString())

        when (query) {
            is RomQuery.RecentlyAdded -> {
                builder.addQueryParameter("order_by", "created_at")
                builder.addQueryParameter("order_dir", "desc")
            }
            is RomQuery.ContinuePlaying -> {
                builder.addQueryParameter("last_played", "true")
                builder.addQueryParameter("order_by", "last_played")
                builder.addQueryParameter("order_dir", "desc")
            }
            is RomQuery.Favorites -> {
                builder.addQueryParameter("favorite", "true")
            }
            is RomQuery.Search -> {
                builder.addQueryParameter("search_term", query.term)
            }
            is RomQuery.ByPlatform -> {
                builder.addQueryParameter("platform_ids", query.platformId.toString())
            }
            is RomQuery.ByCollection -> {
                builder.addQueryParameter("collection_id", query.collectionId.toString())
            }
        }

        val request = Request.Builder().url(builder.build()).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return RomListResult.Failure(it, response.code) }
                val body = response.body?.string()
                val page = body?.let { parseRomsPage(it, origin) }
                page ?: RomListResult.Failure(RommApiError.PARSE_ERROR, response.code)
            }
        } catch (e: IOException) {
            RomListResult.Failure(classifyIOException(e))
        }
    }

    /** `GET /api/collections`. */
    fun fetchCollections(client: OkHttpClient, origin: String): CollectionListResult {
        if (origin.isBlank()) return CollectionListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = originUrl(origin)?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("collections")
            ?.build()
            ?: return CollectionListResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return CollectionListResult.Failure(it, response.code) }
                val body = response.body?.string()
                val collections = body?.let { parseCollectionList(it, origin) }
                if (collections == null) CollectionListResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else CollectionListResult.Success(collections)
            }
        } catch (e: IOException) {
            CollectionListResult.Failure(classifyIOException(e))
        }
    }

    /** `GET /api/roms/{id}` — full detail for `GameDetailScreen`. */
    fun fetchRomDetail(client: OkHttpClient, origin: String, romId: Long): RomDetailResult {
        if (origin.isBlank()) return RomDetailResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)
        val url = originUrl(origin)?.newBuilder()
            ?.addPathSegment("api")
            ?.addPathSegment("roms")
            ?.addPathSegment(romId.toString())
            ?.build()
            ?: return RomDetailResult.Failure(RommApiError.ORIGIN_NOT_CONFIGURED)

        val request = Request.Builder().url(url).get().build()
        return try {
            client.newCall(request).execute().use { response ->
                classifyResponse(response)?.let { return RomDetailResult.Failure(it, response.code) }
                val body = response.body?.string()
                val rom = body?.let { parseRomDetail(it, origin) }
                if (rom == null) RomDetailResult.Failure(RommApiError.PARSE_ERROR, response.code)
                else RomDetailResult.Success(rom)
            }
        } catch (e: IOException) {
            RomDetailResult.Failure(classifyIOException(e))
        }
    }

    private fun originUrl(origin: String): HttpUrl? {
        val normalizedOrigin = RommOrigin.parse(origin)?.toUrl() ?: origin.removeSuffix("/")
        return normalizedOrigin.toHttpUrlOrNull()
    }

    private fun classifyResponse(response: Response): RommApiError? {
        return when {
            response.isSuccessful -> null
            response.code == 401 || response.code == 403 -> RommApiError.AUTH_EXPIRED
            response.code == 404 -> RommApiError.NOT_FOUND
            else -> RommApiError.SERVER_ERROR
        }
    }

    private fun classifyIOException(e: IOException): RommApiError {
        val cause = e.cause ?: e
        return if (cause is javax.net.ssl.SSLException ||
            cause.javaClass.name.contains("SSL", ignoreCase = true)
        ) {
            RommApiError.TLS_ERROR
        } else {
            RommApiError.NETWORK_ERROR
        }
    }
}

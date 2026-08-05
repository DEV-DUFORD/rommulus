package com.romm.androidtv.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Parser-only tests against synthetic fixture JSON matching the real RomM
 * 5+ response shapes documented in UI_REFACTOR.md section 3 (verified live
 * against a real server; these fixtures are hand-written, not captured real
 * data). No network calls, no real server URLs/credentials.
 */
@DisplayName("LibraryApi — platform/rom/collection list parsing")
class LibraryApiTest {

    private val origin = "https://example.test"

    @Nested
    @DisplayName("parsePlatformList")
    inner class ParsePlatformList {

        @Test
        fun `parses normal platform list`() {
            val body = """
                [{"id": 34, "slug": "dc", "name": "Dreamcast", "custom_name": "",
                  "display_name": "Dreamcast", "rom_count": 343, "url_logo": "https://cdn.example/dc.jpg"}]
            """.trimIndent()

            val result = LibraryApi.parsePlatformList(body, origin)

            assertThat(result).isNotNull
            assertThat(result).hasSize(1)
            assertThat(result!![0]).isEqualTo(
                PlatformSummary(
                    id = 34,
                    displayName = "Dreamcast",
                    romCount = 343,
                    logoUrl = "https://cdn.example/dc.jpg",
                    iconUrl = "https://example.test/assets/platforms/dc.svg",
                    iconUrlCandidates = listOf(
                        "https://example.test/assets/platforms/dc.svg",
                        "https://example.test/assets/platforms/dc.ico",
                    ),
                    slug = "dc",
                )
            )
        }

        @Test
        fun `falls back to name when display_name and custom_name are blank or missing`() {
            val body = """
                [{"id": 21, "slug": "gb", "name": "Game Boy", "custom_name": "",
                  "rom_count": 609, "url_logo": null}]
            """.trimIndent()

            val result = LibraryApi.parsePlatformList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].displayName).isEqualTo("Game Boy")
            assertThat(result[0].slug).isEqualTo("gb")
            assertThat(result[0].logoUrl).isNull()
            assertThat(result[0].iconUrl).isEqualTo("https://example.test/assets/platforms/gb.svg")
        }

        @Test
        fun `parses empty array`() {
            val result = LibraryApi.parsePlatformList("[]", origin)
            assertThat(result).isNotNull
            assertThat(result).isEmpty()
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(LibraryApi.parsePlatformList("not json", origin)).isNull()
        }

        @Test
        fun `iconUrl is null and candidates empty when slug is blank`() {
            val body = """
                [{"id": 2, "slug": "", "name": "Gameboy", "custom_name": "",
                  "rom_count": 0, "url_logo": null}]
            """.trimIndent()

            val result = LibraryApi.parsePlatformList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].iconUrl).isNull()
            assertThat(result[0].iconUrlCandidates).isEmpty()
        }

        @Test
        fun `icon candidates fall back from svg to ico, matching the webapp's own resolution order`() {
            val body = """
                [{"id": 31, "slug": "segacd", "name": "Sega CD", "custom_name": "",
                  "rom_count": 225, "url_logo": null}]
            """.trimIndent()

            val result = LibraryApi.parsePlatformList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].iconUrl).isEqualTo("https://example.test/assets/platforms/segacd.svg")
            assertThat(result[0].iconUrlCandidates).containsExactly(
                "https://example.test/assets/platforms/segacd.svg",
                "https://example.test/assets/platforms/segacd.ico",
            )
        }
    }

    @Nested
    @DisplayName("parseRomsPage")
    inner class ParseRomsPage {

        @Test
        fun `parses normal roms page with relative cover path resolved against origin`() {
            val body = """
                {"items": [{
                    "id": 38035, "name": "Live A Live", "fs_name_no_tags": "Live A Live",
                    "platform_display_name": "Super Nintendo Entertainment System",
                    "path_cover_small": "/assets/romm/resources/roms/14/277/cover/small.png?ts=1",
                    "path_cover_large": "/assets/romm/resources/roms/14/277/cover/big.png?ts=1",
                    "url_cover": null,
                    "rom_user": {"last_played": "2026-07-27T09:21:05+00:00", "now_playing": false}
                }], "total": 1}
            """.trimIndent()

            val result = LibraryApi.parseRomsPage(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.total).isEqualTo(1)
            assertThat(result.roms).hasSize(1)
            val rom = result.roms[0]
            assertThat(rom.id).isEqualTo(38035)
            assertThat(rom.title).isEqualTo("Live A Live")
            assertThat(rom.platformDisplayName).isEqualTo("Super Nintendo Entertainment System")
            assertThat(rom.coverUrl).isEqualTo("$origin/assets/romm/resources/roms/14/277/cover/big.png?ts=1")
            assertThat(rom.lastPlayedIso).isEqualTo("2026-07-27T09:21:05+00:00")
            assertThat(rom.nowPlaying).isFalse
        }

        @Test
        fun `falls back to fs_name_no_tags when name is null, and to url_cover when no relative path exists`() {
            val body = """
                {"items": [{
                    "id": 1, "name": null, "fs_name_no_tags": "Some Rom",
                    "platform_display_name": "Game Boy",
                    "path_cover_small": null, "path_cover_large": null,
                    "url_cover": "https://cdn.example/cover.jpg",
                    "rom_user": null
                }], "total": 1}
            """.trimIndent()

            val result = LibraryApi.parseRomsPage(body, origin)

            assertThat(result).isNotNull
            val rom = result!!.roms[0]
            assertThat(rom.title).isEqualTo("Some Rom")
            assertThat(rom.coverUrl).isEqualTo("https://cdn.example/cover.jpg")
            assertThat(rom.lastPlayedIso).isNull()
            assertThat(rom.nowPlaying).isFalse
        }

        @Test
        fun `parses empty items list`() {
            val result = LibraryApi.parseRomsPage("""{"items": [], "total": 0}""", origin)
            assertThat(result).isNotNull
            assertThat(result!!.roms).isEmpty()
            assertThat(result.total).isEqualTo(0)
        }

        @Test
        fun `treats missing items field as empty rather than failing`() {
            val result = LibraryApi.parseRomsPage("""{"total": 0}""", origin)
            assertThat(result).isNotNull
            assertThat(result!!.roms).isEmpty()
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(LibraryApi.parseRomsPage("not json", origin)).isNull()
        }
    }

    @Nested
    @DisplayName("parseCollectionList")
    inner class ParseCollectionList {

        @Test
        fun `parses normal collection list preferring path_cover_large`() {
            val body = """
                [{"id": 1, "name": "Duf's Favorites", "rom_count": 17,
                  "path_cover_large": "/assets/romm/resources/collections/1/cover/big.png",
                  "path_covers_large": ["/assets/romm/resources/roms/1/cover/big.png"]}]
            """.trimIndent()

            val result = LibraryApi.parseCollectionList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0]).isEqualTo(
                CollectionSummary(
                    id = 1,
                    name = "Duf's Favorites",
                    romCount = 17,
                    coverUrl = "$origin/assets/romm/resources/collections/1/cover/big.png",
                )
            )
        }

        @Test
        fun `falls back to first of path_covers_large when path_cover_large is null`() {
            val body = """
                [{"id": 2, "name": "Kino", "rom_count": 3, "path_cover_large": null,
                  "path_covers_large": ["/assets/romm/resources/roms/9/cover/big.png", "/assets/x/cover/big.png"]}]
            """.trimIndent()

            val result = LibraryApi.parseCollectionList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].coverUrl).isEqualTo("$origin/assets/romm/resources/roms/9/cover/big.png")
        }

        @Test
        fun `returns null cover when neither path_cover_large nor path_covers_large has anything`() {
            val body = """
                [{"id": 3, "name": "Empty", "rom_count": 0, "path_cover_large": null, "path_covers_large": []}]
            """.trimIndent()

            val result = LibraryApi.parseCollectionList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].coverUrl).isNull()
        }

        @Test
        fun `parses empty array`() {
            val result = LibraryApi.parseCollectionList("[]", origin)
            assertThat(result).isNotNull
            assertThat(result).isEmpty()
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(LibraryApi.parseCollectionList("not json", origin)).isNull()
        }
    }

    @Nested
    @DisplayName("parseCollection / parseCollectionList — expanded fields")
    inner class ParseCollection {

        @Test
        fun `parses rom_ids into a Set of Long`() {
            val body = """
                [{"id": 5, "name": "Metroidvania", "rom_count": 3,
                  "rom_ids": [10, 20, 30], "is_public": true, "is_favorite": true}]
            """.trimIndent()

            val result = LibraryApi.parseCollectionList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0].romIds).containsExactly(10L, 20L, 30L)
        }

        @Test
        fun `parses is_favorite is_public is_virtual and is_smart flags`() {
            val body = """
                {"id": 9, "name": "Fav", "rom_count": 0,
                  "rom_ids": [], "is_public": true, "is_favorite": true,
                  "is_virtual": true, "is_smart": true}
            """.trimIndent()

            val result = LibraryApi.parseCollection(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.isPublic).isTrue
            assertThat(result.isFavorite).isTrue
            assertThat(result.isVirtual).isTrue
            assertThat(result.isSmart).isTrue
        }

        @Test
        fun `parses user_id and owner_username`() {
            val body = """
                {"id": 9, "name": "Shared", "rom_count": 0, "rom_ids": [],
                  "user_id": 42, "owner_username": "zack"}
            """.trimIndent()

            val result = LibraryApi.parseCollection(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.userId).isEqualTo(42)
            assertThat(result.ownerUsername).isEqualTo("zack")
        }

        @Test
        fun `preserves existing cover fallback behavior with new fields present`() {
            val body = """
                {"id": 1, "name": "Duf", "rom_count": 17,
                  "path_cover_large": "/assets/romm/resources/collections/1/cover/big.png",
                  "path_covers_large": ["/assets/romm/resources/roms/1/cover/big.png"],
                  "rom_ids": [1, 2], "is_favorite": true}
            """.trimIndent()

            val result = LibraryApi.parseCollection(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.coverUrl).isEqualTo("$origin/assets/romm/resources/collections/1/cover/big.png")
            assertThat(result.romIds).containsExactly(1L, 2L)
            assertThat(result.isFavorite).isTrue
        }

        @Test
        fun `handles empty rom_ids as an empty set`() {
            val body = """
                {"id": 3, "name": "Empty", "rom_count": 0, "rom_ids": []}
            """.trimIndent()

            val result = LibraryApi.parseCollection(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.romIds).isEmpty()
        }

        @Test
        fun `missing rom_ids defaults to an empty set`() {
            val result = LibraryApi.parseCollection("""{"id": 3, "name": "NoIds", "rom_count": 0}""", origin)

            assertThat(result).isNotNull
            assertThat(result!!.romIds).isEmpty()
        }

        @Test
        fun `fails predictably on malformed json`() {
            assertThat(LibraryApi.parseCollection("not json", origin)).isNull()
        }

        @Test
        fun `returns null when rom_ids contains a non-numeric entry`() {
            val body = """
                {"id": 3, "name": "Bad", "rom_count": 0, "rom_ids": [1, "nope"]}
            """.trimIndent()

            assertThat(LibraryApi.parseCollection(body, origin)).isNull()
        }

        @Test
        fun `defaults flags false when omitted, matching pre-expansion behavior`() {
            val body = """
                [{"id": 1, "name": "Duf", "rom_count": 17,
                  "path_cover_large": "/assets/romm/resources/collections/1/cover/big.png",
                  "path_covers_large": ["/assets/romm/resources/roms/1/cover/big.png"]}]
            """.trimIndent()

            val result = LibraryApi.parseCollectionList(body, origin)

            assertThat(result).isNotNull
            assertThat(result!![0]).isEqualTo(
                CollectionSummary(
                    id = 1,
                    name = "Duf",
                    romCount = 17,
                    coverUrl = "$origin/assets/romm/resources/collections/1/cover/big.png",
                )
            )
            assertThat(result[0].romIds).isEmpty()
            assertThat(result[0].isFavorite).isFalse
            assertThat(result[0].isVirtual).isFalse
        }
    }

    @Nested
    @DisplayName("parseRomDetail")
    inner class ParseRomDetail {

        @Test
        fun `parses full rom detail with all fields populated`() {
            val body = """
                {
                    "id": 38035, "name": "Live A Live", "fs_name_no_tags": "Live A Live",
                    "fs_name_no_ext": "Live A Live (Disc 1)",
                    "platform_display_name": "Super Nintendo Entertainment System",
                    "summary": "A tale told across seven eras.",
                    "path_cover_small": null,
                    "path_cover_large": "/assets/romm/resources/roms/14/277/cover/big.png?ts=1",
                    "url_cover": null,
                    "merged_screenshots": ["/assets/romm/resources/roms/14/277/screenshots/0.jpg", "https://cdn.example/1.jpg"],
                    "metadatum": {
                        "genres": ["RPG"], "companies": ["Square"], "game_modes": ["Single player"],
                        "player_count": "1", "first_release_date": 788918400000, "average_rating": 91.4
                    },
                    "regions": ["USA"], "languages": ["English"],
                    "fs_size_bytes": 4194304,
                    "rom_user": {"last_played": "2026-07-27T09:21:05+00:00", "now_playing": true},
                    "sibling_roms": [
                        {"id": 38036, "name": "Live A Live (Disc 2)", "fs_name_no_ext": "Live A Live (Disc 2)", "is_main_sibling": false}
                    ]
                }
            """.trimIndent()

            val result = LibraryApi.parseRomDetail(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.id).isEqualTo(38035)
            assertThat(result.title).isEqualTo("Live A Live")
            assertThat(result.platformDisplayName).isEqualTo("Super Nintendo Entertainment System")
            assertThat(result.summary).isEqualTo("A tale told across seven eras.")
            assertThat(result.coverUrl).isEqualTo("$origin/assets/romm/resources/roms/14/277/cover/big.png?ts=1")
            assertThat(result.screenshotUrls).containsExactly(
                "$origin/assets/romm/resources/roms/14/277/screenshots/0.jpg",
                "https://cdn.example/1.jpg",
            )
            assertThat(result.genres).containsExactly("RPG")
            assertThat(result.companies).containsExactly("Square")
            assertThat(result.gameModes).containsExactly("Single player")
            assertThat(result.playerCount).isEqualTo("1")
            assertThat(result.firstReleaseDateEpochMillis).isEqualTo(788918400000)
            assertThat(result.averageRating).isEqualTo(91.4f)
            assertThat(result.regions).containsExactly("USA")
            assertThat(result.languages).containsExactly("English")
            assertThat(result.fileSizeBytes).isEqualTo(4194304)
            assertThat(result.lastPlayedIso).isEqualTo("2026-07-27T09:21:05+00:00")
            assertThat(result.nowPlaying).isTrue
            assertThat(result.fileName).isEqualTo("Live A Live (Disc 1)")
            assertThat(result.siblingRoms).hasSize(1)
            assertThat(result.siblingRoms[0].id).isEqualTo(38036)
            assertThat(result.siblingRoms[0].title).isEqualTo("Live A Live (Disc 2)")
            assertThat(result.siblingRoms[0].fileName).isEqualTo("Live A Live (Disc 2)")
            assertThat(result.siblingRoms[0].isMainSibling).isFalse
        }

        @Test
        fun `falls back to fs_name_no_ext for a sibling with no name, and defaults siblingRoms to empty when absent`() {
            val bodyWithUnnamedSibling = """
                {
                    "id": 1, "name": "Some Game", "fs_name_no_tags": "Some Game",
                    "platform_display_name": "Game Boy",
                    "fs_size_bytes": 0,
                    "sibling_roms": [
                        {"id": 2, "name": null, "fs_name_no_ext": "Some Game (Disc 1)", "is_main_sibling": true}
                    ]
                }
            """.trimIndent()

            val result = LibraryApi.parseRomDetail(bodyWithUnnamedSibling, origin)

            assertThat(result).isNotNull
            assertThat(result!!.siblingRoms).hasSize(1)
            assertThat(result.siblingRoms[0].title).isEqualTo("Some Game (Disc 1)")
            assertThat(result.siblingRoms[0].fileName).isEqualTo("Some Game (Disc 1)")
            assertThat(result.siblingRoms[0].isMainSibling).isTrue

            val bodyWithNoSiblings = """
                {
                    "id": 1, "name": "Some Game", "fs_name_no_tags": "Some Game",
                    "platform_display_name": "Game Boy",
                    "fs_size_bytes": 0
                }
            """.trimIndent()

            assertThat(LibraryApi.parseRomDetail(bodyWithNoSiblings, origin)!!.siblingRoms).isEmpty()
        }

        @Test
        fun `falls back to fs_name_no_tags and blank-strips optional fields when metadatum and rom_user are absent`() {
            val body = """
                {
                    "id": 1, "name": null, "fs_name_no_tags": "Some Rom",
                    "platform_display_name": "Game Boy",
                    "summary": "",
                    "path_cover_small": null, "path_cover_large": null, "url_cover": null,
                    "merged_screenshots": null,
                    "metadatum": null,
                    "regions": null, "languages": null,
                    "fs_size_bytes": 0,
                    "rom_user": null
                }
            """.trimIndent()

            val result = LibraryApi.parseRomDetail(body, origin)

            assertThat(result).isNotNull
            assertThat(result!!.title).isEqualTo("Some Rom")
            assertThat(result.summary).isNull()
            assertThat(result.coverUrl).isNull()
            assertThat(result.screenshotUrls).isEmpty()
            assertThat(result.genres).isEmpty()
            assertThat(result.playerCount).isNull()
            assertThat(result.firstReleaseDateEpochMillis).isNull()
            assertThat(result.averageRating).isNull()
            assertThat(result.regions).isEmpty()
            assertThat(result.fileSizeBytes).isEqualTo(0)
            assertThat(result.lastPlayedIso).isNull()
            assertThat(result.nowPlaying).isFalse
            assertThat(result.siblingRoms).isEmpty()
        }

        @Test
        fun `returns null for malformed json`() {
            assertThat(LibraryApi.parseRomDetail("not json", origin)).isNull()
        }
    }

    @Nested
    @DisplayName("resolveCoverUrl")
    inner class ResolveCoverUrl {

        @Test
        fun `passes through an already-absolute relative-slot url unchanged`() {
            val result = LibraryApi.resolveCoverUrl(origin, "https://cdn.example/already-absolute.jpg", null)
            assertThat(result).isEqualTo("https://cdn.example/already-absolute.jpg")
        }

        @Test
        fun `adds a leading slash if the relative path is missing one`() {
            val result = LibraryApi.resolveCoverUrl(origin, "assets/x/cover.png", null)
            assertThat(result).isEqualTo("$origin/assets/x/cover.png")
        }

        @Test
        fun `returns null when both inputs are null or blank`() {
            assertThat(LibraryApi.resolveCoverUrl(origin, null, null)).isNull()
            assertThat(LibraryApi.resolveCoverUrl(origin, "", "")).isNull()
        }
    }
}


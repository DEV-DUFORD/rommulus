package com.romm.androidtv.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LibrarySupportTest {

    // ---- isPlatformNativelySupported ----

    @Test
    fun `isPlatformNativelySupported returns true for SameBoy supported systems`() {
        assertThat(isPlatformNativelySupported("gb")).isTrue()
        assertThat(isPlatformNativelySupported("gbc")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for Genesis Plus GX supported systems`() {
        assertThat(isPlatformNativelySupported("genesis")).isTrue()
        assertThat(isPlatformNativelySupported("megadrive")).isTrue()
        assertThat(isPlatformNativelySupported("sms")).isTrue()
        assertThat(isPlatformNativelySupported("gamegear")).isTrue()
        assertThat(isPlatformNativelySupported("segacd")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for Snes9x supported systems`() {
        assertThat(isPlatformNativelySupported("snes")).isTrue()
        assertThat(isPlatformNativelySupported("sfc")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for fceumm supported systems`() {
        assertThat(isPlatformNativelySupported("nes")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for mgba supported systems`() {
        assertThat(isPlatformNativelySupported("gba")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for stella supported systems`() {
        assertThat(isPlatformNativelySupported("atari2600")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for beetle_pce_fast supported systems`() {
        assertThat(isPlatformNativelySupported("tg16")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for mednafen_ngp supported systems`() {
        assertThat(isPlatformNativelySupported("neo-geo-pocket")).isTrue()
        assertThat(isPlatformNativelySupported("neo-geo-pocket-color")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for mednafen_wswan supported systems`() {
        assertThat(isPlatformNativelySupported("wonderswan")).isTrue()
        assertThat(isPlatformNativelySupported("wonderswan-color")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for handy supported systems`() {
        assertThat(isPlatformNativelySupported("lynx")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns true for prosystem supported systems`() {
        assertThat(isPlatformNativelySupported("atari7800")).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns false for unsupported platforms`() {
        // PicoDrive and PPSSPP are still unapproved.
        assertThat(isPlatformNativelySupported("psp")).isFalse()
        assertThat(isPlatformNativelySupported("32x")).isFalse()
    }

    @Test
    fun `GameCube support is Linux-only`() {
        assertThat(isPlatformNativelySupported("ngc")).isFalse()
        assertThat(isPlatformNativelySupported("ngc", setOf("linux-x86_64"))).isTrue()
        assertThat(isPlatformNativelySupported("gc", setOf("linux-x86_64"))).isTrue()
    }

    @Test
    fun `isPlatformNativelySupported returns false for blank and empty slugs`() {
        assertThat(isPlatformNativelySupported("")).isFalse()
        assertThat(isPlatformNativelySupported("  ")).isFalse()
    }

    @Test
    fun `isPlatformNativelySupported returns false for unknown slug not in any manifest entry`() {
        assertThat(isPlatformNativelySupported("arcade")).isFalse()
        assertThat(isPlatformNativelySupported("nonexistent")).isFalse()
    }

    // ---- filterUnsupportedIfHidden ----

    @Test
    fun `filterUnsupportedIfHidden with hide false returns all items unchanged`() {
        val roms = listOf(
            LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "Game Boy", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 2, title = "Sonic", platformDisplayName = "Genesis", platformSlug = "genesis", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        assertThat(roms.filterUnsupportedIfHidden(hide = false))
            .containsExactlyElementsOf(roms)
    }

    @Test
    fun `filterUnsupportedIfHidden with hide false preserves reference identity`() {
        val roms = listOf(
            LibraryRom(id = 1, title = "Pokemon", platformDisplayName = "Game Boy", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        assertThat(roms.filterUnsupportedIfHidden(hide = false)).isSameAs(roms)
    }

    @Test
    fun `filterUnsupportedIfHidden with hide true keeps only supported platforms`() {
        val roms = listOf(
            LibraryRom(id = 1, title = "Pokemon Red", platformDisplayName = "Game Boy", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 2, title = "Sonic 2", platformDisplayName = "Genesis", platformSlug = "genesis", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 3, title = "Link's Awakening", platformDisplayName = "Game Boy Color", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 4, title = "God of War", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        val filtered = roms.filterUnsupportedIfHidden(hide = true)

        // Genesis Plus GX is now approved, so "genesis" is supported alongside "gb"/"gbc";
        // "psp" (PPSSPP, still unapproved) remains filtered out.
        assertThat(filtered).hasSize(3)
        assertThat(filtered.map { it.title }).containsExactly("Pokemon Red", "Sonic 2", "Link's Awakening")
    }

    @Test
    fun `filterUnsupportedIfHidden with hide true preserves original order`() {
        val roms = listOf(
            LibraryRom(id = 3, title = "Link's Awakening", platformDisplayName = "GBC", platformSlug = "gbc", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 1, title = "Pokemon Red", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 5, title = "God of War", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 2, title = "Yoshi Island", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        val filtered = roms.filterUnsupportedIfHidden(hide = true)

        assertThat(filtered.map { it.id }).containsExactly(3, 1, 2)
    }

    @Test
    fun `filterUnsupportedIfHidden with hide true returns empty list when no supported platforms`() {
        val roms = listOf(
            LibraryRom(id = 1, title = "God of War", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 2, title = "Motorcycle", platformDisplayName = "PSP", platformSlug = "psp", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        assertThat(roms.filterUnsupportedIfHidden(hide = true)).isEmpty()
    }

    @Test
    fun `filterUnsupportedIfHidden with hide true on empty list returns empty list`() {
        assertThat(emptyList<LibraryRom>().filterUnsupportedIfHidden(hide = true)).isEmpty()
    }

    @Test
    fun `filterUnsupportedIfHidden treats blank platformSlug as unsupported when hiding`() {
        val roms = listOf(
            LibraryRom(id = 1, title = "Unknown Game", platformDisplayName = "Mystery", platformSlug = "", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
            LibraryRom(id = 2, title = "Pokemon", platformDisplayName = "GB", platformSlug = "gb", coverUrl = null, lastPlayedIso = null, nowPlaying = false),
        )

        val filtered = roms.filterUnsupportedIfHidden(hide = true)

        assertThat(filtered).hasSize(1)
        assertThat(filtered[0].title).isEqualTo("Pokemon")
    }

    // ---- PlatformSummary.filterUnsupportedPlatformsIfHidden ----

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden with hide false returns all items unchanged`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb"),
            PlatformSummary(id = 2, displayName = "Genesis", romCount = 30, logoUrl = null, slug = "genesis"),
        )

        assertThat(platforms.filterUnsupportedPlatformsIfHidden(hide = false))
            .containsExactlyElementsOf(platforms)
    }

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden with hide false preserves reference identity`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb"),
        )

        assertThat(platforms.filterUnsupportedPlatformsIfHidden(hide = false)).isSameAs(platforms)
    }

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden with hide true keeps only supported platforms`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb"),
            PlatformSummary(id = 2, displayName = "Genesis", romCount = 30, logoUrl = null, slug = "genesis"),
            PlatformSummary(id = 3, displayName = "GB Color", romCount = 40, logoUrl = null, slug = "gbc"),
            PlatformSummary(id = 4, displayName = "PSP", romCount = 20, logoUrl = null, slug = "psp"),
        )

        val filtered = platforms.filterUnsupportedPlatformsIfHidden(hide = true)

        // Genesis Plus GX is now approved, so "genesis" is supported alongside "gb"/"gbc";
        // "psp" (PPSSPP, still unapproved) remains filtered out.
        assertThat(filtered).hasSize(3)
        assertThat(filtered.map { it.displayName }).containsExactly("Game Boy", "Genesis", "GB Color")
    }

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden treats blank slug as unsupported`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Unknown", romCount = 5, logoUrl = null, slug = ""),
            PlatformSummary(id = 2, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb"),
        )

        val filtered = platforms.filterUnsupportedPlatformsIfHidden(hide = true)

        assertThat(filtered).hasSize(1)
        assertThat(filtered[0].displayName).isEqualTo("Game Boy")
    }

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden preserves ordering`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Genesis", romCount = 30, logoUrl = null, slug = "genesis"),
            PlatformSummary(id = 2, displayName = "Game Boy", romCount = 50, logoUrl = null, slug = "gb"),
            PlatformSummary(id = 3, displayName = "GB Color", romCount = 40, logoUrl = null, slug = "gbc"),
        )

        val filtered = platforms.filterUnsupportedPlatformsIfHidden(hide = true)

        assertThat(filtered.map { it.slug }).containsExactly("genesis", "gb", "gbc")
    }

    @Test
    fun `PlatformSummary filterUnsupportedPlatformsIfHidden retains supported platform with zero games`() {
        val platforms = listOf(
            PlatformSummary(id = 1, displayName = "Game Boy", romCount = 0, logoUrl = null, slug = "gb"),
            PlatformSummary(id = 2, displayName = "PSP", romCount = 30, logoUrl = null, slug = "psp"),
        )

        val filtered = platforms.filterUnsupportedPlatformsIfHidden(hide = true)

        assertThat(filtered).hasSize(1)
        assertThat(filtered[0].slug).isEqualTo("gb")
        assertThat(filtered[0].romCount).isEqualTo(0)
    }
}

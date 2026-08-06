package com.romm.androidtv.library

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LicenseNoticesTest {

    @Test
    fun `parsePluginMetadata slices notices from the text blob`() {
        val text = "0123456789"
        val metadata = """
            0:5  First Lib
            5:5 Second
        """.trimIndent()

        val notices = LicenseNotices.parsePluginMetadata(metadata, text)

        assertThat(notices).containsExactly(
            LicenseNotice(name = "First Lib", text = "01234"),
            LicenseNotice(name = "Second", text = "56789"),
        )
    }

    @Test
    fun `parsePluginMetadata ignores malformed and out-of-range lines`() {
        val metadata = """
            nope
            0:5  Good
            99:5 Bounds
            3:0  Empty
        """.trimIndent()

        val notices = LicenseNotices.parsePluginMetadata(metadata, "0123456789")

        assertThat(notices).containsExactly(LicenseNotice(name = "Good", text = "01234"))
    }

    @Test
    fun `parseVendored splits marker-delimited sections`() {
        val input = """
            === Lib A ===

            Copyright A

            === Lib B ===

            Copyright B

            trailing line with no header
        """.trimIndent()

        val notices = LicenseNotices.parseVendored(input)

        assertThat(notices).containsExactly(
            LicenseNotice(name = "Lib A", text = "Copyright A"),
            LicenseNotice(name = "Lib B", text = "Copyright B\n\ntrailing line with no header"),
        )
    }

    @Test
    fun `parseVendored ignores leading content before the first header`() {
        val input = """
            preamble-only
            === Lib A ===

            License A
        """.trimIndent()

        assertThat(LicenseNotices.parseVendored(input))
            .containsExactly(LicenseNotice(name = "Lib A", text = "License A"))
    }
}
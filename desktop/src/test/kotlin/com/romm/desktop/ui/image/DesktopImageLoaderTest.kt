package com.romm.desktop.ui.image

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DesktopImageLoaderTest {

    @Test
    fun `inlines embedded class styles used by platform artwork`() {
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <style>
                .outline, .button { stroke: #000; stroke-width: 2px; }
                .button { fill: #c5443e; }
              </style>
              <rect class="button" width="10" height="10"/>
            </svg>
        """.trimIndent()

        val normalized = inlineSvgClassStyles(svg.encodeToByteArray()).decodeToString()

        assertThat(normalized).contains(
            """class="button" width="10" height="10" stroke="#000" stroke-width="2px" fill="#c5443e"/>""",
        )
    }

    @Test
    fun `class declarations replace redundant presentation attributes instead of duplicating them`() {
        // The ROMM logo carries a redundant fill="#000000" on every classed shape; the class
        // rule must win (SVG spec: class rules outrank presentation attributes) and the tag
        // must end up with exactly one fill attribute.
        val svg = """
            <svg xmlns="http://www.w3.org/2000/svg">
              <style>
                .cls-2 { fill: #e1a38e; }
              </style>
              <circle class="cls-2" cx="1" cy="1" r="2" fill="#000000"/>
            </svg>
        """.trimIndent()

        val normalized = inlineSvgClassStyles(svg.encodeToByteArray()).decodeToString()

        assertThat(normalized).contains(
            """class="cls-2" cx="1" cy="1" r="2" fill="#e1a38e"/>""",
        )
        assertThat(normalized).doesNotContain("""fill="#000000"""")
    }
}

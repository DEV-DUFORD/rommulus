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
            """class="button" stroke="#000" stroke-width="2px" fill="#c5443e"""",
        )
    }
}

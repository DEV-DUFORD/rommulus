package com.romm.shared.presentation

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class PresentationPlaceholderTest {
    @Test
    fun `module loads`() {
        assertTrue(PresentationPlaceholder.moduleName.isNotEmpty())
    }
}

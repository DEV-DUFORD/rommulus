package com.romm.shared.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class UiPlaceholderTest {
    @Test
    fun `module loads`() {
        assertTrue(UiPlaceholder.moduleName.isNotEmpty())
    }
}

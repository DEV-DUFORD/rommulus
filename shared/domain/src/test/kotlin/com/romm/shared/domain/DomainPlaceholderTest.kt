package com.romm.shared.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class DomainPlaceholderTest {
    @Test
    fun `module loads`() {
        assertTrue(DomainPlaceholder.moduleName.isNotEmpty())
    }
}

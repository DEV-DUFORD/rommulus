package com.romm.shared.network

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class NetworkPlaceholderTest {
    @Test
    fun `module loads`() {
        assertTrue(NetworkPlaceholder.moduleName.isNotEmpty())
    }
}

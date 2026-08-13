package com.romm.shared.storageapi

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class StorageApiPlaceholderTest {
    @Test
    fun `module loads`() {
        assertTrue(StorageApiPlaceholder.moduleName.isNotEmpty())
    }
}

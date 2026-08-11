package com.romm.androidtv.auth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class RommClientTokenScopesTest {

    @Test
    fun `native token can mutate collections`() {
        assertThat(RommClientTokenScopes.FOREGROUND_NATIVE)
            .contains("collections.read", "collections.write")
    }
}

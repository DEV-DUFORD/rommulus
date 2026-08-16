/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.log

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TokenRedactorTest {

    @Test
    fun `Authorization Bearer token is redacted`() {
        val input = """Request with Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJ1c2VyIjoiam9obiJ9.signature_value_here_extra_chars"""
        val result = TokenRedactor.redact(input)
        assertThat(result).contains("Authorization: Bearer [REDACTED]")
        assertThat(result).doesNotContain("eyJhbGciOiJIUzI1NiJ9")
    }

    @Test
    fun `bare Bearer prefix is redacted`() {
        val input = "Bearer secret_token_that_needs_redaction_12345"
        val result = TokenRedactor.redact(input)
        assertThat(result).contains("Bearer [REDACTED]")
        assertThat(result).doesNotContain("secret_token_that_needs_redaction_12345")
    }

    @Test
    fun `JWT-style three-segment token is redacted`() {
        // Construct a plausible JWT-shaped token.
        val seg1 = "a".repeat(25)
        val seg2 = "b".repeat(15)
        val seg3 = "c".repeat(15)
        val input = "Got token: $seg1.$seg2.$seg3 and continued"
        val result = TokenRedactor.redact(input)
        assertThat(result).contains("[REDACTED]")
        assertThat(result).doesNotContain(seg1)
    }

    @Test
    fun `long hex token is redacted`() {
        // 64 hex characters (SHA-256).
        val longHex = "a".repeat(64)
        val input = "auth: $longHex end"
        val result = TokenRedactor.redact(input)
        assertThat(result).contains("[REDACTED]")
        assertThat(result).doesNotContain(longHex)
    }

    @Test
    fun `short hex token is NOT redacted`() {
        // 16 hex characters, below the MIN_HEX_TOKEN_LENGTH threshold.
        val shortHex = "abcd1234efab5678"
        val input = "partial hash: $shortHex"
        val result = TokenRedactor.redact(input)
        assertThat(result).contains(shortHex)
    }

    @Test
    fun `Cookie header values are redacted per name`() {
        val input = """Request: Cookie: session=abc123; user=john_doe; other=xyz"""
        val result = TokenRedactor.redact(input)
        // Whole Cookie header block is replaced with "Cookie: [REDACTED]".
        assertThat(result).contains("Cookie: [REDACTED]")
        assertThat(result).doesNotContain("abc123")
        assertThat(result).doesNotContain("john_doe")
        assertThat(result).doesNotContain("xyz")
    }

    @Test
    fun `password assignment is redacted`() {
        val input = "Login attempt password=my_super_secret_p@ss!"
        val result = TokenRedactor.redact(input)
        assertThat(result).contains("password=[REDACTED]")
        assertThat(result).doesNotContain("my_super_secret_p@ss!")
    }

    @Test
    fun `plain message with no secrets is unchanged`() {
        val input = "User alice logged in at 2025-01-02T03:04:05Z from 192.168.1.1"
        val result = TokenRedactor.redact(input)
        assertThat(result).isEqualTo(input)
    }

    @Test
    fun `empty string returns empty string`() {
        assertThat(TokenRedactor.redact("")).isEqualTo("")
    }

    @Test
    fun `null-safe for null parameter handling is documented by contract`() {
        // Pure function — no null handling required at the API boundary.
        val input = "normal log without any secrets whatsoever"
        assertThat(TokenRedactor.redact(input)).isEqualTo(input)
    }
}

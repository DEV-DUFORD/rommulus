package com.romm.androidtv.web

import com.romm.androidtv.network.RommOrigin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NativeLaunchInterceptorTest {

    private val origin = RommOrigin.parse("https://romm.example.com")!!

    @Test
    fun `recognizes a well-formed rom ejs launch url`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/42/ejs", origin)

        assertThat(candidate).isEqualTo(NativeLaunchCandidate(romId = 42L))
    }

    @Test
    fun `rejects a cross-origin url`() {
        val candidate = NativeLaunchInterceptor.parse("https://evil.example.com/rom/42/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a different scheme`() {
        val candidate = NativeLaunchInterceptor.parse("http://romm.example.com/rom/42/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects extra path segments after ejs`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/42/ejs/extra", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a path that merely starts with the expected prefix`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/42/ejsx", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a non-numeric rom id`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/abc/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a negative-looking rom id`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/-1/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a zero rom id`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/0/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a leading-zero rom id`() {
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/042/ejs", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `rejects a malformed url`() {
        val candidate = NativeLaunchInterceptor.parse("not a url at all", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `tolerates a query string suffix by rejecting it`() {
        // Query strings are not part of this launch shape; be strict and reject rather
        // than silently ignore potentially meaningful query parameters.
        val candidate = NativeLaunchInterceptor.parse("https://romm.example.com/rom/42/ejs?x=1", origin)

        assertThat(candidate).isNull()
    }

    @Test
    fun `respects a non-root configured origin path`() {
        val subPathOrigin = RommOrigin.parse("https://romm.example.com/romm-app")!!

        val candidate = NativeLaunchInterceptor.parse(
            "https://romm.example.com/romm-app/rom/7/ejs",
            subPathOrigin
        )

        assertThat(candidate).isEqualTo(NativeLaunchCandidate(romId = 7L))
    }
}

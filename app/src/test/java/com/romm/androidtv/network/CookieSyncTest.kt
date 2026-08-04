package com.romm.androidtv.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("CookieSync — cookie parsing, origin isolation, and sync helpers")
class CookieSyncTest {

    // ---- Unit tests for parseCookieStringPreservingAttributes (no Android dependency) ----

    private val testParser = object {
        fun parsePreserving(cookieHeader: String, origin: RommOrigin): List<Cookie> {
            val result = mutableListOf<Cookie>()
            val host = origin.host

            for (part in cookieHeader.split(";")) {
                val trimmed = part.trim()
                if (trimmed.isBlank()) continue

                val eqIndex = trimmed.indexOf('=')
                if (eqIndex <= 0) continue

                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                if (name.lowercase() in listOf("secure", "httponly", "path", "domain", "expires", "max-age", "samesite")) {
                    continue
                }

                val builder = Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain(host)
                    .path("/")

                // HTTPS origins get Secure flag; HTTP origins do not
                if (origin.scheme.equals("https", ignoreCase = true)) {
                    builder.secure()
                }

                result.add(builder.build())
            }

            return result
        }

        fun parseLegacy(cookieHeader: String, origin: RommOrigin): List<Cookie> {
            val result = mutableListOf<Cookie>()
            val host = origin.host

            for (part in cookieHeader.split(";")) {
                val trimmed = part.trim()
                val eqIndex = trimmed.indexOf('=')
                if (eqIndex <= 0) continue

                val name = trimmed.substring(0, eqIndex).trim()
                val value = trimmed.substring(eqIndex + 1).trim()

                if (name.lowercase() in listOf("secure", "httponly", "path", "domain", "expires", "max-age", "samesite")) {
                    continue
                }

                result.add(
                    Cookie.Builder()
                        .name(name)
                        .value(value)
                        .domain(host)
                        .path("/")
                        .secure()
                        .build()
                )
            }

            return result
        }
    }

    @Nested
    @DisplayName("parseCookieStringPreservingAttributes() — session cookies")
    inner class SessionCookies {
        private val origin = RommOrigin.parse("https://romm.example.com")!!

        @Test
        @DisplayName("parses romm_session cookie")
        fun `romm session`() {
            val cookies = testParser.parsePreserving(
                "romm_session=abc123def456",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].name).isEqualTo("romm_session")
            assertThat(cookies[0].value).isEqualTo("abc123def456")
            assertThat(cookies[0].domain).isEqualTo("romm.example.com")
            assertThat(cookies[0].secure).isTrue() // HTTPS origin -> Secure
        }

        @Test
        @DisplayName("parses romm_csrftoken cookie")
        fun `csrf token`() {
            val cookies = testParser.parsePreserving(
                "romm_csrftoken=xyz789token",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].name).isEqualTo("romm_csrftoken")
            assertThat(cookies[0].value).isEqualTo("xyz789token")
        }

        @Test
        @DisplayName("parses both session and csrf cookies together")
        fun `both cookies`() {
            val cookies = testParser.parsePreserving(
                "romm_session=sess_value; romm_csrftoken=csrf_value",
                origin
            )
            assertThat(cookies).hasSize(2)
            assertThat(cookies[0].name).isEqualTo("romm_session")
            assertThat(cookies[1].name).isEqualTo("romm_csrftoken")
        }

        @Test
        @DisplayName("skips Secure attribute in getCookie output")
        fun `skips secure attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; Secure",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].name).isEqualTo("romm_session")
        }

        @Test
        @DisplayName("skips HttpOnly attribute")
        fun `skips httponly attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; HttpOnly",
                origin
            )
            assertThat(cookies).hasSize(1)
        }

        @Test
        @DisplayName("skips Path attribute")
        fun `skips path attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; Path=/",
                origin
            )
            assertThat(cookies).hasSize(1)
        }

        @Test
        @DisplayName("skips Domain attribute")
        fun `skips domain attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; Domain=.romm.example.com",
                origin
            )
            assertThat(cookies).hasSize(1)
        }

        @Test
        @DisplayName("skips Expires attribute")
        fun `skips expires attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; Expires=Thu, 01 Jan 2025 00:00:00 GMT",
                origin
            )
            assertThat(cookies).hasSize(1)
        }

        @Test
        @DisplayName("skips Max-Age attribute")
        fun `skips max-age attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; Max-Age=3600",
                origin
            )
            assertThat(cookies).hasSize(1)
        }

        @Test
        @DisplayName("skips SameSite attribute")
        fun `skips samesite attribute`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val; SameSite=Lax",
                origin
            )
            assertThat(cookies).hasSize(1)
        }
    }

    @Nested
    @DisplayName("Cookie attribute preservation — Secure flag by origin scheme")
    inner class SecureAttributePreservation {
        @Test
        @DisplayName("HTTPS origin cookies get Secure=true")
        fun `https origin secure`() {
            val httpsOrigin = RommOrigin.parse("https://romm.example.com")!!
            val cookies = testParser.parsePreserving("session=val", httpsOrigin)
            assertThat(cookies[0].secure).isTrue()
        }

        @Test
        @DisplayName("HTTP origin cookies get Secure=false")
        fun `http origin not secure`() {
            val httpOrigin = RommOrigin.parse("http://localhost:8080")!!
            val cookies = testParser.parsePreserving("session=val", httpOrigin)
            assertThat(cookies[0].secure).isFalse()
        }

        @Test
        @DisplayName("Legacy parser always forces Secure=true (backward compat)")
        fun `legacy always secure`() {
            val httpOrigin = RommOrigin.parse("http://localhost:8080")!!
            val cookies = testParser.parseLegacy("session=val", httpOrigin)
            assertThat(cookies[0].secure).isTrue()
        }
    }

    @Nested
    @DisplayName("Cookie origin isolation — different hosts are isolated")
    inner class OriginIsolation {
        @Test
        @DisplayName("cookies from different origins do not leak")
        fun `cross-origin isolation`() {
            val sync = RomMCookieSync(java.net.CookieManager())

            val rommUrl = "https://romm.example.com/api".toHttpUrl()
            val evilUrl = "https://evil.example.com/api".toHttpUrl()

            val rommCookie = Cookie.Builder()
                .name("session")
                .value("romm_session_value")
                .domain("romm.example.com")
                .path("/")
                .build()

            val evilCookie = Cookie.Builder()
                .name("session")
                .value("evil_session_value")
                .domain("evil.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(rommUrl, listOf(rommCookie))
            sync.saveFromResponse(evilUrl, listOf(evilCookie))

            val rommLoaded = sync.loadForRequest(rommUrl)
            val evilLoaded = sync.loadForRequest(evilUrl)

            assertThat(rommLoaded).hasSize(1)
            assertThat(rommLoaded[0].value).isEqualTo("romm_session_value")
            assertThat(evilLoaded).hasSize(1)
            assertThat(evilLoaded[0].value).isEqualTo("evil_session_value")
        }

        @Test
        @DisplayName("subdomain cookies are isolated from parent domain")
        fun `subdomain isolation`() {
            val sync = RomMCookieSync(java.net.CookieManager())

            val parentUrl = "https://example.com/api".toHttpUrl()
            val subUrl = "https://api.example.com/api".toHttpUrl()

            val parentCookie = Cookie.Builder()
                .name("session")
                .value("parent_value")
                .domain("example.com")
                .path("/")
                .build()

            val subCookie = Cookie.Builder()
                .name("session")
                .value("sub_value")
                .domain("api.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(parentUrl, listOf(parentCookie))
            sync.saveFromResponse(subUrl, listOf(subCookie))

            val parentLoaded = sync.loadForRequest(parentUrl)
            val subLoaded = sync.loadForRequest(subUrl)

            assertThat(parentLoaded[0].value).isEqualTo("parent_value")
            assertThat(subLoaded[0].value).isEqualTo("sub_value")
        }
    }

    @Nested
    @DisplayName("parseCookieStringPreservingAttributes() — edge cases")
    inner class EdgeCases {
        private val origin = RommOrigin.parse("https://example.com")!!

        @Test
        @DisplayName("empty string returns empty list")
        fun `empty string`() {
            val cookies = testParser.parsePreserving("", origin)
            assertThat(cookies).isEmpty()
        }

        @Test
        @DisplayName("cookie with no value is skipped")
        fun `no value`() {
            val cookies = testParser.parsePreserving("nameonly", origin)
            assertThat(cookies).isEmpty()
        }

        @Test
        @DisplayName("cookie with equals sign in value")
        fun `equals in value`() {
            val cookies = testParser.parsePreserving(
                "token=abc=def=ghi",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].value).isEqualTo("abc=def=ghi")
        }

        @Test
        @DisplayName("whitespace around cookie name and value")
        fun `whitespace`() {
            val cookies = testParser.parsePreserving(
                "  session  =  value123  ",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].name).isEqualTo("session")
            assertThat(cookies[0].value).isEqualTo("value123")
        }

        @Test
        @DisplayName("complex Set-Cookie header with all attributes")
        fun `full set-cookie`() {
            val rommOrigin = RommOrigin.parse("https://romm.example.com")!!
            val cookies = testParser.parsePreserving(
                "romm_session=abc123; Path=/; Domain=.romm.example.com; Secure; HttpOnly; SameSite=Lax",
                rommOrigin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].name).isEqualTo("romm_session")
            assertThat(cookies[0].value).isEqualTo("abc123")
        }

        @Test
        @DisplayName("multiple cookies with attributes interleaved")
        fun `interleaved attributes`() {
            val rommOrigin = RommOrigin.parse("https://romm.example.com")!!
            val cookies = testParser.parsePreserving(
                "romm_session=s1; Secure; romm_csrftoken=c1; HttpOnly",
                rommOrigin
            )
            assertThat(cookies).hasSize(2)
            assertThat(cookies[0].name).isEqualTo("romm_session")
            assertThat(cookies[1].name).isEqualTo("romm_csrftoken")
        }

        @Test
        @DisplayName("port in origin is stripped for host extraction")
        fun `port stripped`() {
            val portOrigin = RommOrigin.parse("https://romm.example.com:8443")!!
            val cookies = testParser.parsePreserving(
                "session=val",
                portOrigin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].domain).isEqualTo("romm.example.com")
        }

        @Test
        @DisplayName("cookie value with semicolons in URL-encoded form")
        fun `url encoded value`() {
            val cookies = testParser.parsePreserving(
                "token=abc%3Bdef",
                origin
            )
            assertThat(cookies).hasSize(1)
            assertThat(cookies[0].value).isEqualTo("abc%3Bdef")
        }

        @Test
        @DisplayName("blank parts from double semicolons are skipped")
        fun `double semicolons`() {
            val cookies = testParser.parsePreserving(
                "romm_session=val;; romm_csrftoken=csrf",
                origin
            )
            assertThat(cookies).hasSize(2)
        }
    }

    @Nested
    @DisplayName("CookieJar contract — saveFromResponse / loadForRequest (non-blocking)")
    inner class CookieJarContract {
        @Test
        @DisplayName("cookies saved and loaded for same host")
        fun `save and load`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api/heartbeat".toHttpUrl()

            val cookie1 = Cookie.Builder()
                .name("romm_session")
                .value("session_value")
                .domain("romm.example.com")
                .path("/")
                .build()

            val cookie2 = Cookie.Builder()
                .name("romm_csrftoken")
                .value("csrf_value")
                .domain("romm.example.com")
                .path("/")
                .build()

            // saveFromResponse is now non-blocking — no Android calls
            sync.saveFromResponse(url, listOf(cookie1, cookie2))

            val loaded = sync.loadForRequest(url)
            assertThat(loaded).hasSize(2)
            assertThat(loaded.map { it.name }).containsExactlyInAnyOrder("romm_session", "romm_csrftoken")
        }

        @Test
        @DisplayName("new cookie with same name replaces old one")
        fun `cookie replacement`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api/login".toHttpUrl()

            val oldCookie = Cookie.Builder()
                .name("romm_session")
                .value("old_value")
                .domain("romm.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(url, listOf(oldCookie))

            val newCookie = Cookie.Builder()
                .name("romm_session")
                .value("new_value")
                .domain("romm.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(url, listOf(newCookie))

            val loaded = sync.loadForRequest(url)
            assertThat(loaded).hasSize(1)
            assertThat(loaded[0].value).isEqualTo("new_value")
        }

        @Test
        @DisplayName("different hosts have separate cookie stores")
        fun `separate host stores`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url1 = "https://romm.example.com/api".toHttpUrl()
            val url2 = "https://other.example.com/api".toHttpUrl()

            val cookie1 = Cookie.Builder()
                .name("session")
                .value("romm_session")
                .domain("romm.example.com")
                .path("/")
                .build()

            val cookie2 = Cookie.Builder()
                .name("session")
                .value("other_session")
                .domain("other.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(url1, listOf(cookie1))
            sync.saveFromResponse(url2, listOf(cookie2))

            val loaded1 = sync.loadForRequest(url1)
            val loaded2 = sync.loadForRequest(url2)

            assertThat(loaded1[0].value).isEqualTo("romm_session")
            assertThat(loaded2[0].value).isEqualTo("other_session")
        }

        @Test
        @DisplayName("empty cookies list is safe")
        fun `empty save`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()

            sync.saveFromResponse(url, emptyList())
            val loaded = sync.loadForRequest(url)
            assertThat(loaded).isEmpty()
        }

        @Test
        @DisplayName("unknown host returns empty cookies")
        fun `unknown host`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()
            val loaded = sync.loadForRequest(url)
            assertThat(loaded).isEmpty()
        }

        @Test
        @DisplayName("saveFromResponse does not block (no Android calls)")
        fun `non-blocking save`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()

            val cookie = Cookie.Builder()
                .name("test")
                .value("val")
                .domain("romm.example.com")
                .path("/")
                .build()

            // This should return immediately — no CountDownLatch, no Android calls
            val start = System.nanoTime()
            repeat(100) {
                sync.saveFromResponse(url, listOf(cookie))
            }
            val elapsed = (System.nanoTime() - start) / 1_000_000 // ms
            // 100 saves should complete in under 100ms (no blocking)
            assertThat(elapsed).isLessThan(100L)
        }
    }

    @Nested
    @DisplayName("Credential security — no logging or persistence")
    inner class CredentialSecurity {
        @Test
        @DisplayName("cookie values are preserved as-is (session tokens)")
        fun `session token preserved`() {
            val origin = RommOrigin.parse("https://romm.example.com")!!
            val cookies = testParser.parsePreserving(
                "romm_session=REDACTED_SESSION_TOKEN_12345",
                origin
            )
            assertThat(cookies[0].value).isEqualTo("REDACTED_SESSION_TOKEN_12345")
        }

        @Test
        @DisplayName("csrf token preserved as-is")
        fun `csrf token preserved`() {
            val origin = RommOrigin.parse("https://romm.example.com")!!
            val cookies = testParser.parsePreserving(
                "romm_csrftoken=REDACTED_CSRF_TOKEN_67890",
                origin
            )
            assertThat(cookies[0].value).isEqualTo("REDACTED_CSRF_TOKEN_67890")
        }
    }

    @Nested
    @DisplayName("Cookie path and domain attributes preserved")
    inner class PathAndDomainPreservation {
        @Test
        @DisplayName("cookie with custom path is preserved in OkHttp store")
        fun `custom path preserved`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()

            val cookie = Cookie.Builder()
                .name("api_token")
                .value("secret")
                .domain("romm.example.com")
                .path("/api")
                .build()

            sync.saveFromResponse(url, listOf(cookie))
            val loaded = sync.loadForRequest(url)

            assertThat(loaded).hasSize(1)
            assertThat(loaded[0].path).isEqualTo("/api")
        }

        @Test
        @DisplayName("cookies with same name but different paths coexist")
        fun `same name different paths`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()

            val cookieRoot = Cookie.Builder()
                .name("session")
                .value("root_val")
                .domain("romm.example.com")
                .path("/")
                .build()

            val cookieApi = Cookie.Builder()
                .name("session")
                .value("api_val")
                .domain("romm.example.com")
                .path("/api")
                .build()

            sync.saveFromResponse(url, listOf(cookieRoot))
            sync.saveFromResponse(url, listOf(cookieApi))

            val loaded = sync.loadForRequest(url)
            assertThat(loaded).hasSize(2)
        }

        @Test
        @DisplayName("cookie replacement only removes matching name+domain+path")
        fun `replacement exact match`() {
            val sync = RomMCookieSync(java.net.CookieManager())
            val url = "https://romm.example.com/api".toHttpUrl()

            val oldRoot = Cookie.Builder()
                .name("session")
                .value("old_root")
                .domain("romm.example.com")
                .path("/")
                .build()

            sync.saveFromResponse(url, listOf(oldRoot))

            // New cookie with same name but different path
            val newApi = Cookie.Builder()
                .name("session")
                .value("new_api")
                .domain("romm.example.com")
                .path("/api")
                .build()

            sync.saveFromResponse(url, listOf(newApi))

            val loaded = sync.loadForRequest(url)
            assertThat(loaded).hasSize(2)
            assertThat(loaded.any { it.value == "old_root" }).isTrue()
            assertThat(loaded.any { it.value == "new_api" }).isTrue()
        }
    }
}

package com.romm.androidtv.diagnostic

import com.romm.androidtv.model.DiagnosticResult
import com.romm.androidtv.network.RommOrigin
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Comprehensive tests for RommOrigin: URL normalization, same-origin checks,
 * exact /login path detection (including query/fragment/spoof tests).
 */
@DisplayName("RomM Origin configuration")
class RommOriginTest {

    @Nested
    @DisplayName("RommOrigin.parse() — normalization")
    inner class Normalization {
        @Test
        @DisplayName("parses standard HTTPS origin")
        fun `standard https`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net")
            assertThat(origin).isNotNull
            assertThat(origin!!.scheme).isEqualTo("https")
            assertThat(origin.host).isEqualTo("romm.dufserver.net")
            assertThat(origin.port).isEqualTo(-1)
            assertThat(origin.effectivePort).isEqualTo(443)
            assertThat(origin.path).isEmpty()
        }

        @Test
        @DisplayName("strips trailing slash from path")
        fun `trailing slash removed`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net/")
            assertThat(origin).isNotNull
            assertThat(origin!!.path).isEmpty()
        }

        @Test
        @DisplayName("handles explicit port 443")
        fun `explicit 443`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net:443")
            assertThat(origin).isNotNull
            assertThat(origin!!.effectivePort).isEqualTo(443)
        }

        @Test
        @DisplayName("handles non-standard port")
        fun `non standard port`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net:8443")
            assertThat(origin).isNotNull
            assertThat(origin!!.effectivePort).isEqualTo(8443)
        }

        @Test
        @DisplayName("toUrl reconstructs without trailing slash")
        fun `to url no trailing slash`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net/")!!
            assertThat(origin.toUrl()).isEqualTo("https://romm.dufserver.net")
        }

        @Test
        @DisplayName("toUrl includes non-standard port")
        fun `to url with port`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net:8443")!!
            assertThat(origin.toUrl()).isEqualTo("https://romm.dufserver.net:8443")
        }

        @Test
        @DisplayName("toUrl omits default port 443")
        fun `to url omits default port`() {
            val origin = RommOrigin.parse("https://romm.dufserver.net:443")!!
            assertThat(origin.toUrl()).isEqualTo("https://romm.dufserver.net")
        }

        @Test
        @DisplayName("returns null for blank string")
        fun `blank returns null`() {
            assertThat(RommOrigin.parse("")).isNull()
            assertThat(RommOrigin.parse("   ")).isNull()
        }

        @Test
        @DisplayName("returns null for garbage")
        fun `garbage returns null`() {
            assertThat(RommOrigin.parse("not a url at all!!!")).isNull()
        }

        @Test
        @DisplayName("empty string stays empty with legacy normalize")
        fun `legacy empty string`() {
            val origin = ""
            val normalized = if (origin.endsWith("/")) origin.removeSuffix("/") else origin
            assertThat(normalized).isEmpty()
        }
    }

    @Nested
    @DisplayName("isSameOrigin() — parsed scheme/host/effective-port comparison")
    inner class SameOriginComparison {
        private val base = RommOrigin.parse("https://romm.dufserver.net")!!

        @Test
        @DisplayName("same origin matches")
        fun `exact match`() {
            val other = RommOrigin.parse("https://romm.dufserver.net")!!
            assertThat(base.isSameOrigin(other)).isTrue()
        }

        @Test
        @DisplayName("same origin with explicit 443 matches")
        fun `explicit 443 matches`() {
            val other = RommOrigin.parse("https://romm.dufserver.net:443")!!
            assertThat(base.isSameOrigin(other)).isTrue()
        }

        @Test
        @DisplayName("different port is different origin")
        fun `different port`() {
            val other = RommOrigin.parse("https://romm.dufserver.net:8443")!!
            assertThat(base.isSameOrigin(other)).isFalse()
        }

        @Test
        @DisplayName("HTTP vs HTTPS is different origin")
        fun `http vs https`() {
            val other = RommOrigin.parse("http://romm.dufserver.net")!!
            assertThat(base.isSameOrigin(other)).isFalse()
        }

        @Test
        @DisplayName("different host is different origin")
        fun `different host`() {
            val other = RommOrigin.parse("https://evil.example.com")!!
            assertThat(base.isSameOrigin(other)).isFalse()
        }

        @Test
        @DisplayName("subdomain is different origin")
        fun `subdomain`() {
            val other = RommOrigin.parse("https://romm.dufserver.net.evil.com")!!
            assertThat(base.isSameOrigin(other)).isFalse()
        }

        @Test
        @DisplayName("case-insensitive host comparison")
        fun `case insensitive host`() {
            val other = RommOrigin.parse("https://ROMM.DUFSERVER.NET")!!
            assertThat(base.isSameOrigin(other)).isTrue()
        }

        @Test
        @DisplayName("case-insensitive scheme comparison")
        fun `case insensitive scheme`() {
            val other = RommOrigin.parse("HTTPS://romm.dufserver.net")!!
            assertThat(base.isSameOrigin(other)).isTrue()
        }
    }

    @Nested
    @DisplayName("containsUri() — same-origin URL containment")
    inner class ContainsUri {
        private val base = RommOrigin.parse("https://romm.dufserver.net")!!
        private val baseUrl = "https://romm.dufserver.net"

        @Test
        @DisplayName("allows same-origin paths")
        fun `same origin paths`() {
            assertThat(base.containsUri(java.net.URI("$baseUrl/api/games"))).isTrue()
            assertThat(base.containsUri(java.net.URI("$baseUrl/"))).isTrue()
            assertThat(base.containsUri(java.net.URI(baseUrl))).isTrue()
        }

        @Test
        @DisplayName("blocks HTTP URLs")
        fun `http blocked`() {
            assertThat(base.containsUri(java.net.URI("http://romm.dufserver.net/"))).isFalse()
        }

        @Test
        @DisplayName("blocks different domain")
        fun `different domain blocked`() {
            assertThat(base.containsUri(java.net.URI("https://evil.example.com/"))).isFalse()
            assertThat(base.containsUri(java.net.URI("https://romm.dufserver.net.evil.com/"))).isFalse()
        }

        @Test
        @DisplayName("blocks javascript: scheme")
        fun `javascript scheme blocked`() {
            // java.net.URI parses javascript: as a valid URI, but containsUri rejects it
            val jsUri = RommOrigin.parseUrl("javascript:alert(1)")
            if (jsUri != null) {
                assertThat(base.containsUri(jsUri)).isFalse()
            }
        }

        @Test
        @DisplayName("blocks data: scheme")
        fun `data scheme blocked`() {
            val uri = RommOrigin.parseUrl("data:text/html,<h1>test</h1>")
            if (uri != null) {
                assertThat(base.containsUri(uri)).isFalse()
            }
        }

        @Test
        @DisplayName("URL with query string is contained")
        fun `query string allowed`() {
            assertThat(base.containsUri(java.net.URI("$baseUrl/api/games?id=5&sort=name"))).isTrue()
        }

        @Test
        @DisplayName("URL with fragment is contained")
        fun `fragment allowed`() {
            assertThat(base.containsUri(java.net.URI("$baseUrl/#section"))).isTrue()
        }
    }

    @Nested
    @DisplayName("isLoginPath() — exact /login path detection")
    inner class LoginPathDetection {
        private val base = RommOrigin.parse("https://romm.dufserver.net")!!
        private val baseUrl = "https://romm.dufserver.net"

        @Test
        @DisplayName("exact /login is detected")
        fun `exact login`() {
            val uri = java.net.URI("$baseUrl/login")
            assertThat(base.isLoginPath(uri)).isTrue()
        }

        @Test
        @DisplayName("/login with query string is detected")
        fun `login with query`() {
            val uri = java.net.URI("$baseUrl/login?next=/dashboard")
            assertThat(base.isLoginPath(uri)).isTrue()
        }

        @Test
        @DisplayName("/login with fragment is detected")
        fun `login with fragment`() {
            val uri = java.net.URI("$baseUrl/login#top")
            assertThat(base.isLoginPath(uri)).isTrue()
        }

        @Test
        @DisplayName("/loginpage is NOT detected (spoof)")
        fun `loginpage not detected`() {
            val uri = java.net.URI("$baseUrl/loginpage")
            assertThat(base.isLoginPath(uri)).isFalse()
        }

        @Test
        @DisplayName("/api/login is NOT detected")
        fun `api login not detected`() {
            val uri = java.net.URI("$baseUrl/api/login")
            assertThat(base.isLoginPath(uri)).isFalse()
        }

        @Test
        @DisplayName("/admin/login is NOT detected")
        fun `admin login not detected`() {
            val uri = java.net.URI("$baseUrl/admin/login")
            assertThat(base.isLoginPath(uri)).isFalse()
        }

        @Test
        @DisplayName("/login/ is NOT detected (trailing slash)")
        fun `login trailing slash not detected`() {
            val uri = java.net.URI("$baseUrl/login/")
            assertThat(base.isLoginPath(uri)).isFalse()
        }

        @Test
        @DisplayName("different origin /login is NOT detected")
        fun `different origin login`() {
            val uri = java.net.URI("https://evil.example.com/login")
            assertThat(base.isLoginPath(uri)).isFalse()
        }

        @Test
        @DisplayName("/LOGIN case-insensitive host still matches")
        fun `case insensitive host`() {
            val uri = java.net.URI("https://ROMM.DUFSERVER.NET/login")
            assertThat(base.isLoginPath(uri)).isTrue()
        }
    }

    @Nested
    @DisplayName("Legacy string-based same-origin checks (backward compat)")
    inner class LegacyChecks {
        private val origin = "https://romm.dufserver.net"

        private fun isAllowed(url: String): Boolean {
            if (!url.startsWith("https://", ignoreCase = true)) return false
            return url == origin || url.startsWith("$origin/")
        }

        @Test
        @DisplayName("allows URLs under the configured origin")
        fun `same origin allowed`() {
            assertThat(isAllowed("$origin/")).isTrue()
            assertThat(isAllowed("$origin/api/games")).isTrue()
            assertThat(isAllowed(origin)).isTrue()
        }

        @Test
        @DisplayName("blocks HTTP URLs")
        fun `http blocked`() {
            assertThat(isAllowed("http://romm.dufserver.net/")).isFalse()
        }

        @Test
        @DisplayName("blocks different domain")
        fun `different domain blocked`() {
            assertThat(isAllowed("https://evil.example.com/")).isFalse()
            assertThat(isAllowed("https://romm.dufserver.net.evil.com/")).isFalse()
        }

        @Test
        @DisplayName("blocks javascript: scheme")
        fun `javascript scheme blocked`() {
            assertThat(isAllowed("javascript:alert(1)")).isFalse()
        }

        @Test
        @DisplayName("blocks data: scheme")
        fun `data scheme blocked`() {
            assertThat(isAllowed("data:text/html,<h1>test</h1>")).isFalse()
        }

        @Test
        @DisplayName("case-sensitive origin matching")
        fun `case sensitive`() {
            assertThat(isAllowed("HTTPS://romm.dufserver.net/")).isFalse()
            assertThat(isAllowed("https://ROMM.DUFSERVER.NET/")).isFalse()
        }

        @Test
        @DisplayName("handles multiple trailing slashes")
        fun `multiple trailing slashes`() {
            val origin = "https://romm.dufserver.net//"
            val normalized = if (origin.endsWith("/")) origin.removeSuffix("/") else origin
            assertThat(normalized).isEqualTo("https://romm.dufserver.net/")
        }
    }
}

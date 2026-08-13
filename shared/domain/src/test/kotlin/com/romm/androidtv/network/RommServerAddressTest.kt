package com.romm.androidtv.network

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("RommServerAddress canonicalizer")
class RommServerAddressTest {

    private fun valid(input: String): ServerAddressResult.Valid =
        RommServerAddress.parseAndNormalize(input) as ServerAddressResult.Valid

    private fun invalid(input: String): InvalidReason =
        (RommServerAddress.parseAndNormalize(input) as ServerAddressResult.Invalid).reason

    @Nested
    @DisplayName("Host forms")
    inner class HostForms {
        @Test
        fun `DNS name`() {
            assertThat(valid("https://romm.example.com")).isEqualTo(
                ServerAddressResult.Valid(
                    origin = "https://romm.example.com",
                    scheme = "https",
                    host = "romm.example.com",
                    port = null,
                    basePath = "",
                ),
            )
        }

        @Test
        fun `IPv4 literal`() {
            assertThat(valid("http://192.168.1.10").origin).isEqualTo("http://192.168.1.10")
            assertThat(valid("http://192.168.1.10").host).isEqualTo("192.168.1.10")
        }

        @Test
        fun `bracketed IPv6 literal`() {
            val result = valid("http://[::1]:8080")
            assertThat(result.host).isEqualTo("::1")
            assertThat(result.port).isEqualTo(8080)
            assertThat(result.origin).isEqualTo("http://[::1]:8080")
        }

        @Test
        fun `host case is normalized`() {
            val result = valid("HTTPS://ROMM.EXAMPLE.COM")
            assertThat(result.scheme).isEqualTo("https")
            assertThat(result.host).isEqualTo("romm.example.com")
            assertThat(result.origin).isEqualTo("https://romm.example.com")
        }
    }

    @Nested
    @DisplayName("Scheme inference (bare input, no explicit http/https)")
    inner class SchemeInference {
        @Test
        fun `bare domain infers https — google_com`() {
            val result = valid("google.com")
            assertThat(result.origin).isEqualTo("https://google.com")
            assertThat(result.scheme).isEqualTo("https")
            assertThat(result.host).isEqualTo("google.com")
        }

        @Test
        fun `bare subdomain infers https — romm_example_com`() {
            val result = valid("romm.example.com")
            assertThat(result.origin).isEqualTo("https://romm.example.com")
            assertThat(result.scheme).isEqualTo("https")
            assertThat(result.host).isEqualTo("romm.example.com")
        }

        @Test
        fun `bare domain with path infers https`() {
            val result = valid("romm.example.com/romm")
            assertThat(result.origin).isEqualTo("https://romm.example.com/romm")
            assertThat(result.scheme).isEqualTo("https")
            assertThat(result.basePath).isEqualTo("/romm")
        }

        @Test
        fun `private IPv4 with port infers http — 192_168_8_165_8000`() {
            val result = valid("192.168.8.165:8000")
            assertThat(result.origin).isEqualTo("http://192.168.8.165:8000")
            assertThat(result.scheme).isEqualTo("http")
            assertThat(result.host).isEqualTo("192.168.8.165")
            assertThat(result.port).isEqualTo(8000)
        }

        @Test
        fun `private IPv4 without port infers http — 192_168_8_165`() {
            val result = valid("192.168.8.165")
            assertThat(result.origin).isEqualTo("http://192.168.8.165")
            assertThat(result.scheme).isEqualTo("http")
            assertThat(result.host).isEqualTo("192.168.8.165")
            assertThat(result.port).isNull()
        }

        @Test
        fun `public IPv4 with no scheme inferred as http then rejected as INSECURE_PUBLIC_HTTP — 8_8_8_8`() {
            assertThat(invalid("8.8.8.8")).isEqualTo(InvalidReason.INSECURE_PUBLIC_HTTP)
        }

        @Test
        fun `localhost treated as DNS hostname infers https — localhost_8080`() {
            // localhost is a DNS name (not an IP literal), so it gets https.
            val result = valid("localhost:8080")
            assertThat(result.origin).isEqualTo("https://localhost:8080")
            assertThat(result.scheme).isEqualTo("https")
            assertThat(result.host).isEqualTo("localhost")
            assertThat(result.port).isEqualTo(8080)
        }

        @Test
        fun `bracketed IPv6 with port infers http — bracketed_ipv6_8443`() {
            val result = valid("[::1]:8443")
            assertThat(result.origin).isEqualTo("http://[::1]:8443")
            assertThat(result.scheme).isEqualTo("http")
            assertThat(result.host).isEqualTo("::1")
            assertThat(result.port).isEqualTo(8443)
        }

        @Test
        fun `explicit http scheme is never overridden`() {
            val result = valid("http://localhost")
            assertThat(result.origin).isEqualTo("http://localhost")
            assertThat(result.scheme).isEqualTo("http")
        }

        @Test
        fun `explicit https scheme is never overridden`() {
            val result = valid("https://romm.example.com")
            assertThat(result.origin).isEqualTo("https://romm.example.com")
            assertThat(result.scheme).isEqualTo("https")
        }

        @Test
        fun `explicit http scheme on public host still rejected as insecure`() {
            assertThat(invalid("http://public.example.com"))
                .isEqualTo(InvalidReason.INSECURE_PUBLIC_HTTP)
        }

        @Test
        fun `ambiguous unbracketed IPv6 treated as IP literal infers http — bare ipv6 rejected by parser`() {
            // Bare unbracketed IPv6 is ambiguous with host:port syntax;
            // we infer http:// but OkHttp requires brackets, so it fails parsing.
            assertThat(invalid("::1")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `bare IPv4 with path infers http`() {
            val result = valid("10.0.0.5/api")
            assertThat(result.origin).isEqualTo("http://10.0.0.5/api")
            assertThat(result.scheme).isEqualTo("http")
            assertThat(result.basePath).isEqualTo("/api")
        }

        @Test
        fun `bare host with query still rejected after inference`() {
            // Scheme is inferred, but query params are still rejected.
            assertThat(invalid("romm.example.com?x=1"))
                .isEqualTo(InvalidReason.QUERY_OR_FRAGMENT)
        }

        @Test
        fun `bare host with fragment still rejected after inference`() {
            // Scheme is inferred, but fragments are still rejected.
            assertThat(invalid("romm.example.com#top"))
                .isEqualTo(InvalidReason.QUERY_OR_FRAGMENT)
        }
    }

    @Nested
    @DisplayName("Ports and base paths")
    inner class PortsAndPaths {
        @Test
        fun `explicit non-default port is preserved`() {
            assertThat(valid("https://romm.example.com:8443").origin)
                .isEqualTo("https://romm.example.com:8443")
            assertThat(valid("https://romm.example.com:8443").port).isEqualTo(8443)
        }

        @Test
        fun `explicit default port is omitted`() {
            assertThat(valid("https://romm.example.com:443").origin)
                .isEqualTo("https://romm.example.com")
            assertThat(valid("http://192.168.1.10:80").origin)
                .isEqualTo("http://192.168.1.10")
            assertThat(valid("https://romm.example.com:443").port).isNull()
        }

        @Test
        fun `max port accepted`() {
            assertThat(valid("https://romm.example.com:65535").port).isEqualTo(65535)
        }

        @Test
        fun `port 0 rejected`() {
            assertThat(invalid("https://romm.example.com:0")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `port out of range rejected`() {
            assertThat(invalid("https://romm.example.com:70000")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `base path preserved`() {
            assertThat(valid("https://romm.example.com/romm").origin)
                .isEqualTo("https://romm.example.com/romm")
            assertThat(valid("https://romm.example.com/romm").basePath).isEqualTo("/romm")
        }

        @Test
        fun `trailing slash stripped`() {
            assertThat(valid("https://romm.example.com/romm/").origin)
                .isEqualTo("https://romm.example.com/romm")
            assertThat(valid("https://romm.example.com/").origin)
                .isEqualTo("https://romm.example.com")
        }

        @Test
        fun `redundant trailing slashes stripped`() {
            assertThat(valid("https://romm.example.com/romm//").basePath).isEqualTo("/romm")
        }
    }

    @Nested
    @DisplayName("Rejection")
    inner class Rejection {
        @Test
        fun `blank input`() {
            assertThat(invalid("")).isEqualTo(InvalidReason.BLANK)
            assertThat(invalid("   ")).isEqualTo(InvalidReason.BLANK)
            assertThat(
                (RommServerAddress.parseAndNormalize(null) as ServerAddressResult.Invalid).reason,
            ).isEqualTo(InvalidReason.BLANK)
        }

        @Test
        fun `genuinely unparseable input rejected`() {
            // Inputs that cannot form a valid URL even after scheme inference.
            assertThat(invalid(":::")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `unsupported scheme`() {
            assertThat(invalid("ftp://example.com")).isEqualTo(InvalidReason.UNSUPPORTED_SCHEME)
            assertThat(invalid("ws://example.com")).isEqualTo(InvalidReason.UNSUPPORTED_SCHEME)
        }

        @Test
        fun `credentials rejected`() {
            assertThat(invalid("https://user:pass@example.com"))
                .isEqualTo(InvalidReason.CREDENTIALS_PRESENT)
            assertThat(invalid("https://user@example.com"))
                .isEqualTo(InvalidReason.CREDENTIALS_PRESENT)
        }

        @Test
        fun `query rejected`() {
            assertThat(invalid("https://example.com?x=1"))
                .isEqualTo(InvalidReason.QUERY_OR_FRAGMENT)
        }

        @Test
        fun `fragment rejected`() {
            assertThat(invalid("https://example.com/#top"))
                .isEqualTo(InvalidReason.QUERY_OR_FRAGMENT)
        }

        @Test
        fun `internal whitespace rejected`() {
            assertThat(invalid("https://exa mple.com")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `control characters rejected`() {
            assertThat(invalid("https://example.com/\u0001")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }

        @Test
        fun `missing host rejected`() {
            assertThat(invalid("https://")).isEqualTo(InvalidReason.INVALID_FORMAT)
        }
    }

    @Nested
    @DisplayName("Private vs public HTTP classification")
    inner class Classification {
        @Test
        fun `https accepted for any host`() {
            assertThat(valid("https://public.example.com").origin)
                .isEqualTo("https://public.example.com")
        }

        @Test
        fun `public http rejected`() {
            assertThat(invalid("http://public.example.com"))
                .isEqualTo(InvalidReason.INSECURE_PUBLIC_HTTP)
        }

        @Test
        fun `public IP http rejected`() {
            assertThat(invalid("http://8.8.8.8"))
                .isEqualTo(InvalidReason.INSECURE_PUBLIC_HTTP)
        }

        @Test
        fun `loopback http accepted`() {
            assertThat(valid("http://localhost:8080").origin).isEqualTo("http://localhost:8080")
            assertThat(valid("http://127.0.0.1:8080").origin).isEqualTo("http://127.0.0.1:8080")
            assertThat(valid("http://[::1]:8080").origin).isEqualTo("http://[::1]:8080")
        }

        @Test
        fun `rfc1918 http accepted`() {
            assertThat(valid("http://10.0.0.5").origin).isEqualTo("http://10.0.0.5")
            assertThat(valid("http://172.16.0.5").origin).isEqualTo("http://172.16.0.5")
            assertThat(valid("http://172.31.255.9").origin).isEqualTo("http://172.31.255.9")
            assertThat(valid("http://192.168.1.10").origin).isEqualTo("http://192.168.1.10")
        }

        @Test
        fun `ipv4 link-local http accepted`() {
            assertThat(valid("http://169.254.0.1").origin).isEqualTo("http://169.254.0.1")
        }

        @Test
        fun `unique local ipv6 http accepted`() {
            assertThat(valid("http://[fd00::1]").origin).isEqualTo("http://[fd00::1]")
            assertThat(valid("http://[fc00::1]").origin).isEqualTo("http://[fc00::1]")
        }

        @Test
        fun `ipv6 link-local http accepted`() {
            assertThat(valid("http://[fe80::1]").origin).isEqualTo("http://[fe80::1]")
        }

        @Test
        fun `dot local hostname http accepted`() {
            assertThat(valid("http://romm.local").origin).isEqualTo("http://romm.local")
        }

        @Test
        fun `classify returns secure for https`() {
            assertThat(RommServerAddress.classify(valid("https://example.com")))
                .isEqualTo(AddressClassification.SECURE)
            assertThat(RommServerAddress.isPrivateLanHttp(valid("https://example.com")))
                .isFalse()
        }

        @Test
        fun `classify returns private-lan for private http`() {
            val result = valid("http://192.168.1.10")
            assertThat(RommServerAddress.classify(result))
                .isEqualTo(AddressClassification.PRIVATE_LAN_HTTP)
            assertThat(RommServerAddress.isPrivateLanHttp(result)).isTrue()
        }
    }

    @Nested
    @DisplayName("Network-call boundary")
    inner class NetworkBoundary {
        @Test
        fun `valid origin yields a URL`() {
            val result = valid("https://romm.example.com:8443/romm")
            val url = RommServerAddress.toHttpUrl(result)
            assertThat(url.scheme).isEqualTo("https")
            assertThat(url.host).isEqualTo("romm.example.com")
            assertThat(url.port).isEqualTo(8443)
            assertThat(url.encodedPath).isEqualTo("/romm")
        }

        @Test
        fun `rejected origin cannot become a network call`() {
            // A rejected origin never yields a Valid result, and toHttpUrl only
            // accepts Valid — so there is no path from a rejected origin to a URL.
            val rejected = RommServerAddress.parseAndNormalize("http://public.example.com")
            assertThat(rejected).isInstanceOf(ServerAddressResult.Invalid::class.java)

            // Confirms the canonical origin of a valid address round-trips through
            // the same OkHttp parser used for requests.
            val roundTripped = RommServerAddress.toHttpUrl(valid("https://example.com"))
            assertThat(roundTripped.toString()).isEqualTo("https://example.com/")
        }
    }
}

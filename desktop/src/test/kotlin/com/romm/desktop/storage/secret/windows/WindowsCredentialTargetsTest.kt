package com.romm.desktop.storage.secret.windows

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Pure target-derivation tests (plans/WINDOWS_IMPL.md §4.3): deterministic, separator-safe,
 * injective encoding of the token-store scope into a Windows generic-credential target. Runnable
 * on any host — no Win32 involved.
 */
@DisplayName("Windows credential target derivation")
class WindowsCredentialTargetsTest {

    @Test
    fun `target is deterministic for the same scope`() {
        val scope = "https://romm.example.com|alice"
        assertThat(WindowsCredentialTargets.targetForScope(scope))
            .isEqualTo(WindowsCredentialTargets.targetForScope(scope))
    }

    @Test
    fun `target is scoped by app prefix, origin, and user identity`() {
        val target = WindowsCredentialTargets.targetForScope("https://romm.example.com|alice")
        assertThat(target).startsWith(WindowsCredentialTargets.APP_PREFIX)
        assertThat(target).startsWith("generic:RomMulus|https%3A%2F%2Fromm.example.com%7Calice")
    }

    @Test
    fun `scope is canonicalized by trim and lowercase`() {
        // trim() applies to the whole scope (leading/trailing); the token store trims each
        // part before joining, so interior spacing is part of the scope and is preserved.
        val a = WindowsCredentialTargets.targetForScope("  HTTPS://Romm.Example.COM|Alice  ")
        val b = WindowsCredentialTargets.targetForScope("https://romm.example.com|alice")
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `separator characters in the scope are percent-encoded, never raw`() {
        val target = WindowsCredentialTargets.targetForScope("https://a|b|c:1234 x|y")
        assertThat(target).startsWith(WindowsCredentialTargets.APP_PREFIX)
        val encodedPart = target.removePrefix(WindowsCredentialTargets.APP_PREFIX)
        // No raw separator/whitespace survives encoding: the composition is unambiguous.
        assertThat(encodedPart).doesNotContain("|", " ", ":")
        assertThat(encodedPart).isEqualTo("https%3A%2F%2Fa%7Cb%7Cc%3A1234%20x%7Cy")
    }

    @Test
    fun `a scope cannot forge or escape the app prefix`() {
        // A scope that literally contains the app prefix must encode into the payload part,
        // producing a distinct target — never a collision with a real app target.
        val forged = WindowsCredentialTargets.targetForScope("generic:RomMulus|evil")
        val real = WindowsCredentialTargets.targetForScope("evil")
        assertThat(forged).isNotEqualTo(real)
        assertThat(forged).startsWith(WindowsCredentialTargets.APP_PREFIX)
        // The encoded part begins with the percent-encoded 'g', not a raw prefix.
        assertThat(forged.removePrefix(WindowsCredentialTargets.APP_PREFIX)).startsWith("generic%3A")
    }

    @Test
    fun `distinct scopes yield distinct targets (injective, including percent look-alikes)`() {
        val pairs = listOf(
            "a|b" to "a%7Cb", // literal percent in one scope must not collide with encoded pipe
            "https://x|u" to "https://y|u",
            "https://x|u" to "https://x|v",
            "a b" to "a%20b",
            "a+b" to "a%2Bb",
        )
        for ((left, right) in pairs) {
            val l = WindowsCredentialTargets.targetForScope(left)
            val r = WindowsCredentialTargets.targetForScope(right)
            assertThat(l).`as`("scope '$left' vs '$right'").isNotEqualTo(r)
        }
    }

    @Test
    fun `non-ascii scopes encode as utf-8 percent sequences`() {
        val target = WindowsCredentialTargets.targetForScope("https://x|üser")
        assertThat(target.removePrefix(WindowsCredentialTargets.APP_PREFIX)).isEqualTo("https%3A%2F%2Fx%7C%C3%BCser")
    }

    @Test
    fun `unreserved characters pass through unchanged`() {
        assertThat(WindowsCredentialTargets.percentEncode("Abc-XYZ._~123")).isEqualTo("Abc-XYZ._~123")
    }

    @Test
    fun `percentEncode is deterministic uppercase hex`() {
        assertThat(WindowsCredentialTargets.percentEncode("|")).isEqualTo("%7C")
        assertThat(WindowsCredentialTargets.percentEncode(":")).isEqualTo("%3A")
        assertThat(WindowsCredentialTargets.percentEncode(" ")).isEqualTo("%20")
        assertThat(WindowsCredentialTargets.percentEncode("%")).isEqualTo("%25")
    }

    @Test
    fun `isAppTarget accepts only app-owned targets`() {
        assertThat(WindowsCredentialTargets.isAppTarget("generic:RomMulus|abc")).isTrue()
        assertThat(WindowsCredentialTargets.isAppTarget("generic:OtherApp|abc")).isFalse()
        assertThat(WindowsCredentialTargets.isAppTarget("rommulus-legacy")).isFalse()
        assertThat(WindowsCredentialTargets.isAppTarget("")).isFalse()
    }

    @Test
    fun `max target length matches the Win32 limit`() {
        assertThat(WindowsCredentialTargets.MAX_TARGET_LENGTH).isEqualTo(5121)
    }
}

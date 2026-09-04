package com.romm.desktop.storage.secret.windows

import com.romm.desktop.storage.secret.KeyringState
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * In-memory [WindowsCredentialApi] fake for backend policy tests (runs on any host). Mirrors the
 * real API's observable behavior: raw blob storage, not-found on missing targets, idempotent
 * delete, prefix-filtered enumeration — plus test hooks (modes, call recording, read mangling).
 */
class FakeWindowsCredentialApi : WindowsCredentialApi {

    enum class Mode { AVAILABLE, UNAVAILABLE, DENIED }

    var mode: Mode = Mode.AVAILABLE

    /** Raw Win32 credential blobs, keyed by target name (same shape as Credential Manager). */
    val store = LinkedHashMap<String, ByteArray>()

    var lastWriteTarget: String? = null
    var writeCount: Int = 0
    val deletedTargets = mutableListOf<String>()
    val enumeratedFilters = mutableListOf<String>()
    /** The exact array reference handed to the backend per target (byte-cleanup assertions). */
    val handedOutReadArrays = LinkedHashMap<String, ByteArray>()
    /** When set, reads return the stored blob with the final byte flipped (write-verification). */
    var mangleRead: Boolean = false

    override fun write(targetName: String, secret: ByteArray): CredentialWriteResult {
        lastWriteTarget = targetName
        writeCount++
        return when (mode) {
            Mode.AVAILABLE -> {
                store[targetName] = secret.copyOf()
                CredentialWriteResult.Ok
            }
            Mode.UNAVAILABLE -> CredentialWriteResult.Unavailable("fake: credential manager unreachable")
            Mode.DENIED -> CredentialWriteResult.Denied("fake: access denied")
        }
    }

    override fun read(targetName: String): CredentialReadResult {
        return when (mode) {
            Mode.UNAVAILABLE -> CredentialReadResult.Unavailable("fake: credential manager unreachable")
            Mode.DENIED -> CredentialReadResult.Denied("fake: access denied")
            Mode.AVAILABLE -> {
                val stored = store[targetName] ?: return CredentialReadResult.NotFound
                val handed = stored.copyOf().also { if (mangleRead) it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
                handedOutReadArrays[targetName] = handed
                CredentialReadResult.Found(handed)
            }
        }
    }

    override fun delete(targetName: String): CredentialDeleteResult {
        deletedTargets += targetName
        return when (mode) {
            Mode.AVAILABLE -> {
                store.remove(targetName)
                CredentialDeleteResult.Ok
            }
            Mode.UNAVAILABLE -> CredentialDeleteResult.Unavailable("fake: credential manager unreachable")
            Mode.DENIED -> CredentialDeleteResult.Denied("fake: access denied")
        }
    }

    override fun enumerateTargets(filter: String): CredentialEnumerateResult {
        enumeratedFilters += filter
        return when (mode) {
            Mode.UNAVAILABLE -> CredentialEnumerateResult.Unavailable("fake: credential manager unreachable")
            Mode.DENIED -> CredentialEnumerateResult.Denied("fake: access denied")
            Mode.AVAILABLE -> {
                val prefix = filter.removeSuffix("*")
                CredentialEnumerateResult.Ok(store.keys.filter { it.startsWith(prefix) }.toList())
            }
        }
    }
}

/**
 * Backend policy tests against the fake seam (plans/WINDOWS_IMPL.md §4.3): outcome mapping,
 * replace-on-write with verification, exact delete / scoped deleteAll, malformed handling,
 * byte cleanup, and redaction. Runnable on macOS/Linux — no Win32 involved.
 */
@DisplayName("Windows Credential Manager backend (fake seam)")
class WindowsCredentialBackendTest {

    private val fake = FakeWindowsCredentialApi()
    private val backend = WindowsCredentialBackend(fake)

    private val scope = "https://romm.example.com|alice"
    private val target = WindowsCredentialTargets.targetForScope(scope)
    private val token = "jwt-ish.token.value"

    // --- state mapping -----------------------------------------------------------

    @Test
    fun `state maps enumeration outcomes to keyring states`() {
        fake.mode = FakeWindowsCredentialApi.Mode.AVAILABLE
        assertThat(backend.state()).isEqualTo(KeyringState.Available)

        fake.mode = FakeWindowsCredentialApi.Mode.UNAVAILABLE
        assertThat(backend.state()).isEqualTo(KeyringState.Unavailable)

        fake.mode = FakeWindowsCredentialApi.Mode.DENIED
        assertThat(backend.state()).isInstanceOf(KeyringState.Denied::class.java)
    }

    // --- store / retrieve --------------------------------------------------------

    @Test
    fun `store and retrieve roundtrip a unicode token under the derived target`() {
        val secret = "tøken-✓-🎮"
        assertThat(backend.store(scope, secret)).isTrue()
        assertThat(fake.lastWriteTarget).isEqualTo(target)
        assertThat(backend.retrieve(scope)).isEqualTo(secret)
    }

    @Test
    fun `stored blob is a version-1 credential blob carrying the framed payload`() {
        backend.store(scope, token)
        val blob = fake.store[target]!!
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(buffer.int).isEqualTo(1) // CREDENTIAL_BLOB version
        assertThat(buffer.int).isEqualTo(1) // one attribute
        assertThat(buffer.int).isEqualTo(0x2) // CRED_ATTRIBUTE_PASSWORD_CREDENTIAL
        val valueLength = buffer.int
        val value = ByteArray(valueLength)
        buffer.get(value)
        // Framed payload: RMM1 magic + version + UTF-8 token.
        assertThat(value.copyOfRange(0, 4)).isEqualTo("RMM1".toByteArray())
        assertThat(value[4]).isEqualTo(0x01.toByte())
        assertThat(String(value, 5, value.size - 5, Charsets.UTF_8)).isEqualTo(token)
    }

    @Test
    fun `store replaces the existing credential on the same target`() {
        backend.store(scope, "first")
        backend.store(scope, "second")
        assertThat(fake.store.keys.count { it == target }).isEqualTo(1)
        assertThat(backend.retrieve(scope)).isEqualTo("second")
    }

    @Test
    fun `store verifies the write by re-reading and fails closed on mismatch`() {
        fake.mangleRead = true
        assertThat(backend.store(scope, token)).isFalse()
        fake.mangleRead = false
        // The mangling only affected the verification read; the stored blob itself is intact.
        assertThat(backend.retrieve(scope)).isEqualTo(token)
    }

    @Test
    fun `store returns false when the credential manager is unavailable or denied`() {
        fake.mode = FakeWindowsCredentialApi.Mode.UNAVAILABLE
        assertThat(backend.store(scope, token)).isFalse()
        fake.mode = FakeWindowsCredentialApi.Mode.DENIED
        assertThat(backend.store(scope, token)).isFalse()
        assertThat(fake.store).isEmpty()
    }

    @Test
    fun `retrieve returns null when absent, unavailable, or denied`() {
        assertThat(backend.retrieve(scope)).isNull()
        fake.mode = FakeWindowsCredentialApi.Mode.UNAVAILABLE
        assertThat(backend.retrieve(scope)).isNull()
        fake.mode = FakeWindowsCredentialApi.Mode.DENIED
        assertThat(backend.retrieve(scope)).isNull()
    }

    // --- malformed handling ------------------------------------------------------

    @Test
    fun `a blob without the framed payload is treated as absent`() {
        // A well-formed CREDENTIAL_BLOB whose password value is not ours (e.g. written by
        // another tool under a colliding target): must never be returned as a token.
        fake.store[target] = credentialBlob(passwordValue = "foreign-plaintext".toByteArray())
        assertThat(backend.retrieve(scope)).isNull()
    }

    @Test
    fun `a blob with an unknown framing version is treated as absent`() {
        val framed = "RMM1".toByteArray() + byteArrayOf(0x7F) + token.toByteArray()
        fake.store[target] = credentialBlob(passwordValue = framed)
        assertThat(backend.retrieve(scope)).isNull()
    }

    @Test
    fun `a truncated or non-version-1 credential blob is treated as absent`() {
        fake.store[target] = byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00)
        assertThat(backend.retrieve(scope)).isNull()
        fake.store[target] = byteArrayOf(0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x02)
        assertThat(backend.retrieve(scope)).isNull()
    }

    @Test
    fun `a hostile blob with an overflowing attribute length is absent, never thrown`() {
        // version=1, one attribute, flags=PASSWORD, valueLength=0x7FFFFFFF: the bounds check
        // must reject it without integer overflow (which would let copyOfRange throw).
        fake.store[target] = byteArrayOf(
            0x01, 0x00, 0x00, 0x00, // version
            0x01, 0x00, 0x00, 0x00, // attribute count
            0x02, 0x00, 0x00, 0x00, // flags = CRED_ATTRIBUTE_PASSWORD_CREDENTIAL
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, // valueLength = 0x7FFFFFFF
        )
        assertThat(backend.retrieve(scope)).isNull()
    }

    // --- delete / deleteAll ------------------------------------------------------

    @Test
    fun `delete removes exactly the derived target`() {
        backend.store(scope, token)
        backend.delete(scope)
        assertThat(fake.deletedTargets).containsExactly(target)
        assertThat(fake.store).isEmpty()
    }

    @Test
    fun `delete of an absent credential is idempotent success`() {
        backend.delete(scope) // never stored
        assertThat(fake.deletedTargets).containsExactly(target)
    }

    @Test
    fun `deleteAll deletes only app-owned targets and never foreign credentials`() {
        val foreignGeneric = "generic:OtherApp|bob"
        val foreignPlain = "rommulus-legacy"
        fake.store[WindowsCredentialTargets.targetForScope("https://a.example|u1")] = ByteArray(8)
        fake.store[WindowsCredentialTargets.targetForScope("https://b.example|u2")] = ByteArray(8)
        fake.store[foreignGeneric] = ByteArray(8)
        fake.store[foreignPlain] = ByteArray(8)

        backend.deleteAll()

        assertThat(fake.store.keys)
            .containsExactlyInAnyOrder(foreignGeneric, foreignPlain)
        assertThat(fake.deletedTargets)
            .containsExactlyInAnyOrder(
                WindowsCredentialTargets.targetForScope("https://a.example|u1"),
                WindowsCredentialTargets.targetForScope("https://b.example|u2"),
            )
        // Enumeration must be scoped to the app prefix, not a bare wildcard.
        assertThat(fake.enumeratedFilters.last()).startsWith(WindowsCredentialTargets.APP_PREFIX)
    }

    // --- byte cleanup and redaction ----------------------------------------------

    @Test
    fun `raw blob bytes handed to the backend are zeroed after retrieve`() {
        backend.store(scope, token)
        assertThat(backend.retrieve(scope)).isEqualTo(token)
        val handed = fake.handedOutReadArrays[target]!!
        assertThat(handed).isNotEmpty()
        assertThat(handed.all { it == 0.toByte() }).`as`("blob bytes must be cleaned up").isTrue()
    }

    @Test
    fun `secret bytes never appear in api failure reasons or targets`() {
        fake.mode = FakeWindowsCredentialApi.Mode.DENIED
        backend.store(scope, token)
        backend.retrieve(scope)
        // Targets only ever carry the percent-encoded scope; the raw token must never appear in
        // any api interaction recorded by the fake.
        assertThat(fake.lastWriteTarget).doesNotContain(token)
        assertThat(fake.deletedTargets.none { it.contains(token) }).isTrue()
    }

    // --- size guard ----------------------------------------------------------------

    @Test
    fun `oversized scopes are refused without touching the api`() {
        val hugeScope = "https://x.example|" + "u".repeat(6000)
        assertThat(backend.store(hugeScope, token)).isFalse()
        assertThat(backend.retrieve(hugeScope)).isNull()
        assertThat(fake.writeCount).isZero()
        assertThat(fake.store).isEmpty()
    }

    // --- helpers -------------------------------------------------------------------

    /** Builds a version-1 CREDENTIAL_BLOB with a single password attribute (little-endian). */
    private fun credentialBlob(passwordValue: ByteArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(8 + 8 + passwordValue.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1) // version
        buffer.putInt(1) // attribute count
        buffer.putInt(0x2) // CRED_ATTRIBUTE_PASSWORD_CREDENTIAL
        buffer.putInt(passwordValue.size)
        buffer.put(passwordValue)
        return buffer.array()
    }
}

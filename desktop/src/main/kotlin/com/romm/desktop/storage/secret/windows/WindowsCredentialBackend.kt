package com.romm.desktop.storage.secret.windows

import com.romm.desktop.log.DesktopLogger
import com.romm.desktop.storage.secret.KeyringState
import com.romm.desktop.storage.secret.SecretBackend
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CharacterCodingException
import java.util.logging.Level

/**
 * Windows Credential Manager implementation of [SecretBackend] (plans/WINDOWS_IMPL.md §4.3).
 *
 * Semantics:
 *  - **Target**: a deterministic generic-credential target derived from the RomMulus app prefix,
 *    the canonical server origin, and the existing user identity/scope
 *    ([WindowsCredentialTargets.targetForScope]) so multiple RomM servers never collide.
 *  - **Framing**: the token is stored inside the Win32 credential blob as a
 *    `CREDENTIAL_BLOB` (version 1, single password attribute) whose password value is a framed
 *    payload (`RMM1` magic + version + UTF-8 token). The framing makes "malformed" a real,
 *    detectable outcome: a blob we did not write (or a tampered one) is treated as absent, never
 *    returned as a token.
 *  - **Outcomes**: not-found / unavailable / denied are distinguished by the [WindowsCredentialApi]
 *    results; locked has no Windows analogue and is never reported.
 *  - **Replace on write**: `CredWriteW` overwrites an existing same-target credential; [store]
 *    additionally verifies the write by re-reading and comparing the framed payload, per the
 *    [SecretBackend] "durably committed and immediately re-readable" contract.
 *  - **Exact delete / deleteAll without plaintext fallback**: [delete] removes exactly the target
 *    derived from the scope; [deleteAll] enumerates with the app prefix and deletes only targets
 *    it still verifies it owns. There is NO file fallback on Windows — if Credential Manager is
 *    unavailable, login stays unauthenticated with an actionable error.
 *  - **Redaction / byte cleanup**: secret bytes never reach logs (reasons carry only Win32 error
 *    diagnostics; target names are not logged because they embed the user identity). Raw blob
 *    bytes are zeroed as soon as they are no longer needed.
 *
 * No method throws (the [SecretBackend] contract).
 */
class WindowsCredentialBackend(
    private val api: WindowsCredentialApi,
) : SecretBackend {

    // Adapter composition happens before Main installs the platform-specific logger.
    // Resolve it only when a credential operation actually needs to report a warning.
    private val logger by lazy { DesktopLogger.get() }

    override fun state(): KeyringState = runCatching {
        when (val result = api.enumerateTargets(WindowsCredentialTargets.APP_PREFIX + "*")) {
            is CredentialEnumerateResult.Ok -> KeyringState.Available
            is CredentialEnumerateResult.Unavailable -> KeyringState.Unavailable
            is CredentialEnumerateResult.Denied -> KeyringState.Denied(result.reason)
        }
    }.getOrDefault(KeyringState.Unavailable)

    override fun store(scope: String, secret: String): Boolean {
        val target = WindowsCredentialTargets.targetForScope(scope)
        if (target.length > WindowsCredentialTargets.MAX_TARGET_LENGTH) {
            warn("credential target name too long; refusing to store (fail closed)")
            return false
        }
        val payload = encodeFramedPayload(secret)
        val expected = payload.copyOf()
        // The API stores the raw Win32 credential blob: wrap the framed payload in a
        // CREDENTIAL_BLOB (version 1, single password attribute).
        val blob = buildCredentialBlob(payload)
        val writeResult = api.write(target, blob)
        payload.fill(0)
        blob.fill(0)
        val ok: Boolean = when (writeResult) {
            is CredentialWriteResult.Ok -> {
                // Replace-on-write is verified: the new framed payload must be immediately
                // re-readable and byte-identical.
                val verified = readFramedPayload(target)
                val success = verified != null && verified.contentEquals(expected)
                if (!success) warn("credential write verification failed; treating store as failed")
                success
            }
            is CredentialWriteResult.Unavailable -> {
                warn("credential store unavailable: ${writeResult.reason}")
                false
            }
            is CredentialWriteResult.Denied -> {
                warn("credential store denied: ${writeResult.reason}")
                false
            }
        }
        expected.fill(0)
        return ok
    }

    override fun retrieve(scope: String): String? {
        val target = WindowsCredentialTargets.targetForScope(scope)
        if (target.length > WindowsCredentialTargets.MAX_TARGET_LENGTH) return null
        val payload = readFramedPayload(target) ?: return null
        return decodeFramedPayload(payload).also { payload.fill(0) } // byte cleanup
    }

    override fun delete(scope: String) {
        val target = WindowsCredentialTargets.targetForScope(scope)
        if (target.length > WindowsCredentialTargets.MAX_TARGET_LENGTH) return
        when (val result = api.delete(target)) {
            is CredentialDeleteResult.Ok -> Unit
            is CredentialDeleteResult.Unavailable -> warn("credential delete unavailable: ${result.reason}")
            is CredentialDeleteResult.Denied -> warn("credential delete denied: ${result.reason}")
        }
    }

    override fun deleteAll() {
        when (val result = api.enumerateTargets(WindowsCredentialTargets.APP_PREFIX + "*")) {
            is CredentialEnumerateResult.Ok -> {
                for (name in result.targetNames) {
                    // Defense in depth: only ever delete credentials this application owns, even
                    // if the enumeration filter semantics ever change.
                    if (WindowsCredentialTargets.isAppTarget(name)) {
                        api.delete(name)
                    }
                }
            }
            is CredentialEnumerateResult.Unavailable -> warn("credential deleteAll unavailable: ${result.reason}")
            is CredentialEnumerateResult.Denied -> warn("credential deleteAll denied: ${result.reason}")
        }
    }

    // --- payload framing ---------------------------------------------------------

    /**
     * Reads [target] and returns the framed payload bytes, or null (absent, unavailable, denied,
     * or malformed — a blob/payload we did not write is never returned).
     */
    private fun readFramedPayload(target: String): ByteArray? = when (val result = api.read(target)) {
        is CredentialReadResult.Found -> {
            val payload = extractPasswordAttribute(result.bytes)
            result.bytes.fill(0) // byte cleanup: raw blob no longer needed
            if (payload == null) {
                warn("credential blob is malformed; treating as absent (fail closed)")
                null
            } else if (!hasValidFraming(payload)) {
                payload.fill(0) // byte cleanup
                warn("credential payload is malformed; treating as absent (fail closed)")
                null
            } else {
                payload
            }
        }
        is CredentialReadResult.NotFound -> null
        is CredentialReadResult.Unavailable -> null
        is CredentialReadResult.Denied -> null
    }

    /** `RMM1` + version byte + UTF-8 token. */
    private fun encodeFramedPayload(secret: String): ByteArray {
        val token = secret.toByteArray(Charsets.UTF_8)
        return PAYLOAD_MAGIC + byteArrayOf(PAYLOAD_VERSION.toByte()) + token
    }

    private fun decodeFramedPayload(payload: ByteArray): String? {
        if (!hasValidFraming(payload)) return null
        return try {
            String(payload, PAYLOAD_MAGIC.size + 1, payload.size - PAYLOAD_MAGIC.size - 1, Charsets.UTF_8)
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** True iff [payload] starts with our magic and carries a supported version byte. */
    private fun hasValidFraming(payload: ByteArray): Boolean {
        if (payload.size < PAYLOAD_MAGIC.size + 1) return false
        for (i in PAYLOAD_MAGIC.indices) {
            if (payload[i] != PAYLOAD_MAGIC[i]) return false
        }
        return payload[PAYLOAD_MAGIC.size].toInt() == PAYLOAD_VERSION
    }

    // --- Win32 CREDENTIAL_BLOB (version 1) framing -------------------------------

    /**
     * Builds a `CREDENTIAL_BLOB` (version 1) with a single password attribute carrying [payload].
     * Layout (little-endian): `DWORD Version`, `DWORD AttributeCount`, then per attribute
     * `DWORD Flags`, `DWORD ValueLength`, `BYTE Value[]`.
     */
    private fun buildCredentialBlob(payload: ByteArray): ByteArray {
        val buffer = ByteBuffer
            .allocate(8 + 8 + payload.size)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(CREDENTIAL_BLOB_VERSION)
        buffer.putInt(1) // one attribute
        buffer.putInt(CRED_ATTRIBUTE_PASSWORD_CREDENTIAL)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Extracts the password attribute value from a `CREDENTIAL_BLOB`, or null when the blob is
     * not a well-formed version-1 blob carrying a password attribute (malformed outcome).
     */
    private fun extractPasswordAttribute(blob: ByteArray): ByteArray? {
        if (blob.size < 8) return null
        if (readIntLe(blob, 0) != CREDENTIAL_BLOB_VERSION) return null
        val attributeCount = readIntLe(blob, 4)
        if (attributeCount < 0 || attributeCount > MAX_ATTRIBUTES) return null
        var offset = 8
        repeat(attributeCount) {
            if (offset + 8 > blob.size) return null
            val flags = readIntLe(blob, offset)
            val valueLength = readIntLe(blob, offset + 4)
            // Overflow-safe bounds check: `offset + 8 + valueLength` would wrap for hostile
            // valueLength values (e.g. 0x7FFFFFFF) and let copyOfRange throw, violating the
            // never-throw [SecretBackend] contract.
            if (valueLength < 0 || valueLength > blob.size - offset - 8) return null
            offset += 8
            if (flags and CRED_ATTRIBUTE_PASSWORD_CREDENTIAL != 0) {
                return blob.copyOfRange(offset, offset + valueLength)
            }
            offset += valueLength
        }
        return null
    }

    private fun readIntLe(blob: ByteArray, offset: Int): Int =
        (blob[offset].toInt() and 0xFF) or
            ((blob[offset + 1].toInt() and 0xFF) shl 8) or
            ((blob[offset + 2].toInt() and 0xFF) shl 16) or
            ((blob[offset + 3].toInt() and 0xFF) shl 24)

    private fun warn(message: String) {
        // Deliberately no target/scope in the message: targets embed the user identity, and
        // reasons must never carry secret bytes (plans/WINDOWS_IMPL.md §4.3).
        logger.log(Level.WARNING, "windows-credential: %s", message)
    }

    private companion object {
        val PAYLOAD_MAGIC: ByteArray = "RMM1".toByteArray(Charsets.US_ASCII)
        const val PAYLOAD_VERSION: Int = 0x01
        const val CREDENTIAL_BLOB_VERSION: Int = 1
        const val CRED_ATTRIBUTE_PASSWORD_CREDENTIAL: Int = 0x2
        const val MAX_ATTRIBUTES: Int = 16
    }
}

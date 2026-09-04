package com.romm.desktop.storage.secret.windows

/**
 * Deterministic derivation of Windows Credential Manager generic-credential target names
 * (plans/WINDOWS_IMPL.md §4.3: "generic credential target name includes application, normalized
 * server origin, and device/user identity so multiple RomM servers do not collide").
 *
 * The desktop token store scopes secrets as `origin|username` (see
 * [com.romm.desktop.storage.secret.SecretServiceClientTokenStore]); that scope already carries the
 * canonical (trimmed, lowercased) server origin and the existing user identity, so the target is:
 *
 * ```
 * generic:RomMulus|<percent-encoded scope>
 * ```
 *
 * The scope is RFC 3986 percent-encoded (unreserved characters pass through, everything else —
 * including `|`, `:`, spaces, and non-ASCII bytes — becomes `%XX` with uppercase hex) so the
 * composition never relies on an ambiguous separator: two distinct scopes always yield two
 * distinct targets, and no scope can inject or forge the `RomMulus` prefix. Pure and
 * side-effect-free so it is unit-testable on any host.
 */
object WindowsCredentialTargets {

    /**
     * Application prefix every RomMulus credential target starts with. The `generic:` prefix is
     * the Windows convention for generic (non-domain) credentials, which makes them show up under
     * "Generic credentials" in the Windows Credential UI; `RomMulus` scopes them to this app so
     * [com.romm.desktop.storage.secret.windows.WindowsCredentialBackend.deleteAll] can enumerate
     * exactly the credentials this application owns.
     */
    const val APP_PREFIX: String = "generic:RomMulus|"

    /** `CRED_MAX_TARGET_NAME_LENGTH` from wincred.h — target names are limited to 5121 chars. */
    const val MAX_TARGET_LENGTH: Int = 5121

    /**
     * Derives the credential target for a token-store [scope] (`origin|username`). Canonicalizes
     * defensively (trim + lowercase, matching the token store's own normalization) and percent-
     * encodes so the result is deterministic and separator-safe.
     */
    fun targetForScope(scope: String): String = APP_PREFIX + percentEncode(scope.trim().lowercase())

    /** True iff [target] belongs to this application (safe to delete via [deleteAll]). */
    fun isAppTarget(target: String): Boolean = target.startsWith(APP_PREFIX)

    /**
     * RFC 3986 §2.1 percent-encoding over the UTF-8 bytes of [value]: unreserved characters
     * (`A-Z a-z 0-9 - _ . ~`) pass through; every other byte becomes `%XX` (uppercase hex).
     * The output therefore contains no raw `|`, `:`, space, or non-ASCII characters.
     */
    fun percentEncode(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (isUnreserved(c)) {
                out.append(c.toChar())
            } else {
                out.append('%').append(HEX[c ushr 4]).append(HEX[c and 0x0F])
            }
        }
        return out.toString()
    }

    private fun isUnreserved(c: Int): Boolean =
        c in 0x41..0x5A || c in 0x61..0x7A || c in 0x30..0x39 ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code

    private const val HEX: String = "0123456789ABCDEF"
}

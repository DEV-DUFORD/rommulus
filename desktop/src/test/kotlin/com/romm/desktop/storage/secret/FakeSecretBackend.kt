package com.romm.desktop.storage.secret

import java.util.LinkedHashMap

/** In-memory [SecretBackend] for unit tests, with a switchable [KeyringState]. */
class FakeSecretBackend(
    var mode: KeyringState = KeyringState.Available,
) : SecretBackend {

    private val secrets = LinkedHashMap<String, String>()

    override fun state(): KeyringState = mode

    override fun store(scope: String, secret: String): Boolean {
        if (mode !is KeyringState.Available) return false
        secrets[scope] = secret
        return true
    }

    override fun retrieve(scope: String): String? {
        if (mode !is KeyringState.Available) return null
        return secrets[scope]
    }

    override fun delete(scope: String) {
        secrets.remove(scope)
    }

    override fun deleteAll() {
        secrets.clear()
    }
}

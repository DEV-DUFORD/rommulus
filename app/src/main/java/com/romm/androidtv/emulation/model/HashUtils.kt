package com.romm.androidtv.emulation.model

import java.security.MessageDigest

/**
 * SHA-256 hex digest of [bytes]. Pure utility, no Android dependency.
 * Canonical implementation shared across all modules.
 */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

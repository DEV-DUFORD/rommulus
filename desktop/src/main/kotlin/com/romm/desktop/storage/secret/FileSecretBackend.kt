package com.romm.desktop.storage.secret

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

/**
 * Owner-only fallback for hosts such as Steam Deck Gaming Mode where no
 * freedesktop Secret Service provider exists.
 */
class FileSecretBackend(
    private val credentialsFile: Path,
) : SecretBackend {
    private val lock = Any()

    override fun state(): KeyringState = synchronized(lock) {
        try {
            ensureParent()
            KeyringState.Available
        } catch (_: IOException) {
            KeyringState.Unavailable
        } catch (_: SecurityException) {
            KeyringState.Unavailable
        }
    }

    override fun store(scope: String, secret: String): Boolean = synchronized(lock) {
        try {
            val values = readValues()
            values.setProperty(scope, secret)
            writeValues(values)
            readValues().getProperty(scope) == secret
        } catch (_: IOException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    override fun retrieve(scope: String): String? = synchronized(lock) {
        try {
            readValues().getProperty(scope)
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    override fun delete(scope: String): Unit = synchronized(lock) {
        try {
            val values = readValues()
            if (values.remove(scope) != null) writeValues(values)
        } catch (_: IOException) {
            Unit
        } catch (_: SecurityException) {
            Unit
        }
    }

    override fun deleteAll(): Unit = synchronized(lock) {
        try {
            Files.deleteIfExists(credentialsFile)
            Unit
        } catch (_: IOException) {
            Unit
        } catch (_: SecurityException) {
            Unit
        }
    }

    private fun readValues(): Properties {
        ensureParent()
        val values = Properties()
        if (Files.exists(credentialsFile)) {
            Files.newInputStream(credentialsFile).use(values::load)
        }
        return values
    }

    private fun writeValues(values: Properties) {
        ensureParent()
        val temp = Files.createTempFile(credentialsFile.parent, ".client-tokens.", ".tmp")
        try {
            Files.newOutputStream(
                temp,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING,
            ).use { values.store(it, "RomMulus client tokens") }
            setPermissions(temp, FILE_PERMISSIONS)
            FileChannel.open(temp, StandardOpenOption.WRITE).use { it.force(true) }
            try {
                Files.move(
                    temp,
                    credentialsFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, credentialsFile, StandardCopyOption.REPLACE_EXISTING)
            }
            setPermissions(credentialsFile, FILE_PERMISSIONS)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ensureParent() {
        Files.createDirectories(credentialsFile.parent)
        setPermissions(credentialsFile.parent, DIRECTORY_PERMISSIONS)
        if (Files.exists(credentialsFile)) setPermissions(credentialsFile, FILE_PERMISSIONS)
    }

    private fun setPermissions(path: Path, permissions: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, permissions)
        } catch (_: UnsupportedOperationException) {
            // SteamOS and supported Ubuntu filesystems are POSIX. This keeps
            // tests and unusual mounted filesystems functional.
        }
    }

    private companion object {
        val DIRECTORY_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE,
        )
        val FILE_PERMISSIONS = setOf(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
        )
    }
}

/**
 * Uses the file store only when Secret Service is absent. Locked and denied
 * providers remain fail-closed.
 */
class UnavailableSecretServiceFallback(
    private val primary: SecretBackend,
    private val fallback: SecretBackend,
) : SecretBackend {
    override fun state(): KeyringState = when (val state = primary.state()) {
        KeyringState.Unavailable -> fallback.state()
        else -> state
    }

    override fun store(scope: String, secret: String): Boolean = when (primary.state()) {
        KeyringState.Available -> primary.store(scope, secret).also { stored ->
            if (stored) fallback.delete(scope)
        }
        KeyringState.Unavailable -> fallback.store(scope, secret)
        KeyringState.Locked, is KeyringState.Denied -> false
    }

    override fun retrieve(scope: String): String? = when (primary.state()) {
        KeyringState.Available -> {
            primary.retrieve(scope) ?: fallback.retrieve(scope)?.let { stored ->
                if (primary.store(scope, stored)) fallback.delete(scope)
                stored
            }
        }
        KeyringState.Unavailable -> fallback.retrieve(scope)
        KeyringState.Locked, is KeyringState.Denied -> null
    }

    override fun delete(scope: String) {
        primary.delete(scope)
        fallback.delete(scope)
    }

    override fun deleteAll() {
        primary.deleteAll()
        fallback.deleteAll()
    }
}

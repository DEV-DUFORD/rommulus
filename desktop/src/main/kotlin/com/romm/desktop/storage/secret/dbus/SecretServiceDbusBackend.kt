package com.romm.desktop.storage.secret.dbus

import com.romm.desktop.storage.secret.KeyringState
import com.romm.desktop.storage.secret.SecretBackend
import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.Struct
import org.freedesktop.dbus.Tuple
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.annotations.Position
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.exceptions.DBusExecutionException
import org.freedesktop.dbus.interfaces.DBus
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.messages.MethodCall
import org.freedesktop.dbus.types.Variant

/**
 * Real Linux transport for the [SecretBackend] seam, talking directly to the freedesktop
 * Secret Service (`org.freedesktop.secrets`) over the session bus via dbus-java (pure Java).
 *
 * Fail-closed contract:
 *  - Any connect / name-resolution / name-with-no-owner failure maps to [KeyringState.Unavailable]
 *    and a null/false read/write result.
 *  - Any access-denied D-Bus error maps to [KeyringState.Denied] with the daemon reason.
 *  - A locked collection (or any operation that would require a host-side prompt) maps to
 *    [KeyringState.Locked]; reads return null, writes return false.
 *  - Secrets are read/written through an `OpenSession("plain")` session, as required by the
 *    Secret Service spec — the daemon rejects raw secret structs with no open session.
 *  - We never invoke `Prompt.Prompt()`, so nothing blocks; a non-empty Prompt object path in a
 *    reply is treated as failure. `MethodCall.setDefaultTimeout` additionally bounds every call.
 *
 * No exception ever escapes any interface method.
 */
class SecretServiceDbusBackend(
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SecretBackend {

    companion object {
        const val BUS_NAME = "org.freedesktop.secrets"
        const val OBJECT_PATH = "/org/freedesktop/secrets"
        const val APPLICATION = "rommulus"
        const val DEFAULT_COLLECTION_ALIAS = "default"
        const val COLLECTION_LABEL = "rommulus"
        const val SECRET_CONTENT_TYPE = "text/plain"
        const val SESSION_ALGORITHM = "plain"
        const val COLLECTION_IFACE = "org.freedesktop.Secret.Collection"
        // 20s is dbus-java's default reply wait; we bound it so a stalled daemon fails closed.
        const val DEFAULT_TIMEOUT_MILLIS = 5_000L

        private val EMPTY_PATH = setOf("", "/")
    }

    /** Lazily connect to the session bus (reads $DBUS_SESSION_BUS_ADDRESS); null if it fails. */
    private val connection: DBusConnection? by lazy {
        MethodCall.setDefaultTimeout(timeoutMillis)
        runCatching {
            DBusConnectionBuilder.forSessionBus().withShared(false).build()
        }.getOrNull()
    }

    private fun connect(): DBusConnection? = connection

    override fun state(): KeyringState {
        val conn = connect() ?: return KeyringState.Unavailable
        return try {
            if (!nameHasOwner(conn)) return KeyringState.Unavailable
            val service = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, SecretService::class.java)
            when (val ref = resolveCollection(service)) {
                is CollectionRef.Open ->
                    if (isCollectionLocked(conn, ref.path)) KeyringState.Locked else KeyringState.Available
                CollectionRef.PromptRequired -> KeyringState.Locked
            }
        } catch (e: Throwable) {
            classifyFailure(e)
        }
    }

    override fun store(scope: String, secret: String): Boolean {
        val conn = connect() ?: return false
        return runCatching {
            if (!nameHasOwner(conn)) return false
            val service = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, SecretService::class.java)
            val ref = resolveCollection(service)
            if (ref !is CollectionRef.Open) return false
            if (isCollectionLocked(conn, ref.path)) return false
            val col = conn.getRemoteObject(BUS_NAME, ref.path.getPath(), SecretCollection::class.java)
            val session = service.OpenSession(SESSION_ALGORITHM, ByteArray(0))
            val secretStruct = SecretStruct(
                session = session,
                parameters = ByteArray(0),
                value = secret.toByteArray(Charsets.UTF_8),
                contentType = SECRET_CONTENT_TYPE,
            )
            val attributes = mapOf("application" to APPLICATION, "scope" to scope)
            val result = col.CreateItem(
                properties = mapOf(
                    "Label" to Variant<String>(scope),
                    "Attributes" to Variant<Map<String, String>>(attributes),
                ),
                secret = secretStruct,
                replace = true,
            )
            // A non-empty prompt path means the daemon wants a (host-side) unlock dialog: fail closed.
            result.prompt.getPath().isEmpty()
        }.getOrDefault(false)
    }

    override fun retrieve(scope: String): String? {
        val conn = connect() ?: return null
        return runCatching {
            if (!nameHasOwner(conn)) return null
            val service = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, SecretService::class.java)
            val ref = resolveCollection(service)
            if (ref !is CollectionRef.Open) return null
            if (isCollectionLocked(conn, ref.path)) return null
            val col = conn.getRemoteObject(BUS_NAME, ref.path.getPath(), SecretCollection::class.java)
            val result = col.SearchItems(mapOf("application" to APPLICATION, "scope" to scope))
            if (result.locked.isNotEmpty()) return null // a matching item is locked -> fail closed
            val itemPath = result.unlocked.firstOrNull() ?: return null
            val item = conn.getRemoteObject(BUS_NAME, itemPath.getPath(), SecretItem::class.java)
            val session = service.OpenSession(SESSION_ALGORITHM, ByteArray(0))
            val s = item.GetSecret(session)
            String(s.value, Charsets.UTF_8)
        }.getOrNull()
    }

    override fun delete(scope: String) {
        deleteMatching(mapOf("application" to APPLICATION, "scope" to scope))
    }

    override fun deleteAll() {
        deleteMatching(mapOf("application" to APPLICATION))
    }

    private fun deleteMatching(attributes: Map<String, String>) {
        val conn = connect() ?: return
        runCatching {
            if (!nameHasOwner(conn)) return
            val service = conn.getRemoteObject(BUS_NAME, OBJECT_PATH, SecretService::class.java)
            val ref = resolveCollection(service)
            if (ref !is CollectionRef.Open) return
            // Deleting from a locked collection would need an unlock prompt: skip, best-effort.
            if (isCollectionLocked(conn, ref.path)) return
            val col = conn.getRemoteObject(BUS_NAME, ref.path.getPath(), SecretCollection::class.java)
            val result = col.SearchItems(attributes)
            for (path in result.unlocked + result.locked) {
                val item = conn.getRemoteObject(BUS_NAME, path.getPath(), SecretItem::class.java)
                item.Delete()
            }
        }
    }

    // --- helpers -------------------------------------------------------------

    private fun nameHasOwner(conn: DBusConnection): Boolean = runCatching {
        val dbus = conn.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus::class.java)
        dbus.NameHasOwner(BUS_NAME)
    }.getOrDefault(false)

    /**
     * Resolves the app collection (alias "default", label "rommulus"), creating it on first use.
     * Throws on transport/daemon errors — callers must catch and fail closed.
     */
    private fun resolveCollection(service: SecretService): CollectionRef {
        val alias = service.ReadAlias(DEFAULT_COLLECTION_ALIAS)
        if (!EMPTY_PATH.contains(alias.getPath())) return CollectionRef.Open(alias)
        val created = service.CreateCollection(
            properties = mapOf("Label" to Variant<String>(COLLECTION_LABEL)),
            alias = DEFAULT_COLLECTION_ALIAS,
        )
        // A non-empty prompt means the daemon needs a host-side dialog before it will create the
        // collection: treat as locked (fail closed) rather than reporting Available.
        return if (EMPTY_PATH.contains(created.prompt.getPath())) {
            CollectionRef.Open(created.collection)
        } else {
            CollectionRef.PromptRequired
        }
    }

    private fun isCollectionLocked(conn: DBusConnection, collection: DBusPath): Boolean = runCatching {
        val props = conn.getRemoteObject(BUS_NAME, collection.getPath(), Properties::class.java)
        val v = props.Get<Any>(COLLECTION_IFACE, "Locked")
        (v as? Variant<*>)?.value as? Boolean ?: false
    }.getOrDefault(false)

    /** Maps a caught transport/daemon failure to a fail-closed [KeyringState]. Never throws. */
    private fun classifyFailure(e: Throwable): KeyringState {
        val type = (e as? DBusExecutionException)?.type
            ?: (e.cause as? DBusExecutionException)?.type
            ?: ""
        val msg = e.message ?: ""
        val denied = type.contains("AccessDenied", ignoreCase = true) ||
            type.contains("NotAllowed", ignoreCase = true) ||
            type.contains("NotAuthorized", ignoreCase = true) ||
            type.contains("SecurityPolicyDenied", ignoreCase = true) ||
            msg.contains("AccessDenied", ignoreCase = true) ||
            msg.contains("not allowed", ignoreCase = true)
        return if (denied) {
            KeyringState.Denied(msg.ifEmpty { "secret service access denied" })
        } else {
            KeyringState.Unavailable
        }
    }

    /** Result of locating the app collection. */
    private sealed class CollectionRef {
        data class Open(val path: DBusPath) : CollectionRef()
        object PromptRequired : CollectionRef()
    }
}

// --- D-Bus interface definitions and DTOs -----------------------------------

@DBusInterfaceName("org.freedesktop.Secret.Service")
interface SecretService : DBusInterface {
    fun ReadAlias(alias: String): DBusPath
    fun CreateCollection(properties: Map<String, Variant<*>>, alias: String): CreateCollectionResult
    fun OpenSession(algorithm: String, input: ByteArray): DBusPath
}

@DBusInterfaceName("org.freedesktop.Secret.Collection")
interface SecretCollection : DBusInterface {
    fun CreateItem(
        properties: Map<String, Variant<*>>,
        secret: SecretStruct,
        replace: Boolean,
    ): CreateItemResult
    fun SearchItems(attributes: Map<String, String>): SearchItemsResult
}

@DBusInterfaceName("org.freedesktop.Secret.Item")
interface SecretItem : DBusInterface {
    fun Delete()
    fun GetSecret(session: DBusPath): SecretStruct
}

/** The Secret Service `(oayays)` struct: session, parameters, value, content_type. */
class SecretStruct(
    @Position(0) val session: DBusPath,
    @Position(1) val parameters: ByteArray,
    @Position(2) val value: ByteArray,
    @Position(3) val contentType: String,
) : Struct()

/** Multi-return DTO for `CreateCollection` -> `(o collection, o prompt)`. */
class CreateCollectionResult(
    @Position(0) val collection: DBusPath,
    @Position(1) val prompt: DBusPath,
) : Tuple()

/** Multi-return DTO for `CreateItem` -> `(o item, o prompt)`. */
class CreateItemResult(
    @Position(0) val item: DBusPath,
    @Position(1) val prompt: DBusPath,
) : Tuple()

/** Multi-return DTO for `SearchItems` -> `(ao unlocked, ao locked)`. */
class SearchItemsResult(
    @Position(0) val unlocked: List<DBusPath>,
    @Position(1) val locked: List<DBusPath>,
) : Tuple()

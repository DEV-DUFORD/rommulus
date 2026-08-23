package com.romm.desktop.player

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi

/** File name of the player's controller-binding sidecar (LINUX_X64.md §11.9). */
const val CONTROLLER_BINDINGS_SIDECAR_FILE_NAME = "controller-bindings.json"

/** The only sidecar version this build understands (mirrors C++ `kBindingSidecarVersion`). */
const val BINDING_SIDECAR_VERSION: Int = 1

/**
 * The player's `<sessionDir>/controller-bindings.json` (LINUX_X64.md §11.9): checkpointed
 * atomically after every in-game edit and retried at shutdown, keyed by each connected pad's
 * stable SDL GUID plus normalized identity. Schema v1:
 *
 * ```
 * { "protocolVersion": 1, "devices": [ <sidecar device entries> ] }
 * ```
 *
 * The device-entry shape is IDENTICAL to the v2 request's `controllerBindings.devices` entries
 * (see [ControllerBindingDevice]) — the player writes both from the same canonical serializer.
 */
data class ControllerBindingSidecar(
    val protocolVersion: Int,
    val devices: List<ControllerBindingDevice>,
)

/**
 * Strict READ-ONLY codec for the sidecar file. The desktop supervisor ingests it after a player
 * session and deletes it on success (it is a session artifact). Parsing mirrors the C++ strictness
 * of [PlayerProtocol]: unknown fields are rejected at every nesting level, wrong types are
 * rejected, and only [BINDING_SIDECAR_VERSION] is accepted. Failures return [Result.failure]
 * (never thrown across the API boundary) so a malformed sidecar can be preserved for forensics
 * without breaking reconciliation.
 */
object ControllerBindingSidecarCodec {

    private val moshi: Moshi = Moshi.Builder()
        .add(ControllerBindingSidecar::class.java, SidecarAdapter())
        .build()

    private val adapter: JsonAdapter<ControllerBindingSidecar> =
        moshi.adapter(ControllerBindingSidecar::class.java)

    fun parse(json: String): Result<ControllerBindingSidecar> = runCatching {
        adapter.fromJson(json) ?: throw PlayerProtocol.ProtocolException("sidecar JSON is empty")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { e ->
            Result.failure(
                if (e is PlayerProtocol.ProtocolException) e
                else PlayerProtocol.ProtocolException("malformed sidecar: ${e.message ?: e::class.java.simpleName}"),
            )
        },
    )

    private class SidecarAdapter : JsonAdapter<ControllerBindingSidecar>() {

        override fun fromJson(reader: JsonReader): ControllerBindingSidecar? {
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var protocolVersion: Long? = null
            var devices: List<ControllerBindingDevice>? = null
            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                val name = reader.nextName()
                seen += name
                when (name) {
                    "protocolVersion" -> protocolVersion = PlayerProtocol.readInt64(reader, name)
                    "devices" -> devices = PlayerProtocol.readControllerBindingDevices(reader)
                    else -> throw PlayerProtocol.ProtocolException("unknown field: $name")
                }
            }
            reader.endObject()

            for (field in REQUIRED_FIELDS) {
                if (field !in seen) throw PlayerProtocol.ProtocolException("missing required field: $field")
            }
            val version = checkNotNull(protocolVersion)
            if (version != BINDING_SIDECAR_VERSION.toLong()) {
                throw PlayerProtocol.ProtocolException("unsupported protocolVersion: $version")
            }
            return ControllerBindingSidecar(version.toInt(), checkNotNull(devices))
        }

        override fun toJson(writer: JsonWriter, value: ControllerBindingSidecar?) {
            // Read-only codec: the desktop never writes sidecars (the player does).
            throw UnsupportedOperationException("sidecar serialization is player-side only")
        }

        private companion object {
            val REQUIRED_FIELDS = setOf("protocolVersion", "devices")
        }
    }
}

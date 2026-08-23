package com.romm.desktop.player

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * The Kotlin side of the v2 player launch request/result file protocol.
 *
 * This is a security boundary with the `rommulus_player` process (plans/LINUX_X64.md
 * §12.2/§12.3) and must match the platform-neutral C++ implementation in
 * `native/player/src/protocol.cpp` EXACTLY: same field names, same types, same strictness.
 * The v2 schema deliberately carries no origin, username, token, server save ID, or upload
 * URL; both parsers reject unknown fields so a secret can never ride along via a schema typo.
 *
 * v2 adds the OPTIONAL request field `controllerBindings` (per-device RetroPad binding
 * tables to apply at launch). Its device-entry shape reuses the sidecar schema
 * ([ControllerBindingSidecarCodec]) verbatim, so a sidecar's `devices` array pastes
 * straight into a request.
 *
 * Implemented as strict Moshi [JsonAdapter]s: parsing walks the [JsonReader] token by token
 * (non-lenient mode), and serialization writes the C++ canonical field order with 2-space
 * indent (nlohmann `dump(2)` equivalent).
 */

/** The only protocol version this build understands (mirrors C++ `kProtocolVersion`). */
const val PLAYER_PROTOCOL_VERSION: Int = 2
private const val LEGACY_RETRO_PAD_SLOT_COUNT = 12

/** Canonical RetroPad slot wire names, in slot order (mirrors C++ `retroPadSlotName`). */
internal val RETRO_PAD_SLOT_NAMES: List<String> = listOf(
    "a", "b", "x", "y", "select", "start",
    "left_shoulder", "right_shoulder",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
    "left_trigger", "right_trigger", "left_stick", "right_stick",
)

/** Canonical pad-button wire names, in `PadButton` ordinal order (mirrors C++ `padButtonName`). */
internal val PAD_BUTTON_NAMES: List<String> = listOf(
    "south", "east", "west", "north", "back", "start",
    "left_shoulder", "right_shoulder",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right",
    "left_stick", "right_stick",
)

/** Canonical pad-axis wire names, in `PadAxis` ordinal order (mirrors C++ `padAxisName`). */
internal val PAD_AXIS_NAMES: List<String> = listOf(
    "left_x", "left_y", "right_x", "right_y", "left_trigger", "right_trigger",
)

/** The wire type of one slot binding entry, including GameCube's full-axis targets. */
enum class PlayerBindingType(val wireName: String) {
    BUTTON("button"),
    AXIS("axis"),
    AXIS_DIRECTION("axis_direction"),
    UNBOUND("unbound");

    companion object {
        fun fromWireName(value: String): PlayerBindingType? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * One RetroPad slot binding entry (sidecar/request wire shape). Exactly one of [button] /
 * ([axis] + [polarity]) is set, matching [type]; [UNBOUND] entries carry neither.
 */
data class PlayerSlotBinding(
    val slot: String,
    val type: PlayerBindingType,
    val button: String? = null,
    val axis: String? = null,
    val polarity: Int? = null,
)

/** Normalized device identity of a controllerBindings entry (sidecar schema). */
data class ControllerBindingIdentity(
    val vendorId: Int?,
    val productId: Int?,
    val descriptor: String,
)

/**
 * One device entry of the v2 request's optional [PlayerRequest.controllerBindings] field.
 * The shape REUSES the sidecar schema (guid + identity + the full 16-slot table). Unlike the
 * sidecar (real 32-hex SDL GUIDs), a launch request may carry an EMPTY guid/identity to mean
 * "apply this table to every connected controller" — which is what the desktop supervisor
 * serializes, since its store keys bindings by core, not by device. The player keeps ONE
 * global binding table applied to every port and seeds from the FIRST device entry.
 */
data class ControllerBindingDevice(
    val guid: String,
    val identity: ControllerBindingIdentity,
    val bindings: List<PlayerSlotBinding>,
    val secondaryBindings: List<PlayerSlotBinding>? = null,
)

/** The v2 request's optional controllerBindings field (absent = player keeps its defaults). */
data class ControllerBindings(val devices: List<ControllerBindingDevice>)

data class KeyboardBindingEntry(
    val target: String,
    val primaryScancode: Int?,
    val secondaryScancode: Int?,
)

data class KeyboardBindings(val bindings: List<KeyboardBindingEntry>)

/** Video settings block of a v1 launch request (§12.2). All four fields are required on the wire. */
data class VideoSettings(
    val fullscreen: Boolean = false,
    val integerScaling: Boolean = false,
    val scanlines: Boolean = false,
    /** Sharp filter (nearest-neighbor scaling); mirrors Android's VideoOptionsDialog toggle. */
    val sharpFilter: Boolean = false,
)

/**
 * Result exit kinds (§12.3). Signals, a missing result, malformed JSON, a protocol mismatch,
 * and a nonzero exit are classified by the supervisor as crashes — they are NEVER coerced
 * into one of these values.
 */
enum class PlayerExitKind(val wireName: String) {
    COMPLETED("completed"),
    USER_CANCELLED_BEFORE_START("user_cancelled_before_start"),
    CORE_REQUESTED_SHUTDOWN("core_requested_shutdown"),
    LAUNCH_FAILED("launch_failed"),
    RUNTIME_FAILED("runtime_failed");

    companion object {
        fun fromWireName(value: String): PlayerExitKind? = entries.firstOrNull { it.wireName == value }
    }
}

/**
 * Launch request v2 (§12.2). Every field except [controllerBindings] is required on the wire;
 * [contentHash] may be the empty string (the player then skips hash verification),
 * [expectedSaveSize] may be null, and [controllerBindings] may be absent (the player then
 * keeps its built-in default binding table).
 *
 * @property expectedSaveSize 64-bit byte size — MUST be [Long] to match the C++ `int64_t`
 *   (a save file may legitimately exceed Int.MAX_VALUE).
 */
data class PlayerRequest(
    val protocolVersion: Int = PLAYER_PROTOCOL_VERSION,
    val sessionId: String,
    val coreId: String,
    val coreBuildRevision: String,
    val corePath: String,
    val contentPath: String,
    val contentHash: String,
    val systemDir: String,
    val savePath: String,
    val candidateSavePath: String,
    val resultPath: String,
    val expectedSaveSize: Long? = null,
    val video: VideoSettings = VideoSettings(),
    /** v2 optional: stored controller bindings to apply from the first frame; null = defaults. */
    val controllerBindings: ControllerBindings? = null,
    /** Optional Linux keyboard bindings; null keeps the player's built-in defaults. */
    val keyboardBindings: KeyboardBindings? = null,
)

/** Result v1 (§12.3). [saveHash], [saveSize], [errorCode], and [errorMessage] may be null. */
data class PlayerResult(
    val protocolVersion: Int = PLAYER_PROTOCOL_VERSION,
    val sessionId: String,
    val exitKind: PlayerExitKind,
    val checkpointWritten: Boolean,
    val candidateSavePath: String,
    val saveHash: String? = null,
    val saveSize: Long? = null,
    val frames: Long = 0L,
    val audioUnderrunFrames: Long = 0L,
    val audioOverrunFrames: Long = 0L,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    /** Final runtime values, absent in older v2 result journals. */
    val video: VideoSettings? = null,
)

/**
 * Strict v1 parsing and canonical serialization. Mirrors `native/player/src/protocol.cpp`:
 * malformed JSON, a non-object top level, missing required fields, wrong types, unknown
 * fields, protocolVersion != 1, and negative integer fields are all rejected via
 * [Result.failure] (never thrown across the API boundary).
 */
object PlayerProtocol {

    /** Parse failure with a human-readable reason. */
    class ProtocolException(message: String) : IOException(message)

    private val moshi: Moshi = Moshi.Builder()
        .add(PlayerRequest::class.java, RequestAdapter())
        .add(PlayerResult::class.java, ResultAdapter())
        .build()

    private val requestAdapter: JsonAdapter<PlayerRequest> = moshi.adapter(PlayerRequest::class.java)
    private val resultAdapter: JsonAdapter<PlayerResult> = moshi.adapter(PlayerResult::class.java)

    // ------------------------------------------------------------------ public API

    fun serializeRequest(request: PlayerRequest): String =
        requireNotNull(requestAdapter.toJson(request)) { "request serialization returned null" }

    fun serializeResult(result: PlayerResult): String =
        requireNotNull(resultAdapter.toJson(result)) { "result serialization returned null" }

    fun parseRequest(json: String): Result<PlayerRequest> = runCatching {
        requestAdapter.fromJson(json) ?: throw ProtocolException("request JSON is empty")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { e -> Result.failure(asProtocolException(e)) },
    )

    fun parseResult(json: String): Result<PlayerResult> = runCatching {
        resultAdapter.fromJson(json) ?: throw ProtocolException("result JSON is empty")
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { e -> Result.failure(asProtocolException(e)) },
    )

    private fun asProtocolException(e: Throwable): ProtocolException =
        if (e is ProtocolException) e else ProtocolException("malformed JSON: ${e.message ?: e::class.java.simpleName}")

    // ------------------------------------------------------------------ request adapter

    private class RequestAdapter : JsonAdapter<PlayerRequest>() {

        override fun fromJson(reader: JsonReader): PlayerRequest? {
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var protocolVersion: Long? = null
            var sessionId: String? = null
            var coreId: String? = null
            var coreBuildRevision: String? = null
            var corePath: String? = null
            var contentPath: String? = null
            var contentHash: String? = null
            var systemDir: String? = null
            var savePath: String? = null
            var candidateSavePath: String? = null
            var resultPath: String? = null
            var expectedSaveSize: Long? = null
            var video: VideoSettings? = null
            var controllerBindings: ControllerBindings? = null
            var keyboardBindings: KeyboardBindings? = null

            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                val name = reader.nextName()
                seen += name
                when (name) {
                    "protocolVersion" -> protocolVersion = readInt64(reader, name)
                    "sessionId" -> sessionId = readString(reader, name)
                    "coreId" -> coreId = readString(reader, name)
                    "coreBuildRevision" -> coreBuildRevision = readString(reader, name)
                    "corePath" -> corePath = readString(reader, name)
                    "contentPath" -> contentPath = readString(reader, name)
                    "contentHash" -> contentHash = readString(reader, name)
                    "systemDir" -> systemDir = readString(reader, name)
                    "savePath" -> savePath = readString(reader, name)
                    "candidateSavePath" -> candidateSavePath = readString(reader, name)
                    "resultPath" -> resultPath = readString(reader, name)
                    "expectedSaveSize" -> expectedSaveSize = when (reader.peek()) {
                        JsonReader.Token.NULL -> {
                            reader.nextNull<Unit>()
                            null
                        }
                        else -> readNonNegativeInt64(reader, name)
                    }
                    "video" -> video = readVideo(reader)
                    // v2 optional field (not in REQUIRED_REQUEST_FIELDS): absent = defaults.
                    "controllerBindings" -> controllerBindings = readControllerBindings(reader)
                    "keyboardBindings" -> keyboardBindings = readKeyboardBindings(reader)
                    else -> throw ProtocolException("unknown field: $name")
                }
            }
            reader.endObject()

            val missing = REQUIRED_REQUEST_FIELDS.firstOrNull { it !in seen }
            if (missing != null) throw ProtocolException("missing required field: $missing")
            val version = protocolVersion!!
            if (version != PLAYER_PROTOCOL_VERSION.toLong()) {
                throw ProtocolException("unsupported protocolVersion: $version")
            }
            return PlayerRequest(
                protocolVersion = version.toInt(),
                sessionId = sessionId!!,
                coreId = coreId!!,
                coreBuildRevision = coreBuildRevision!!,
                corePath = corePath!!,
                contentPath = contentPath!!,
                contentHash = contentHash!!,
                systemDir = systemDir!!,
                savePath = savePath!!,
                candidateSavePath = candidateSavePath!!,
                resultPath = resultPath!!,
                expectedSaveSize = expectedSaveSize,
                video = video!!,
                controllerBindings = controllerBindings,
                keyboardBindings = keyboardBindings,
            )
        }

        override fun toJson(writer: JsonWriter, value: PlayerRequest?) {
            val request = checkNotNull(value)
            writer.setIndent("  ")
            writer.setSerializeNulls(true) // the v1 schema requires explicit null fields
            writer.beginObject()
            writer.name("protocolVersion").value(request.protocolVersion.toLong())
            writer.name("sessionId").value(request.sessionId)
            writer.name("coreId").value(request.coreId)
            writer.name("coreBuildRevision").value(request.coreBuildRevision)
            writer.name("corePath").value(request.corePath)
            writer.name("contentPath").value(request.contentPath)
            writer.name("contentHash").value(request.contentHash)
            writer.name("systemDir").value(request.systemDir)
            writer.name("savePath").value(request.savePath)
            writer.name("candidateSavePath").value(request.candidateSavePath)
            writer.name("resultPath").value(request.resultPath)
            if (request.expectedSaveSize == null) {
                writer.name("expectedSaveSize").nullValue()
            } else {
                writer.name("expectedSaveSize").value(request.expectedSaveSize)
            }
            writer.name("video").beginObject()
            writer.name("fullscreen").value(request.video.fullscreen)
            writer.name("integerScaling").value(request.video.integerScaling)
            writer.name("scanlines").value(request.video.scanlines)
            writer.name("sharpFilter").value(request.video.sharpFilter)
            writer.endObject()
            // v2 optional field: written only when present (absent = player defaults), so a
            // request with no stored bindings stays byte-identical to the v1 layout plus the
            // version bump. Device entries use the sidecar's canonical shape and field order.
            if (request.controllerBindings != null) {
                writer.name("controllerBindings").beginObject()
                writeControllerBindingDevices(writer, request.controllerBindings.devices)
                writer.endObject()
            }
            request.keyboardBindings?.let { keyboard ->
                writer.name("keyboardBindings").beginObject()
                writer.name("bindings").beginArray()
                keyboard.bindings.forEach { binding ->
                    writer.beginObject()
                    writer.name("target").value(binding.target)
                    if (binding.primaryScancode == null) {
                        writer.name("primaryScancode").nullValue()
                    } else {
                        writer.name("primaryScancode").value(binding.primaryScancode.toLong())
                    }
                    if (binding.secondaryScancode == null) {
                        writer.name("secondaryScancode").nullValue()
                    } else {
                        writer.name("secondaryScancode").value(binding.secondaryScancode.toLong())
                    }
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
            }
            writer.endObject()
        }

        private fun readKeyboardBindings(reader: JsonReader): KeyboardBindings {
            reader.beginObject()
            var bindings: List<KeyboardBindingEntry>? = null
            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                when (val name = reader.nextName()) {
                    "bindings" -> {
                        reader.beginArray()
                        val entries = mutableListOf<KeyboardBindingEntry>()
                        while (reader.hasNext()) {
                            reader.beginObject()
                            var target: String? = null
                            var primarySeen = false
                            var secondarySeen = false
                            var primary: Int? = null
                            var secondary: Int? = null
                            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                                when (val field = reader.nextName()) {
                                    "target" -> target = readString(reader, field)
                                    "primaryScancode" -> {
                                        primarySeen = true
                                        primary = readNullableScancode(reader, field)
                                    }
                                    "secondaryScancode" -> {
                                        secondarySeen = true
                                        secondary = readNullableScancode(reader, field)
                                    }
                                    else -> throw ProtocolException("unknown keyboard binding field: $field")
                                }
                            }
                            reader.endObject()
                            if (target == null || !primarySeen || !secondarySeen) {
                                throw ProtocolException("incomplete keyboard binding")
                            }
                            entries += KeyboardBindingEntry(target, primary, secondary)
                        }
                        reader.endArray()
                        bindings = entries
                    }
                    else -> throw ProtocolException("unknown keyboardBindings field: $name")
                }
            }
            reader.endObject()
            val result = bindings ?: throw ProtocolException("missing keyboardBindings field: bindings")
            val expectedTargets = com.romm.desktop.controller.keyboard.KEYBOARD_TARGETS
            if (result.map(KeyboardBindingEntry::target) != expectedTargets) {
                throw ProtocolException("keyboard bindings must contain every target exactly once in order")
            }
            return KeyboardBindings(result)
        }

        private fun readNullableScancode(reader: JsonReader, name: String): Int? =
            if (reader.peek() == JsonReader.Token.NULL) {
                reader.nextNull<Unit>()
                null
            } else {
                readInt64(reader, name).also {
                    if (it !in 0..511) throw ProtocolException("$name must be in 0..511")
                }.toInt()
            }

        private fun writeControllerBindingDevices(writer: JsonWriter, devices: List<ControllerBindingDevice>) {
            writer.name("devices").beginArray()
            for (device in devices) {
                writer.beginObject()
                writer.name("guid").value(device.guid)
                writer.name("identity").beginObject()
                if (device.identity.vendorId == null) {
                    writer.name("vendorId").nullValue()
                } else {
                    writer.name("vendorId").value(device.identity.vendorId.toLong())
                }
                if (device.identity.productId == null) {
                    writer.name("productId").nullValue()
                } else {
                    writer.name("productId").value(device.identity.productId.toLong())
                }
                writer.name("descriptor").value(device.identity.descriptor)
                writer.endObject()
                writeSlotBindings(writer, "bindings", device.bindings)
                device.secondaryBindings?.let {
                    writeSlotBindings(writer, "secondaryBindings", it)
                }
                writer.endObject()
            }
            writer.endArray()
        }

        private fun writeSlotBindings(
            writer: JsonWriter,
            name: String,
            bindings: List<PlayerSlotBinding>,
        ) {
            writer.name(name).beginArray()
            for (binding in bindings) {
                    writer.beginObject()
                    writer.name("slot").value(binding.slot)
                    when (binding.type) {
                        PlayerBindingType.UNBOUND -> writer.name("type").value(PlayerBindingType.UNBOUND.wireName)
                        PlayerBindingType.BUTTON -> {
                            writer.name("type").value(PlayerBindingType.BUTTON.wireName)
                            writer.name("button").value(checkNotNull(binding.button))
                        }
                        PlayerBindingType.AXIS -> {
                            writer.name("type").value(PlayerBindingType.AXIS.wireName)
                            writer.name("axis").value(checkNotNull(binding.axis))
                        }
                        PlayerBindingType.AXIS_DIRECTION -> {
                            writer.name("type").value(PlayerBindingType.AXIS_DIRECTION.wireName)
                            writer.name("axis").value(checkNotNull(binding.axis))
                            writer.name("polarity").value(checkNotNull(binding.polarity).toLong())
                        }
                    }
                    writer.endObject()
            }
            writer.endArray()
        }
    }

    // ------------------------------------------------------------------ result adapter

    private class ResultAdapter : JsonAdapter<PlayerResult>() {

        override fun fromJson(reader: JsonReader): PlayerResult? {
            reader.beginObject()
            val seen = mutableSetOf<String>()
            var protocolVersion: Long? = null
            var sessionId: String? = null
            var exitKind: PlayerExitKind? = null
            var checkpointWritten: Boolean? = null
            var candidateSavePath: String? = null
            var saveHash: String? = null
            var saveSize: Long? = null
            var frames: Long? = null
            var audioUnderrunFrames: Long? = null
            var audioOverrunFrames: Long? = null
            var errorCode: String? = null
            var errorMessage: String? = null
            var video: VideoSettings? = null

            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                val name = reader.nextName()
                seen += name
                when (name) {
                    "protocolVersion" -> protocolVersion = readInt64(reader, name)
                    "sessionId" -> sessionId = readString(reader, name)
                    "exitKind" -> {
                        val wire = readString(reader, name)
                        exitKind = PlayerExitKind.fromWireName(wire)
                            ?: throw ProtocolException("unknown exitKind: $wire")
                    }
                    "checkpointWritten" -> checkpointWritten = readBoolean(reader, name)
                    "candidateSavePath" -> candidateSavePath = readString(reader, name)
                    "saveHash" -> saveHash = readNullableString(reader, name)
                    "saveSize" -> saveSize = when (reader.peek()) {
                        JsonReader.Token.NULL -> {
                            reader.nextNull<Unit>()
                            null
                        }
                        else -> readNonNegativeInt64(reader, name)
                    }
                    "frames" -> frames = readNonNegativeInt64(reader, name)
                    "audioUnderrunFrames" -> audioUnderrunFrames = readNonNegativeInt64(reader, name)
                    "audioOverrunFrames" -> audioOverrunFrames = readNonNegativeInt64(reader, name)
                    "errorCode" -> errorCode = readNullableString(reader, name)
                    "errorMessage" -> errorMessage = readNullableString(reader, name)
                    "video" -> video = readVideo(reader)
                    else -> throw ProtocolException("unknown field: $name")
                }
            }
            reader.endObject()

            val missing = REQUIRED_RESULT_FIELDS.firstOrNull { it !in seen }
            if (missing != null) throw ProtocolException("missing required field: $missing")
            val version = protocolVersion!!
            if (version != PLAYER_PROTOCOL_VERSION.toLong()) {
                throw ProtocolException("unsupported protocolVersion: $version")
            }
            return PlayerResult(
                protocolVersion = version.toInt(),
                sessionId = sessionId!!,
                exitKind = exitKind!!,
                checkpointWritten = checkpointWritten!!,
                candidateSavePath = candidateSavePath!!,
                saveHash = saveHash,
                saveSize = saveSize,
                frames = frames!!,
                audioUnderrunFrames = audioUnderrunFrames!!,
                audioOverrunFrames = audioOverrunFrames!!,
                errorCode = errorCode,
                errorMessage = errorMessage,
                video = video,
            )
        }

        override fun toJson(writer: JsonWriter, value: PlayerResult?) {
            val result = checkNotNull(value)
            writer.setIndent("  ")
            writer.setSerializeNulls(true) // the v1 schema requires explicit null fields
            writer.beginObject()
            writer.name("protocolVersion").value(result.protocolVersion.toLong())
            writer.name("sessionId").value(result.sessionId)
            writer.name("exitKind").value(result.exitKind.wireName)
            writer.name("checkpointWritten").value(result.checkpointWritten)
            writer.name("candidateSavePath").value(result.candidateSavePath)
            if (result.saveHash == null) {
                writer.name("saveHash").nullValue()
            } else {
                writer.name("saveHash").value(result.saveHash)
            }
            if (result.saveSize == null) {
                writer.name("saveSize").nullValue()
            } else {
                writer.name("saveSize").value(result.saveSize)
            }
            writer.name("frames").value(result.frames)
            writer.name("audioUnderrunFrames").value(result.audioUnderrunFrames)
            writer.name("audioOverrunFrames").value(result.audioOverrunFrames)
            if (result.errorCode == null) {
                writer.name("errorCode").nullValue()
            } else {
                writer.name("errorCode").value(result.errorCode)
            }
            if (result.errorMessage == null) {
                writer.name("errorMessage").nullValue()
            } else {
                writer.name("errorMessage").value(result.errorMessage)
            }
            result.video?.let { video ->
                writer.name("video").beginObject()
                writer.name("fullscreen").value(video.fullscreen)
                writer.name("integerScaling").value(video.integerScaling)
                writer.name("scanlines").value(video.scanlines)
                writer.name("sharpFilter").value(video.sharpFilter)
                writer.endObject()
            }
            writer.endObject()
        }
    }

    // ------------------------------------------------------------------ token readers

    /**
     * Reads a 64-bit integer, rejecting non-integer numerics ("1.5", "2e0") exactly like the
     * C++ `is_number_integer()` check: the raw literal is read as text and must be a plain
     * (optionally negative) base-10 integer within the [Long] range.
     */
    /** Shared with [ControllerBindingSidecarCodec] (same int64 strictness as the C++ parser). */
    internal fun readInt64(reader: JsonReader, name: String): Long {
        if (reader.peek() != JsonReader.Token.NUMBER) {
            throw ProtocolException("$name must be an integer")
        }
        val literal = reader.nextString()
        if (!INT64_LITERAL.matches(literal)) {
            throw ProtocolException("$name must be an integer")
        }
        return literal.toLongOrNull() ?: throw ProtocolException("$name is out of int64 range: $literal")
    }

    private fun readNonNegativeInt64(reader: JsonReader, name: String): Long {
        val value = readInt64(reader, name)
        if (value < 0) throw ProtocolException("$name must be a non-negative integer")
        return value
    }

    private fun readString(reader: JsonReader, name: String): String {
        if (reader.peek() != JsonReader.Token.STRING) {
            throw ProtocolException("field must be a string: $name")
        }
        return reader.nextString()
    }

    private fun readNullableString(reader: JsonReader, name: String): String? = when (reader.peek()) {
        JsonReader.Token.NULL -> {
            reader.nextNull<Unit>()
            null
        }
        else -> readString(reader, name)
    }

    private fun readBoolean(reader: JsonReader, name: String): Boolean {
        if (reader.peek() != JsonReader.Token.BOOLEAN) {
            throw ProtocolException("field must be a boolean: $name")
        }
        return reader.nextBoolean()
    }

    private fun readVideo(reader: JsonReader): VideoSettings {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            throw ProtocolException("video must be an object")
        }
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var fullscreen: Boolean? = null
        var integerScaling: Boolean? = null
        var scanlines: Boolean? = null
        var sharpFilter: Boolean? = null
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            val name = reader.nextName()
            if (name !in VIDEO_FIELDS) throw ProtocolException("unknown video field: $name")
            seen += name
            when (name) {
                "fullscreen" -> fullscreen = readBoolean(reader, name)
                "integerScaling" -> integerScaling = readBoolean(reader, name)
                "scanlines" -> scanlines = readBoolean(reader, name)
                "sharpFilter" -> sharpFilter = readBoolean(reader, name)
            }
        }
        reader.endObject()
        val missing = VIDEO_FIELDS.firstOrNull { it !in seen }
        if (missing != null) throw ProtocolException("missing video field: $missing")
        return VideoSettings(fullscreen!!, integerScaling!!, scanlines!!, sharpFilter!!)
    }

    // ------------------------------------------------------------------ v2 controllerBindings

    /**
     * Reads the v2 request's optional `controllerBindings` object: exactly one known field,
     * `devices` (an array of sidecar-shaped device entries). Mirrors C++ `parseControllerBindings`.
     */
    private fun readControllerBindings(reader: JsonReader): ControllerBindings {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            throw ProtocolException("controllerBindings must be an object")
        }
        reader.beginObject()
        var devices: List<ControllerBindingDevice>? = null
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            when (val name = reader.nextName()) {
                "devices" -> devices = readControllerBindingDevices(reader)
                else -> throw ProtocolException("unknown field: $name")
            }
        }
        reader.endObject()
        if (devices == null) throw ProtocolException("missing controllerBindings field: devices")
        return ControllerBindings(devices)
    }

    /** Reads a `devices` array (may be empty). Shared with [ControllerBindingSidecarCodec]. */
    internal fun readControllerBindingDevices(reader: JsonReader): List<ControllerBindingDevice> {
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            throw ProtocolException("controllerBindings.devices must be an array")
        }
        reader.beginArray()
        val devices = mutableListOf<ControllerBindingDevice>()
        while (reader.peek() != JsonReader.Token.END_ARRAY) {
            devices += readControllerBindingDevice(reader)
        }
        reader.endArray()
        return devices
    }

    private fun readControllerBindingDevice(reader: JsonReader): ControllerBindingDevice {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            throw ProtocolException("controllerBindings device must be an object")
        }
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var guid: String? = null
        var identity: ControllerBindingIdentity? = null
        var bindings: List<PlayerSlotBinding>? = null
        var secondaryBindings: List<PlayerSlotBinding>? = null
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            val name = reader.nextName()
            seen += name
            when (name) {
                "guid" -> guid = readString(reader, name)
                "identity" -> identity = readControllerBindingIdentity(reader)
                "bindings" -> bindings = readSlotBindings(reader)
                "secondaryBindings" -> secondaryBindings = readSlotBindings(reader)
                else -> throw ProtocolException("unknown field: $name")
            }
        }
        reader.endObject()
        for (field in DEVICE_FIELDS) {
            if (field !in seen) throw ProtocolException("missing controllerBindings device field: $field")
        }
        return ControllerBindingDevice(
            checkNotNull(guid),
            checkNotNull(identity),
            checkNotNull(bindings),
            secondaryBindings,
        )
    }

    private fun readControllerBindingIdentity(reader: JsonReader): ControllerBindingIdentity {
        if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
            throw ProtocolException("controllerBindings identity must be an object")
        }
        reader.beginObject()
        val seen = mutableSetOf<String>()
        var vendorId: Int? = null
        var productId: Int? = null
        var descriptor: String? = null
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            val name = reader.nextName()
            seen += name
            when (name) {
                "vendorId" -> vendorId = readNullableNonNegativeInt(reader, name)
                "productId" -> productId = readNullableNonNegativeInt(reader, name)
                "descriptor" -> descriptor = readString(reader, name)
                else -> throw ProtocolException("unknown field: $name")
            }
        }
        reader.endObject()
        for (field in IDENTITY_FIELDS) {
            if (field !in seen) throw ProtocolException("missing controllerBindings identity field: $field")
        }
        return ControllerBindingIdentity(vendorId, productId, checkNotNull(descriptor))
    }

    /**
     * Reads a `bindings` array: the legacy 12 or current 16 RetroPad slots, each once, in order.
     * the exact field set for its declared type (mirrors C++ `parseBindingEntry`). The union of
     * all entry fields is checked per token; the declared type then pins the subset.
     */
    internal fun readSlotBindings(reader: JsonReader): List<PlayerSlotBinding> {
        if (reader.peek() != JsonReader.Token.BEGIN_ARRAY) {
            throw ProtocolException("bindings must be an array")
        }
        reader.beginArray()
        val bindings = mutableListOf<PlayerSlotBinding>()
        val seenSlots = mutableSetOf<String>()
        while (reader.peek() != JsonReader.Token.END_ARRAY) {
            if (reader.peek() != JsonReader.Token.BEGIN_OBJECT) {
                throw ProtocolException("binding entry must be an object")
            }
            reader.beginObject()
            var slot: String? = null
            var type: PlayerBindingType? = null
            var button: String? = null
            var axis: String? = null
            var polarity: Int? = null
            while (reader.peek() != JsonReader.Token.END_OBJECT) {
                val name = reader.nextName()
                when (name) {
                    "slot" -> slot = readString(reader, name)
                    "type" -> {
                        val wire = readString(reader, name)
                        type = PlayerBindingType.fromWireName(wire)
                            ?: throw ProtocolException("unknown binding type: $wire")
                    }
                    "button" -> button = readString(reader, name)
                    "axis" -> axis = readString(reader, name)
                    "polarity" -> {
                        val value = readInt64(reader, name)
                        if (value != -1L && value != 1L) {
                            throw ProtocolException("binding polarity must be -1 or 1")
                        }
                        polarity = value.toInt()
                    }
                    else -> throw ProtocolException("unknown field: $name")
                }
            }
            reader.endObject()

            val slotName = checkNotNull(slot) { "missing binding slot" }
            if (slotName !in RETRO_PAD_SLOT_NAMES) {
                throw ProtocolException("unknown binding slot: $slotName")
            }
            if (!seenSlots.add(slotName)) {
                throw ProtocolException("duplicate binding slot: $slotName")
            }
            val bindingType = checkNotNull(type) { "missing binding type" }
            when (bindingType) {
                PlayerBindingType.UNBOUND -> {
                    if (button != null || axis != null || polarity != null) {
                        throw ProtocolException("unbound binding must not carry button/axis/polarity")
                    }
                }
                PlayerBindingType.BUTTON -> {
                    if (axis != null || polarity != null) {
                        throw ProtocolException("button binding must not carry axis/polarity")
                    }
                    val buttonName = button ?: throw ProtocolException("missing button")
                    if (buttonName !in PAD_BUTTON_NAMES) {
                        throw ProtocolException("unknown pad button: $buttonName")
                    }
                }
                PlayerBindingType.AXIS -> {
                    if (button != null || polarity != null) {
                        throw ProtocolException("axis binding must not carry button/polarity")
                    }
                    val axisName = axis ?: throw ProtocolException("missing axis")
                    if (axisName !in PAD_AXIS_NAMES) {
                        throw ProtocolException("unknown pad axis: $axisName")
                    }
                }
                PlayerBindingType.AXIS_DIRECTION -> {
                    if (button != null) {
                        throw ProtocolException("axis_direction binding must not carry button")
                    }
                    val axisName = axis ?: throw ProtocolException("missing axis")
                    if (axisName !in PAD_AXIS_NAMES) {
                        throw ProtocolException("unknown pad axis: $axisName")
                    }
                    requireNotNull(polarity) { "missing polarity" }
                }
            }
            bindings += PlayerSlotBinding(slotName, bindingType, button, axis, polarity)
        }
        reader.endArray()

        if (bindings.size != LEGACY_RETRO_PAD_SLOT_COUNT &&
            bindings.size != RETRO_PAD_SLOT_NAMES.size
        ) {
            throw ProtocolException(
                "bindings must carry exactly $LEGACY_RETRO_PAD_SLOT_COUNT or " +
                    "${RETRO_PAD_SLOT_NAMES.size} entries",
            )
        }
        // Entries must arrive in slot order (the canonical producers — the sidecar and the
        // desktop serializer — both emit all entries in order); a gap is rejected here.
        bindings.forEachIndexed { index, binding ->
            if (binding.slot != RETRO_PAD_SLOT_NAMES[index]) {
                throw ProtocolException("binding slot out of order or duplicate: ${binding.slot}")
            }
        }
        return bindings
    }

    /** null or non-negative integer narrowed to [Int] (vendor/product IDs). */
    private fun readNullableNonNegativeInt(reader: JsonReader, name: String): Int? = when (reader.peek()) {
        JsonReader.Token.NULL -> {
            reader.nextNull<Unit>()
            null
        }
        else -> {
            val value = readNonNegativeInt64(reader, name)
            if (value > Int.MAX_VALUE) throw ProtocolException("$name is out of int range")
            value.toInt()
        }
    }

    private val REQUIRED_REQUEST_FIELDS = listOf(
        "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
        "corePath", "contentPath", "contentHash", "systemDir",
        "savePath", "candidateSavePath", "resultPath",
        "expectedSaveSize", "video",
    )

    private val REQUIRED_RESULT_FIELDS = listOf(
        "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
        "candidateSavePath", "saveHash", "saveSize", "frames",
        "audioUnderrunFrames", "audioOverrunFrames", "errorCode",
        "errorMessage",
    )

    private val VIDEO_FIELDS = setOf("fullscreen", "integerScaling", "scanlines", "sharpFilter")

    private val DEVICE_FIELDS = setOf("guid", "identity", "bindings")

    private val IDENTITY_FIELDS = setOf("vendorId", "productId", "descriptor")

    private val INT64_LITERAL = Regex("-?[0-9]+")
}

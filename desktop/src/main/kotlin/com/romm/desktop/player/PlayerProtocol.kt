package com.romm.desktop.player

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import java.io.IOException

/**
 * The Kotlin side of the v1 player launch request/result file protocol.
 *
 * This is a security boundary with the `rommulus_player` process (plans/LINUX_X64.md
 * §12.2/§12.3) and must match the platform-neutral C++ implementation in
 * `native/player/src/protocol.cpp` EXACTLY: same field names, same types, same strictness.
 * The v1 schema deliberately carries no origin, username, token, server save ID, or upload
 * URL; both parsers reject unknown fields so a secret can never ride along via a schema typo.
 *
 * Implemented as strict Moshi [JsonAdapter]s: parsing walks the [JsonReader] token by token
 * (non-lenient mode), and serialization writes the C++ canonical field order with 2-space
 * indent (nlohmann `dump(2)` equivalent).
 */

/** The only protocol version this build understands (mirrors C++ `kProtocolVersion`). */
const val PLAYER_PROTOCOL_VERSION: Int = 1

/** Video settings block of a v1 launch request (§12.2). All three fields are required on the wire. */
data class VideoSettings(
    val fullscreen: Boolean = false,
    val integerScaling: Boolean = false,
    val scanlines: Boolean = false,
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
 * Launch request v1 (§12.2). Every field is required on the wire; [contentHash] may be the
 * empty string (the player then skips hash verification) and [expectedSaveSize] may be null.
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
            writer.endObject()
            writer.endObject()
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
            writer.endObject()
        }
    }

    // ------------------------------------------------------------------ token readers

    /**
     * Reads a 64-bit integer, rejecting non-integer numerics ("1.5", "2e0") exactly like the
     * C++ `is_number_integer()` check: the raw literal is read as text and must be a plain
     * (optionally negative) base-10 integer within the [Long] range.
     */
    private fun readInt64(reader: JsonReader, name: String): Long {
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
        while (reader.peek() != JsonReader.Token.END_OBJECT) {
            val name = reader.nextName()
            if (name !in VIDEO_FIELDS) throw ProtocolException("unknown video field: $name")
            seen += name
            when (name) {
                "fullscreen" -> fullscreen = readBoolean(reader, name)
                "integerScaling" -> integerScaling = readBoolean(reader, name)
                "scanlines" -> scanlines = readBoolean(reader, name)
            }
        }
        reader.endObject()
        val missing = VIDEO_FIELDS.firstOrNull { it !in seen }
        if (missing != null) throw ProtocolException("missing video field: $missing")
        return VideoSettings(fullscreen!!, integerScaling!!, scanlines!!)
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

    private val VIDEO_FIELDS = setOf("fullscreen", "integerScaling", "scanlines")

    private val INT64_LITERAL = Regex("-?[0-9]+")
}

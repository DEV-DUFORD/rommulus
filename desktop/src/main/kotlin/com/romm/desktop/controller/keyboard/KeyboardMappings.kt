package com.romm.desktop.controller.keyboard

import com.romm.androidtv.controller.config.BindingSlot
import com.romm.androidtv.controller.config.CoreControllerProfiles
import com.romm.androidtv.controller.config.InputKind
import com.romm.androidtv.controller.model.LogicalControl
import com.romm.androidtv.storage.ports.ControllerBindingStore
import com.romm.androidtv.storage.records.ControllerBindingRecord
import com.romm.desktop.player.KeyboardBindingEntry
import com.romm.desktop.player.KeyboardBindings
import com.romm.desktop.player.PlayerProtocol
import com.romm.desktop.player.RetroPadControlMapping
import com.squareup.moshi.JsonReader
import okio.Buffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

const val KEYBOARD_BINDINGS_SIDECAR_FILE_NAME = "keyboard-bindings.json"
private const val KEYBOARD_PLAYER_INDEX = -1
private const val KEYBOARD_TYPE = "KEYBOARD"
private const val KEYBOARD_UNMAPPED_TYPE = "KEYBOARD_UNMAPPED"

val KEYBOARD_TARGETS = listOf(
    "a", "b", "x", "y", "select", "start", "left_shoulder", "right_shoulder",
    "dpad_up", "dpad_down", "dpad_left", "dpad_right", "left_trigger", "right_trigger",
    "left_stick", "right_stick",
    "left_x_negative", "left_x_positive", "left_y_negative", "left_y_positive",
    "right_x_negative", "right_x_positive", "right_y_negative", "right_y_positive",
)

data class KeyboardMappingRow(
    val target: String,
    val label: String,
    val primaryScancode: Int?,
    val secondaryScancode: Int?,
)

class KeyboardMappingRepository(private val store: ControllerBindingStore) {
    private val flows = mutableMapOf<String, MutableStateFlow<List<KeyboardMappingRow>>>()

    @Synchronized
    fun observe(coreId: String): StateFlow<List<KeyboardMappingRow>> =
        flows.getOrPut(coreId) { MutableStateFlow(loadRows(coreId)) }.asStateFlow()

    fun launchBindings(coreId: String): KeyboardBindings? {
        val stored = store.loadForPlayer(coreId, KEYBOARD_PLAYER_INDEX)
        if (stored.isEmpty()) return null
        val values = stored.associateBy { it.controlId to it.bindingSlot }
        return KeyboardBindings(KEYBOARD_TARGETS.map { target ->
            val defaults = defaultScancodes(coreId, target)
            KeyboardBindingEntry(
                target,
                effective(values, target, BindingSlot.PRIMARY.index, defaults.first),
                effective(values, target, BindingSlot.SECONDARY.index, defaults.second),
            )
        })
    }

    fun replaceAll(coreId: String, bindings: KeyboardBindings) {
        require(bindings.bindings.map { it.target } == KEYBOARD_TARGETS)
        val records = bindings.bindings.flatMap { entry ->
            listOf(
                record(coreId, entry.target, BindingSlot.PRIMARY.index, entry.primaryScancode),
                record(coreId, entry.target, BindingSlot.SECONDARY.index, entry.secondaryScancode),
            )
        }
        store.upsertAll(records).getOrThrow()
        refresh(coreId)
    }

    fun set(coreId: String, target: String, slot: BindingSlot, scancode: Int?) {
        require(target in KEYBOARD_TARGETS)
        store.upsert(record(coreId, target, slot.index, scancode)).getOrThrow()
        refresh(coreId)
    }

    fun reset(coreId: String) {
        store.deletePlayer(coreId, KEYBOARD_PLAYER_INDEX).getOrThrow()
        refresh(coreId)
    }

    fun clear(coreId: String) {
        store.upsertAll(KEYBOARD_TARGETS.flatMap { target ->
            BindingSlot.entries.map { slot -> record(coreId, target, slot.index, null) }
        }).getOrThrow()
        refresh(coreId)
    }

    private fun loadRows(coreId: String): List<KeyboardMappingRow> {
        val stored = store.loadForPlayer(coreId, KEYBOARD_PLAYER_INDEX)
            .associateBy { it.controlId to it.bindingSlot }
        return rowsForCore(coreId).map { (target, label) ->
            val defaults = defaultScancodes(coreId, target)
            KeyboardMappingRow(
                target = target,
                label = label,
                primaryScancode = effective(stored, target, BindingSlot.PRIMARY.index, defaults.first),
                secondaryScancode = effective(stored, target, BindingSlot.SECONDARY.index, defaults.second),
            )
        }
    }

    private fun refresh(coreId: String) {
        flows[coreId]?.value = loadRows(coreId)
    }

    private fun record(coreId: String, target: String, slot: Int, scancode: Int?) =
        ControllerBindingRecord(
            coreId = coreId,
            playerIndex = KEYBOARD_PLAYER_INDEX,
            controlId = target,
            bindingSlot = slot,
            bindingType = if (scancode == null) KEYBOARD_UNMAPPED_TYPE else KEYBOARD_TYPE,
            inputCode = scancode ?: 0,
            polarity = null,
        )

    private fun decode(record: ControllerBindingRecord?): Int? =
        record?.takeIf { it.bindingType == KEYBOARD_TYPE }?.inputCode

    private fun effective(
        records: Map<Pair<String, Int>, ControllerBindingRecord>,
        target: String,
        slot: Int,
        default: Int?,
    ): Int? {
        val record = records[target to slot] ?: return default
        return decode(record)
    }
}

fun rowsForCore(coreId: String): List<Pair<String, String>> {
    val profile = CoreControllerProfiles.byCoreId(coreId) ?: return emptyList()
    return buildList {
        profile.controls.forEach { control ->
            if (control.inputKind == InputKind.ANALOG_STICK) {
                when (control.target) {
                    LogicalControl.AXIS_LX -> {
                        add("left_x_negative" to "Left Stick Left")
                        add("left_x_positive" to "Left Stick Right")
                    }
                    LogicalControl.AXIS_LY -> {
                        add("left_y_negative" to "Left Stick Up")
                        add("left_y_positive" to "Left Stick Down")
                    }
                    LogicalControl.AXIS_RX -> {
                        add("right_x_negative" to "Right Stick Left")
                        add("right_x_positive" to "Right Stick Right")
                    }
                    LogicalControl.AXIS_RY -> {
                        add("right_y_negative" to "Right Stick Up")
                        add("right_y_positive" to "Right Stick Down")
                    }
                    else -> Unit
                }
            } else {
                RetroPadControlMapping.SLOT_TO_CONTROL.entries
                    .firstOrNull { it.value == control.target }
                    ?.let { add(it.key to control.label) }
            }
        }
    }.distinctBy { it.first }
}

private fun defaultScancodes(coreId: String, target: String): Pair<Int?, Int?> {
    if (coreId == "mupen64plus_next") {
        return when (target) {
            "dpad_up", "dpad_down", "dpad_left", "dpad_right" -> null to null
            "left_x_negative" -> 4 to 80
            "left_x_positive" -> 7 to 79
            "left_y_negative" -> 26 to 82
            "left_y_positive" -> 22 to 81
            "b" -> 40 to 44
            "y" -> 225 to 229
            "a" -> 27 to null
            "x" -> 29 to null
            else -> genericDefaultScancodes(target)
        }
    }
    return genericDefaultScancodes(target)
}

private fun genericDefaultScancodes(target: String): Pair<Int?, Int?> = when (target) {
    "dpad_up" -> 26 to 82
    "dpad_down" -> 22 to 81
    "dpad_left" -> 4 to 80
    "dpad_right" -> 7 to 79
    "a" -> 40 to 44
    "b" -> 225 to 229
    "x" -> 27 to null
    "y" -> 29 to null
    "select" -> 224 to null
    "start" -> 228 to null
    else -> null to null
}

object KeyboardBindingSidecarCodec {
    fun parse(json: String): Result<KeyboardBindings> = runCatching {
        val reader = JsonReader.of(Buffer().writeUtf8(json))
        reader.beginObject()
        var version: Int? = null
        var bindings: List<KeyboardBindingEntry>? = null
        while (reader.hasNext()) {
            when (val name = reader.nextName()) {
                "protocolVersion" -> version = reader.nextInt()
                "bindings" -> bindings = readBindings(reader)
                else -> throw PlayerProtocol.ProtocolException("unknown keyboard sidecar field: $name")
            }
        }
        reader.endObject()
        if (version != 1) throw PlayerProtocol.ProtocolException("unsupported keyboard sidecar version: $version")
        KeyboardBindings(bindings ?: throw PlayerProtocol.ProtocolException("missing keyboard sidecar bindings"))
            .also { require(it.bindings.map(KeyboardBindingEntry::target) == KEYBOARD_TARGETS) }
    }

    private fun readBindings(reader: JsonReader): List<KeyboardBindingEntry> {
        val result = mutableListOf<KeyboardBindingEntry>()
        reader.beginArray()
        while (reader.hasNext()) {
            reader.beginObject()
            var target: String? = null
            var primary: Int? = null
            var secondary: Int? = null
            var primarySeen = false
            var secondarySeen = false
            while (reader.hasNext()) {
                when (val name = reader.nextName()) {
                    "target" -> target = reader.nextString()
                    "primaryScancode" -> {
                        primarySeen = true
                        primary = nullableInt(reader)
                    }
                    "secondaryScancode" -> {
                        secondarySeen = true
                        secondary = nullableInt(reader)
                    }
                    else -> throw PlayerProtocol.ProtocolException("unknown keyboard binding field: $name")
                }
            }
            reader.endObject()
            require(target != null && primarySeen && secondarySeen)
            result += KeyboardBindingEntry(target, primary, secondary)
        }
        reader.endArray()
        return result
    }

    private fun nullableInt(reader: JsonReader): Int? =
        if (reader.peek() == JsonReader.Token.NULL) {
            reader.nextNull<Unit>()
            null
        } else {
            reader.nextInt().also { require(it in 0..511) }
        }
}

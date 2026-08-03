package com.romm.androidtv.emulation.nativehost

/**
 * One input descriptor advertised by a Libretro core via
 * `RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS`, as parsed from the native
 * snapshot string.
 *
 * Mirrors the native `retro_input_descriptor` struct fields:
 * [port], [device], [index], [id], plus the human-readable [description].
 */
data class RetroInputDescriptor(
    val port: Int,
    val device: Int,
    val index: Int,
    val id: Int,
    val description: String,
)

/**
 * Parses the native input-descriptors snapshot returned by
 * [NativeLibretroHost.nativeGetInputDescriptorsSnapshot].
 *
 * Wire format: one line per descriptor, `port|device|index|id|description`,
 * lines joined with `\n`. The native side replaces any `|` or newline inside
 * a core's description with a space so the delimiter can never collide.
 *
 * An empty string (no session, or no descriptors set yet) parses to an empty
 * list. Lines with the wrong field count are skipped defensively rather than
 * crashing the caller.
 */
object RetroInputDescriptorSnapshot {

    fun parse(snapshot: String): List<RetroInputDescriptor> {
        if (snapshot.isEmpty()) return emptyList()

        val result = mutableListOf<RetroInputDescriptor>()
        for (line in snapshot.split('\n')) {
            val fields = line.split('|')
            if (fields.size != 5) continue
            val port = fields[0].toIntOrNull() ?: continue
            val device = fields[1].toIntOrNull() ?: continue
            val index = fields[2].toIntOrNull() ?: continue
            val id = fields[3].toIntOrNull() ?: continue
            result += RetroInputDescriptor(port, device, index, id, fields[4])
        }
        return result
    }
}

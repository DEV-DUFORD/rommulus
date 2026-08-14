package com.romm.androidtv.library

/**
 * One open-source-software notice: a human-readable component name plus its
 * license text. Aggregated by [LicensesRepository] and rendered by the Settings
 * "View Licenses" screen.
 */
data class LicenseNotice(
    val name: String,
    val text: String,
)

/**
 * Pure parsing for the two sources of license notices:
 *
 * 1. Google's `oss-licenses-plugin` output (raw resources `third_party_licenses` +
 *    `third_party_license_metadata`), which covers every Gradle/transitive dependency.
 *    The metadata file is lines of `offset:length <name>` indexing into the text blob.
 *
 * 2. The checked-in vendored-core asset (`assets/licenses/libretro.txt`), which the
 *    plugin cannot see (native, non-Maven code). Sections are delimited by
 *    `=== <name> ===` headers (see `scripts` / the generator invocation in the README).
 *
 * Keeping these parse functions pure (no Android types) lets them be unit-tested on the JVM.
 */
object LicenseNotices {

    const val VENDORED_ASSET = "licenses/libretro.txt"

    private val METADATA_LINE = Regex("""^(\d+):(\d+) (.+)$""")

    private val VENDORED_HEADER = Regex("""^=== (.+) ===$""")

    /** Parse `offset:length name` metadata lines against [licensesText] back into notices. */
    fun parsePluginMetadata(metadata: String, licensesText: String): List<LicenseNotice> {
        return metadata.lineSequence()
            .mapNotNull { line -> METADATA_LINE.matchEntire(line.trim()) }
            .mapNotNull { m ->
                val start = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val length = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                val name = m.groupValues[3].trim()
                if (name.isEmpty() || start < 0 || length < 0 || start + length > licensesText.length) {
                    return@mapNotNull null
                }
                val text =
                    licensesText.substring(start, start + length).trim()
                if (text.isEmpty()) null else LicenseNotice(name, text)
            }
            .toList()
    }

    /**
     * Parse the `=== <name> ===`-delimited vendored notice asset. A trailing section with
     * no header is ignored (defensive against a stray EOF line).
     */
    fun parseVendored(vendoredText: String): List<LicenseNotice> {
        val notices = mutableListOf<LicenseNotice>()
        var currentName: String? = null
        val body = StringBuilder()
        vendoredText.lineSequence().forEach { line ->
            val header = VENDORED_HEADER.matchEntire(line.trim())
            if (header != null) {
                currentName?.let { name ->
                    val text = body.toString().trim()
                    if (text.isNotEmpty()) notices.add(LicenseNotice(name, text))
                }
                currentName = header.groupValues[1].trim()
                body.setLength(0)
            } else {
                currentName?.let { body.append(line).append('\n') }
            }
        }
        currentName?.let { name ->
            val text = body.toString().trim()
            if (text.isNotEmpty()) notices.add(LicenseNotice(name, text))
        }
        return notices
    }
}
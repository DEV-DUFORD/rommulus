package com.romm.androidtv.cache

import java.io.File

/**
 * Shared result type and entry-path safety checks for RomM archive content
 * that turns out to be a compressed container rather than raw ROM bytes
 * (LIBRETRO_REFACTOR.md section 10: "For ZIP content, reject absolute paths,
 * `..`, symlinks, duplicate canonical paths, excessive entry counts, and
 * decompression ratios"). [ZipArchiveExtractor] and [SevenZArchiveExtractor]
 * both extract into a caller-provided empty directory and both apply these
 * same checks, so the rules can't drift between formats.
 *
 * Neither extractor ever honors an archive's own symlink flag: every entry is
 * always written out as the bytes of a brand-new regular file that this code
 * creates, so a "symlink" entry can at worst waste space — it can never
 * actually create a filesystem symlink or otherwise redirect a later read.
 */
sealed interface ArchiveExtractionOutcome {
    /** Exactly one usable entry was found and extracted to [file]. */
    data class Success(val file: File) : ArchiveExtractionOutcome

    /** More than one non-directory entry was present; initial support only handles a single ROM entry per archive. */
    data class MultipleEntries(val count: Int) : ArchiveExtractionOutcome

    /** The archive was empty, malformed, or tripped a section-10 safety check. [reason] is human-readable. */
    data class Rejected(val reason: String) : ArchiveExtractionOutcome
}

/** Hard safety caps — deliberately conservative for handheld/early-generation ROM sizes (section 10 zip-bomb protection). */
internal data class ArchiveSafetyLimits(
    val maxEntries: Int = 4096,
    val maxUncompressedBytes: Long = 512L * 1024 * 1024, // 512 MiB
    val maxCompressionRatio: Long = 200L,
    val copyBufferBytes: Int = 64 * 1024,
) {
    companion object {
        val DEFAULT = ArchiveSafetyLimits()
    }
}

/**
 * Validates one archive entry's name against every entry seen so far for the
 * same extraction, per section 10. Returns a human-readable rejection reason,
 * or null if [name] is safe to extract under [destinationDir]. On success,
 * the entry's canonical destination path is added to [seenCanonicalPaths] so
 * later duplicate/collision checks see it.
 */
internal fun validateArchiveEntryName(
    name: String,
    destinationDir: File,
    seenCanonicalPaths: MutableSet<String>,
): String? {
    if (name.isBlank()) {
        return "archive entry with an empty name"
    }
    // Reject absolute paths and any ".." traversal component outright, independent of
    // canonicalization, so a crafted entry never gets a chance to resolve outside destinationDir.
    if (name.startsWith("/") || name.startsWith("\\") || name.contains("..")) {
        return "unsafe entry path: $name"
    }
    if (name.length >= 2 && name[1] == ':') {
        // Windows-style drive-letter absolute path (e.g. "C:\...").
        return "unsafe entry path: $name"
    }

    val canonicalDestRoot = destinationDir.canonicalFile
    val candidate = File(destinationDir, name)
    val canonicalCandidate = candidate.canonicalFile
    if (canonicalCandidate != canonicalDestRoot &&
        !canonicalCandidate.path.startsWith(canonicalDestRoot.path + File.separator)
    ) {
        return "entry resolves outside the destination directory: $name"
    }
    if (!seenCanonicalPaths.add(canonicalCandidate.path)) {
        return "duplicate canonical path in archive: $name"
    }
    return null
}

/** Derives a safe extracted-file name from the archive entry's own extension, e.g. "rom.gb". */
internal fun safeExtractedFileName(entryName: String): String {
    val extension = entryName.substringAfterLast('.', missingDelimiterValue = "")
    val safeExtension = extension.filter { it.isLetterOrDigit() }.take(8)
    return if (safeExtension.isNotEmpty()) "rom.$safeExtension" else "rom"
}

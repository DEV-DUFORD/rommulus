package com.romm.androidtv.cache

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.io.File
import java.io.IOException

/**
 * Secure 7z extraction for RomM content whose "single file" entry is
 * actually a 7z archive containing the raw ROM bytes (LIBRETRO_REFACTOR.md
 * section 10 — the same rules that apply to ZIP apply here). Backed by
 * Apache Commons Compress (Apache-2.0) + XZ for Java (public domain, LZMA2
 * codec support) rather than a hand-rolled decoder.
 */
object SevenZArchiveExtractor {

    /**
     * Extracts the sole ROM-like entry from [archiveFile] into
     * [destinationDir], which must already exist and be empty. Never
     * partially populates [destinationDir] on any failure path — partial
     * output is deleted before returning.
     */
    @Suppress("DEPRECATION") // SevenZFile(File) / getNextEntry() sequential API: simplest correct
    // way to read a single entry without relying on random-access support every 7z archive has.
    internal fun extractSingleEntry(
        archiveFile: File,
        destinationDir: File,
        limits: ArchiveSafetyLimits = ArchiveSafetyLimits.DEFAULT,
    ): ArchiveExtractionOutcome {
        val sevenZFile = try {
            SevenZFile(archiveFile)
        } catch (e: Exception) {
            // Commons Compress throws IOException for corrupt archives and various runtime
            // exceptions (e.g. unsupported/unknown codec) for well-formed-but-unreadable ones;
            // both are equally "can't safely extract this" from this call site's perspective.
            return ArchiveExtractionOutcome.Rejected("not a valid/supported 7z archive: ${e.message}")
        }

        return sevenZFile.use { sevenZ ->
            val entries = sevenZ.entries.toList()
            if (entries.size > limits.maxEntries) {
                return@use ArchiveExtractionOutcome.Rejected(
                    "archive has ${entries.size} entries, exceeding the limit of ${limits.maxEntries}"
                )
            }

            val seenCanonicalPaths = mutableSetOf<String>()
            val fileEntries = mutableListOf<SevenZArchiveEntry>()
            for (entry in entries) {
                val rejection = validateArchiveEntryName(entry.name.orEmpty(), destinationDir, seenCanonicalPaths)
                if (rejection != null) {
                    return@use ArchiveExtractionOutcome.Rejected(rejection)
                }
                if (!entry.isDirectory) {
                    fileEntries += entry
                }
            }

            if (fileEntries.isEmpty()) {
                return@use ArchiveExtractionOutcome.Rejected("archive contains no files")
            }
            if (fileEntries.size > 1) {
                return@use ArchiveExtractionOutcome.MultipleEntries(fileEntries.size)
            }

            val entry = fileEntries.single()
            if (entry.size > limits.maxUncompressedBytes) {
                return@use ArchiveExtractionOutcome.Rejected(
                    "declared uncompressed size ${entry.size} exceeds the limit of ${limits.maxUncompressedBytes}"
                )
            }

            // 7z compresses entries inside shared "folders" rather than per-entry streams, so
            // there is no reliable per-entry compressed size to compare against — approximate the
            // decompression-ratio check against the whole archive's on-disk size instead (still
            // guards against a small 7z file expanding to an enormous one).
            val archiveSizeOnDisk = archiveFile.length().takeIf { it > 0 } ?: 1L

            var positioned: SevenZArchiveEntry? = null
            while (true) {
                val next = sevenZ.nextEntry ?: break
                if (!next.isDirectory) {
                    positioned = next
                    break
                }
            }
            if (positioned == null) {
                return@use ArchiveExtractionOutcome.Rejected("failed to position reader at the archive's sole entry")
            }

            val outputFile = File(destinationDir, safeExtractedFileName(entry.name.orEmpty()))
            var totalWritten = 0L
            try {
                outputFile.outputStream().use { output ->
                    val buffer = ByteArray(limits.copyBufferBytes)
                    while (true) {
                        val read = sevenZ.read(buffer)
                        if (read < 0) break
                        totalWritten += read
                        if (totalWritten > limits.maxUncompressedBytes) {
                            throw ZipBombException("uncompressed size exceeded ${limits.maxUncompressedBytes} bytes")
                        }
                        if (totalWritten / archiveSizeOnDisk > limits.maxCompressionRatio) {
                            throw ZipBombException("compression ratio exceeded ${limits.maxCompressionRatio}x")
                        }
                        output.write(buffer, 0, read)
                    }
                }
            } catch (e: ZipBombException) {
                outputFile.delete()
                return@use ArchiveExtractionOutcome.Rejected(e.message ?: "decompression ratio safety check failed")
            } catch (e: IOException) {
                outputFile.delete()
                return@use ArchiveExtractionOutcome.Rejected("failed to extract archive entry: ${e.message}")
            }

            if (totalWritten == 0L) {
                outputFile.delete()
                return@use ArchiveExtractionOutcome.Rejected("extracted entry was empty")
            }

            ArchiveExtractionOutcome.Success(outputFile)
        }
    }

    private class ZipBombException(message: String) : IOException(message)
}

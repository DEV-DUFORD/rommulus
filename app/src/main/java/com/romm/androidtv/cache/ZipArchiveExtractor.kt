package com.romm.androidtv.cache

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Secure ZIP extraction for RomM content whose "single file" entry is
 * actually a ZIP archive containing the raw ROM bytes (LIBRETRO_REFACTOR.md
 * section 10). Cores like SameBoy need raw `.gb`/`.gbc` bytes, not a
 * compressed container.
 */
object ZipArchiveExtractor {

    /**
     * Extracts the sole ROM-like entry from [archiveFile] into
     * [destinationDir], which must already exist and be empty. Never
     * partially populates [destinationDir] on any failure path — partial
     * output is deleted before returning.
     */
    internal fun extractSingleEntry(
        archiveFile: File,
        destinationDir: File,
        limits: ArchiveSafetyLimits = ArchiveSafetyLimits.DEFAULT,
    ): ArchiveExtractionOutcome {
        val zipFile = try {
            ZipFile(archiveFile)
        } catch (e: IOException) {
            return ArchiveExtractionOutcome.Rejected("not a valid zip archive: ${e.message}")
        }

        return zipFile.use { zip ->
            val entries = zip.entries().asSequence().toList()
            if (entries.size > limits.maxEntries) {
                return@use ArchiveExtractionOutcome.Rejected(
                    "archive has ${entries.size} entries, exceeding the limit of ${limits.maxEntries}"
                )
            }

            val seenCanonicalPaths = mutableSetOf<String>()
            val fileEntries = mutableListOf<ZipEntry>()
            for (entry in entries) {
                val rejection = validateArchiveEntryName(entry.name, destinationDir, seenCanonicalPaths)
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
            val outputFile = File(destinationDir, safeExtractedFileName(entry.name))
            val compressedSize = entry.compressedSize.takeIf { it > 0 } ?: 1L

            var totalWritten = 0L
            try {
                zip.getInputStream(entry).use { input ->
                    outputFile.outputStream().use { output ->
                        val buffer = ByteArray(limits.copyBufferBytes)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            totalWritten += read
                            if (totalWritten > limits.maxUncompressedBytes) {
                                throw ZipBombException("uncompressed size exceeded ${limits.maxUncompressedBytes} bytes")
                            }
                            if (totalWritten / compressedSize > limits.maxCompressionRatio) {
                                throw ZipBombException("compression ratio exceeded ${limits.maxCompressionRatio}x")
                            }
                            output.write(buffer, 0, read)
                        }
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

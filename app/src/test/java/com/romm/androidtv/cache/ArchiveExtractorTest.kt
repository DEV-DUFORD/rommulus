package com.romm.androidtv.cache

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Covers the section-10 safety rules shared by [ZipArchiveExtractor] and
 * [SevenZArchiveExtractor]: happy-path single-entry extraction, path
 * traversal/absolute-path/duplicate-path rejection, multi-entry archives,
 * and the entry-count/decompression-ratio zip-bomb limits. Limits are
 * injected small so these tests stay fast without needing real gigantic
 * archives.
 */
@DisplayName("Archive extractors — RomM single-file entries that are actually zip/7z containers")
class ArchiveExtractorTest {

    private lateinit var workDir: File
    private lateinit var destinationDir: File

    @BeforeEach
    fun setUp() {
        workDir = Files.createTempDirectory("archive-extractor-test").toFile()
        destinationDir = File(workDir, "out").apply { mkdirs() }
    }

    @AfterEach
    fun tearDown() {
        workDir.deleteRecursively()
    }

    private fun zipWithEntries(vararg entries: Pair<String, ByteArray>): File {
        val zipFile = File(workDir, "archive-${System.nanoTime()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            for ((name, bytes) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return zipFile
    }

    /** Two entries with different literal names that canonicalize to the exact same on-disk path once resolved under destinationDir — not rejected by ZipOutputStream's own dedup (which only rejects identical literal names), so this genuinely exercises the extractor's own canonical-path check. */
    private fun zipWithCanonicalCollision(): File {
        val zipFile = File(workDir, "archive-dup-${System.nanoTime()}.zip")
        ZipOutputStream(zipFile.outputStream()).use { zos ->
            zos.putNextEntry(ZipEntry("sub/game.gb"))
            zos.write("first".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("sub/./game.gb"))
            zos.write("second".toByteArray())
            zos.closeEntry()
        }
        return zipFile
    }

    @Nested
    @DisplayName("ZipArchiveExtractor")
    inner class Zip {
        @Test
        fun `extracts the sole entry's raw bytes, preserving its extension`() {
            val zip = zipWithEntries("game.gb" to "GBROM".toByteArray())

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Success::class.java)
            val file = (outcome as ArchiveExtractionOutcome.Success).file
            assertThat(file.name).isEqualTo("rom.gb")
            assertThat(file.readBytes()).isEqualTo("GBROM".toByteArray())
        }

        @Test
        fun `a path-traversal entry name is rejected without writing anything`() {
            val zip = zipWithEntries("../evil.gb" to "x".toByteArray())

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat(destinationDir.listFiles()).isEmpty()
        }

        @Test
        fun `an absolute entry path is rejected`() {
            val zip = zipWithEntries("/etc/evil.gb" to "x".toByteArray())

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
        }

        @Test
        fun `a windows drive-letter absolute path is rejected`() {
            val zip = zipWithEntries("""C:\evil.gb""" to "x".toByteArray())

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
        }

        @Test
        fun `duplicate canonical entry paths are rejected`() {
            val zip = zipWithCanonicalCollision()

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat((outcome as ArchiveExtractionOutcome.Rejected).reason).contains("duplicate canonical path")
        }

        @Test
        fun `an archive with more than one file entry is surfaced as MultipleEntries, not extracted`() {
            val zip = zipWithEntries("a.gb" to "a".toByteArray(), "b.gb" to "b".toByteArray())

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir)

            assertThat(outcome).isEqualTo(ArchiveExtractionOutcome.MultipleEntries(2))
            assertThat(destinationDir.listFiles()).isEmpty()
        }

        @Test
        fun `an archive with no file entries at all is rejected`() {
            val zipFile = File(workDir, "empty.zip")
            ZipOutputStream(zipFile.outputStream()).use { /* zero entries */ }

            val outcome = ZipArchiveExtractor.extractSingleEntry(zipFile, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
        }

        @Test
        fun `an entry count over the configured limit is rejected before any extraction`() {
            val entries = (1..5).map { "f$it.gb" to "x".toByteArray() }.toTypedArray()
            val zip = zipWithEntries(*entries)
            val tightLimits = ArchiveSafetyLimits(maxEntries = 3)

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir, tightLimits)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat((outcome as ArchiveExtractionOutcome.Rejected).reason).contains("exceeding the limit of 3")
        }

        @Test
        fun `an entry whose declared uncompressed size exceeds the byte cap is rejected without exhausting disk`() {
            // Highly compressible payload so the zip file itself stays tiny, but its
            // uncompressed size is deliberately larger than a tightly-configured cap.
            val payload = ByteArray(1024) { 0 }
            val zip = zipWithEntries("bomb.gb" to payload)
            val tightLimits = ArchiveSafetyLimits(maxUncompressedBytes = 100)

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir, tightLimits)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat((outcome as ArchiveExtractionOutcome.Rejected).reason).contains("uncompressed size exceeded")
            assertThat(destinationDir.listFiles()?.toList().orEmpty()).noneMatch { it.length() > 0 }
        }

        @Test
        fun `a decompression ratio over the configured limit is rejected`() {
            // 4096 zero bytes deflate down to a handful of compressed bytes — comfortably over a ratio limit of 10x.
            val payload = ByteArray(4096) { 0 }
            val zip = zipWithEntries("bomb.gb" to payload)
            val tightLimits = ArchiveSafetyLimits(maxUncompressedBytes = 1024L * 1024, maxCompressionRatio = 10)

            val outcome = ZipArchiveExtractor.extractSingleEntry(zip, destinationDir, tightLimits)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat((outcome as ArchiveExtractionOutcome.Rejected).reason).contains("compression ratio exceeded")
        }

        @Test
        fun `a non-zip file is rejected as a malformed archive, not a crash`() {
            val notAZip = File(workDir, "not-a-zip.zip").apply { writeText("this is not a zip file") }

            val outcome = ZipArchiveExtractor.extractSingleEntry(notAZip, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
        }
    }

    @Nested
    @DisplayName("SevenZArchiveExtractor")
    inner class SevenZ {
        private fun sevenZWithEntries(vararg entries: Pair<String, ByteArray>): File {
            val file = File(workDir, "archive-${System.nanoTime()}.7z")
            SevenZOutputFile(file).use { out ->
                for ((name, bytes) in entries) {
                    val entry = SevenZArchiveEntry()
                    entry.name = name
                    out.putArchiveEntry(entry)
                    out.write(bytes)
                    out.closeArchiveEntry()
                }
            }
            return file
        }

        @Test
        fun `extracts the sole entry's raw bytes, preserving its extension`() {
            val archive = sevenZWithEntries("game.gbc" to "GBCROM".toByteArray())

            val outcome = SevenZArchiveExtractor.extractSingleEntry(archive, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Success::class.java)
            val file = (outcome as ArchiveExtractionOutcome.Success).file
            assertThat(file.name).isEqualTo("rom.gbc")
            assertThat(file.readBytes()).isEqualTo("GBCROM".toByteArray())
        }

        @Test
        fun `a path-traversal entry name is rejected without writing anything`() {
            val archive = sevenZWithEntries("../evil.gbc" to "x".toByteArray())

            val outcome = SevenZArchiveExtractor.extractSingleEntry(archive, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat(destinationDir.listFiles()).isEmpty()
        }

        @Test
        fun `an archive with more than one file entry is surfaced as MultipleEntries, not extracted`() {
            val archive = sevenZWithEntries("a.gbc" to "a".toByteArray(), "b.gbc" to "b".toByteArray())

            val outcome = SevenZArchiveExtractor.extractSingleEntry(archive, destinationDir)

            assertThat(outcome).isEqualTo(ArchiveExtractionOutcome.MultipleEntries(2))
        }

        @Test
        fun `an entry whose declared size exceeds the byte cap is rejected`() {
            val payload = ByteArray(4096) { 0 }
            val archive = sevenZWithEntries("bomb.gbc" to payload)
            val tightLimits = ArchiveSafetyLimits(maxUncompressedBytes = 100)

            val outcome = SevenZArchiveExtractor.extractSingleEntry(archive, destinationDir, tightLimits)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
            assertThat((outcome as ArchiveExtractionOutcome.Rejected).reason).contains("declared uncompressed size")
        }

        @Test
        fun `a non-7z file is rejected as a malformed archive, not a crash`() {
            val notASevenZ = File(workDir, "not-a-7z.7z").apply { writeText("this is not a 7z file") }

            val outcome = SevenZArchiveExtractor.extractSingleEntry(notASevenZ, destinationDir)

            assertThat(outcome).isInstanceOf(ArchiveExtractionOutcome.Rejected::class.java)
        }
    }
}

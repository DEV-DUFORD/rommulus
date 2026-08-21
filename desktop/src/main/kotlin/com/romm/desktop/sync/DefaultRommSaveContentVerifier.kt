package com.romm.desktop.sync

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Matches RomM's pinned backend `compute_content_hash` implementation:
 * raw files use MD5, while ZIP saves hash the sorted `entry-name:entry-md5` lines.
 */
object DefaultRommSaveContentVerifier : RommSaveContentVerifier {

    override fun verify(
        bytes: ByteArray,
        expected: RommSaveContentHash,
    ): RommSaveContentVerification = try {
        val actual = RommSaveContentHash.computed(
            if (hasZipEndOfCentralDirectory(bytes)) zipContentHash(bytes) else md5Hex(bytes),
        )
        if (actual == expected) {
            RommSaveContentVerification.Match
        } else {
            RommSaveContentVerification.Mismatch(expected, actual)
        }
    } catch (e: Exception) {
        RommSaveContentVerification.Unreadable(e.message ?: e::class.java.simpleName)
    }

    private fun zipContentHash(bytes: ByteArray): String {
        val names = mutableListOf<String>()
        val lastHashByName = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes), ZIP_NAME_CHARSET).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name
                names += name
                if (!name.endsWith("/")) {
                    lastHashByName[name] = md5Hex(zip.readBytes())
                }
                zip.closeEntry()
            }
        }
        val combined = names.asSequence()
            .filterNot { it.endsWith("/") }
            .sorted()
            .joinToString("\n") { name -> "$name:${lastHashByName.getValue(name)}" }
        return md5Hex(combined.toByteArray(StandardCharsets.UTF_8))
    }

    /**
     * Python's `zipfile.is_zipfile` recognizes an archive by its end-of-central-directory record,
     * including empty and self-extracting ZIPs. Checking the same record avoids treating every
     * `PK`-prefixed payload as an archive.
     */
    private fun hasZipEndOfCentralDirectory(bytes: ByteArray): Boolean {
        if (bytes.size < ZIP_EOCD_MIN_SIZE) return false
        val searchStart = (bytes.size - ZIP_EOCD_MAX_SEARCH).coerceAtLeast(0)
        for (index in bytes.size - ZIP_EOCD_MIN_SIZE downTo searchStart) {
            if (
                bytes[index] == 0x50.toByte() &&
                bytes[index + 1] == 0x4b.toByte() &&
                bytes[index + 2] == 0x05.toByte() &&
                bytes[index + 3] == 0x06.toByte()
            ) {
                val commentLength =
                    (bytes[index + 20].toInt() and 0xff) or
                        ((bytes[index + 21].toInt() and 0xff) shl 8)
                if (index + ZIP_EOCD_MIN_SIZE + commentLength == bytes.size) return true
            }
        }
        return false
    }

    private fun md5Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("MD5")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private val ZIP_NAME_CHARSET: Charset = Charset.forName("Cp437")
    private const val ZIP_EOCD_MIN_SIZE = 22
    private const val ZIP_EOCD_MAX_SEARCH = ZIP_EOCD_MIN_SIZE + 65_535
}

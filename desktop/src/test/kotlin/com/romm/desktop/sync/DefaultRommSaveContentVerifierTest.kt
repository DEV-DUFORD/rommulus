package com.romm.desktop.sync

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@DisplayName("RomM save content verifier")
class DefaultRommSaveContentVerifierTest {

    @Test
    fun `raw saves use RomM MD5 rather than client SHA-256`() {
        val bytes = "chosen-save-bytes".toByteArray()
        val expected = checkNotNull(
            RommSaveContentHash.parseOrNull("6d3a0aaf65e2d1cf8b904f030edbeb50"),
        )

        assertThat(DefaultRommSaveContentVerifier.verify(bytes, expected))
            .isEqualTo(RommSaveContentVerification.Match)
        assertThat(
            RommSaveContentHash.parseOrNull(
                "3363f694a9cbc73d8f0ea4f77d07fea813c4acf06328106ec29017d52e32484e",
            ),
        ).isNull()
    }

    @Test
    fun `zip saves use the backend sorted member fingerprint`() {
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry("b.srm"))
                zip.write("beta".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("a.srm"))
                zip.write("alpha".toByteArray())
                zip.closeEntry()
            }
            output.toByteArray()
        }
        val expected = checkNotNull(
            RommSaveContentHash.parseOrNull("6e42de0bba44de86f213ca48f5c388dd"),
        )

        assertThat(DefaultRommSaveContentVerifier.verify(bytes, expected))
            .isEqualTo(RommSaveContentVerification.Match)
    }

    @Test
    fun `recognized fingerprint mismatch is typed with the computed hash`() {
        val expected = checkNotNull(
            RommSaveContentHash.parseOrNull("00000000000000000000000000000000"),
        )

        val result = DefaultRommSaveContentVerifier.verify(
            "chosen-save-bytes".toByteArray(),
            expected,
        )

        assertThat(result).isEqualTo(
            RommSaveContentVerification.Mismatch(
                expected = expected,
                actual = checkNotNull(
                    RommSaveContentHash.parseOrNull("6d3a0aaf65e2d1cf8b904f030edbeb50"),
                ),
            ),
        )
    }
}

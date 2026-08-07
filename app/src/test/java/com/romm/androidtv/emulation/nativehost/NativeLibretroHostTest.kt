package com.romm.androidtv.emulation.nativehost

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class NativeLibretroHostTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `finds a core in an ABI split when the base APK does not contain it`() {
        val baseApk = createApk("base.apk", "res/drawable/icon.png")
        val abiSplit = createApk(
            "split_config.arm64_v8a.apk",
            "lib/arm64-v8a/libsameboy_core.so",
        )

        val result = NativeLibretroHost.findApkContainingEntry(
            listOf(baseApk, abiSplit),
            "lib/arm64-v8a/libsameboy_core.so",
        )

        assertThat(result).isEqualTo(abiSplit)
    }

    @Test
    fun `reports every inspected APK when a core is missing`() {
        val baseApk = createApk("base.apk", "AndroidManifest.xml")
        val abiSplit = createApk("split_config.armeabi_v7a.apk", "lib/armeabi-v7a/libother.so")

        assertThatThrownBy {
            NativeLibretroHost.findApkContainingEntry(
                listOf(baseApk, abiSplit),
                "lib/armeabi-v7a/libsameboy_core.so",
            )
        }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining(baseApk)
            .hasMessageContaining(abiSplit)
    }

    private fun createApk(fileName: String, entryName: String): String {
        val apk = tempDir.resolve(fileName)
        ZipOutputStream(apk.toFile().outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(entryName))
            zip.write(byteArrayOf(1, 2, 3))
            zip.closeEntry()
        }
        return apk.toString()
    }
}

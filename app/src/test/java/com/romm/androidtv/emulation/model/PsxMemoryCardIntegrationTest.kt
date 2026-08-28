package com.romm.androidtv.emulation.model

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class PsxMemoryCardIntegrationTest {
    @Test
    fun `frontend keeps card 1 in synchronized save RAM and disables upstream shared card 2`() {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .takeWhile { it.exists() }
            .first { File(it, "native/engine/src/emulation_session.cpp").isFile }
        val hostSource = File(
            repositoryRoot,
            "native/engine/src/emulation_session.cpp",
        ).readText()
        val coreSource = File(
            repositoryRoot,
            "third_party/cores/pcsx_rearmed/frontend/libretro.c",
        ).readText()

        assertThat(coreSource).contains("case RETRO_MEMORY_SAVE_RAM:")
        assertThat(coreSource).contains("memcard_type[0] == MEMCARDTYPE_LIBRETRO")
        assertThat(coreSource).contains("return MCD_SIZE;")
        assertThat(hostSource)
            .contains("setCoreOptionOverride(\"pcsx_rearmed_memcard1\", \"libretro\")")
            .contains("setCoreOptionOverride(\"pcsx_rearmed_memcard2\", \"none\")")
    }

    @Test
    fun `full path cores do not duplicate disc bytes into frontend memory`() {
        val repositoryRoot = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
            .takeWhile { it.exists() }
            .first { File(it, "native/engine/src/emulation_session.cpp").isFile }
        val hostSource = File(
            repositoryRoot,
            "native/engine/src/emulation_session.cpp",
        ).readText()

        assertThat(hostSource).contains("info.data = systemInfo.need_fullpath ? nullptr")
        assertThat(hostSource).contains("info.size = systemInfo.need_fullpath ? 0")
    }
}

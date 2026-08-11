package com.romm.androidtv.emulation.touch

import com.romm.androidtv.config.FakeSharedPreferences
import com.romm.androidtv.controller.config.CoreControllerProfiles
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TouchLayoutRepositoryTest {

    @Test
    fun `saved visual layout round trips without mapping data`() {
        val prefs = FakeSharedPreferences()
        val repository = TouchLayoutRepository(prefs)
        val profile = CoreControllerProfiles.byCoreId("snes9x")!!
        val defaults = DefaultTouchLayouts.forProfile(profile)
        val document = defaults.toOverrideDocument()

        assertThat(repository.save(document)).isTrue()

        assertThat(repository.load(profile.coreId)).isEqualTo(document)
    }

    @Test
    fun `layouts are persisted independently per core`() {
        val repository = TouchLayoutRepository(FakeSharedPreferences())
        val snes = DefaultTouchLayouts.forCore("snes9x")!!.toOverrideDocument()
        val nes = DefaultTouchLayouts.forCore("fceumm")!!.toOverrideDocument()

        repository.save(snes)
        repository.save(nes)

        assertThat(repository.load("snes9x")).isEqualTo(snes)
        assertThat(repository.load("fceumm")).isEqualTo(nes)
    }

    @Test
    fun `reset removes only requested core`() {
        val repository = TouchLayoutRepository(FakeSharedPreferences())
        repository.save(DefaultTouchLayouts.forCore("snes9x")!!.toOverrideDocument())
        val nes = DefaultTouchLayouts.forCore("fceumm")!!.toOverrideDocument()
        repository.save(nes)

        assertThat(repository.reset("snes9x")).isTrue()

        assertThat(repository.load("snes9x")).isNull()
        assertThat(repository.load("fceumm")).isEqualTo(nes)
    }

    @Test
    fun `corrupt persisted data safely resets to no overrides`() {
        val prefs = FakeSharedPreferences()
        prefs.edit().putString("touch_layout_overrides", "{not-json").commit()

        assertThat(TouchLayoutRepository(prefs).load("snes9x")).isNull()
    }
}

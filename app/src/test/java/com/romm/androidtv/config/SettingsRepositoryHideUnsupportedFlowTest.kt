package com.romm.androidtv.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JVM unit tests for the reactive [SettingsRepository.hideUnsupportedSystemsFlow].
 * Verifies that the StateFlow is a single source of truth: it emits the current
 * preference value and updates synchronously when [setHideUnsupportedSystems] is called.
 */
@DisplayName("SettingsRepository — hideUnsupportedSystemsFlow reactivity")
class SettingsRepositoryHideUnsupportedFlowTest {

    @Test
    fun `flow initial value matches persisted default (false)`() = runBlocking {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        assertThat(repo.hideUnsupportedSystemsFlow.value).isFalse()
        assertThat(repo.hideUnsupportedSystems()).isFalse()
    }

    @Test
    fun `flow initial value matches pre-persisted true`() = runBlocking {
        val prefs = FakeSharedPreferences()
        prefs.edit().putBoolean("hide_unsupported_systems", true).apply()
        val repo = SettingsRepository(prefs, defaultOrigin = "https://example.com")

        assertThat(repo.hideUnsupportedSystemsFlow.value).isTrue()
        assertThat(repo.hideUnsupportedSystems()).isTrue()
    }

    @Test
    fun `setHideUnsupportedSystems updates both the flow and synchronous read`() = runBlocking {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        // OFF → ON
        repo.setHideUnsupportedSystems(true)
        assertThat(repo.hideUnsupportedSystemsFlow.value).isTrue()
        assertThat(repo.hideUnsupportedSystems()).isTrue()

        // ON → OFF
        repo.setHideUnsupportedSystems(false)
        assertThat(repo.hideUnsupportedSystemsFlow.value).isFalse()
        assertThat(repo.hideUnsupportedSystems()).isFalse()
    }

    @Test
    fun `flow emits new value to collectors`() = runBlocking {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        // Collect the flow; verify it sees the initial value and subsequent changes.
        val collectedValues = mutableListOf<Boolean>()
        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        scope.launch {
            repo.hideUnsupportedSystemsFlow.collect { collectedValues.add(it) }
        }

        // Initial emission
        assertThat(collectedValues).containsExactly(false)

        // Toggle ON
        repo.setHideUnsupportedSystems(true)
        assertThat(collectedValues).containsExactly(false, true)

        // Toggle OFF
        repo.setHideUnsupportedSystems(false)
        assertThat(collectedValues).containsExactly(false, true, false)

        scope.coroutineContext[Job]!!.cancel()
    }

    @Test
    fun `multiple collectors each receive independent emissions`() = runBlocking {
        val repo = SettingsRepository(FakeSharedPreferences(), defaultOrigin = "https://example.com")

        val collector1 = mutableListOf<Boolean>()
        val collector2 = mutableListOf<Boolean>()

        val scope = CoroutineScope(Dispatchers.Unconfined + Job())
        scope.launch {
            repo.hideUnsupportedSystemsFlow.collect { collector1.add(it) }
        }
        scope.launch {
            repo.hideUnsupportedSystemsFlow.collect { collector2.add(it) }
        }

        repo.setHideUnsupportedSystems(true)
        repo.setHideUnsupportedSystems(false)

        assertThat(collector1).containsExactly(false, true, false)
        assertThat(collector2).containsExactly(false, true, false)

        scope.coroutineContext[Job]!!.cancel()
    }
}

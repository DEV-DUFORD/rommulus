package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.LoginCompletionResult
import com.romm.androidtv.auth.ServerValidationResult
import com.romm.androidtv.model.HeartbeatResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Verifies [OnboardingViewModelFactory] wires the requested initial step and username
 * prefill into the created [OnboardingViewModel]'s starting UI state.
 */
@DisplayName("OnboardingViewModelFactory — initial step + username wiring")
class OnboardingViewModelFactoryTest {

    private val heartbeat = HeartbeatResponse(
        version = "v0.17.1",
        setupComplete = true,
        userpassEnabled = true,
        emulatorJsEnabled = false,
    )

    // Network boundaries are never invoked for initial-state assertions; fail loudly if touched.
    private val validate = ValidateRommServer { origin ->
        ServerValidationResult.Valid(origin = origin, heartbeat = heartbeat)
    }
    private val persist = PersistValidatedOrigin { true }
    private val login = LoginToRomm { _, _, _ -> error("login should not be called for initial-state assertions") }
    private val removeOldestClientToken = RemoveOldestClientToken { error("removeOldestClientToken should not be called for initial-state assertions") }
    private val establishKioskSession = EstablishKioskSession { error("establishKioskSession should not be called for initial-state assertions") }

    private fun make(
        initialServerInput: String = "",
        initialStep: OnboardingStep = OnboardingStep.WELCOME,
        initialUsername: String = "",
    ): OnboardingViewModel {
        val factory = OnboardingViewModelFactory(
            validateRommServer = validate,
            persistValidatedOrigin = persist,
            loginToRomm = login,
            removeOldestClientToken = removeOldestClientToken,
            establishKioskSession = establishKioskSession,
            initialServerInput = initialServerInput,
            initialStep = initialStep,
            initialUsername = initialUsername,
        )
        return factory.create(OnboardingViewModel::class.java)
    }

    @Test
    fun `defaults to WELCOME with no prefill`() {
        val vm = make()
        val s = vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.WELCOME)
        assertThat(s.serverInput).isEmpty()
        assertThat(s.username).isEmpty()
    }

    @Test
    fun `initialStep SERVER prefills origin`() {
        val vm = make(
            initialServerInput = "https://romm.example.com",
            initialStep = OnboardingStep.SERVER,
        )
        val s = vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.SERVER)
        assertThat(s.serverInput).isEqualTo("https://romm.example.com")
        assertThat(s.username).isEmpty()
    }

    @Test
    fun `initialStep CREDENTIALS prefills username`() {
        val vm = make(
            initialServerInput = "https://romm.example.com",
            initialStep = OnboardingStep.CREDENTIALS,
            initialUsername = "alice",
        )
        val s = vm.uiState.value
        assertThat(s.step).isEqualTo(OnboardingStep.CREDENTIALS)
        assertThat(s.username).isEqualTo("alice")
        assertThat(s.password).isEmpty()
    }
}

package com.romm.androidtv.onboarding

import com.romm.androidtv.auth.LoginCompletionResult
import com.romm.androidtv.auth.QrLoginPollResult
import com.romm.androidtv.auth.QrLoginSession
import com.romm.androidtv.auth.QrLoginStartResult
import com.romm.androidtv.auth.ServerValidationResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Deterministic timing tests for the QR polling loop in [OnboardingPresenter]
 * ([pollQrSession]): the `delay(pollIntervalSeconds * 1_000L)` between polls,
 * the `SlowDown` backoff (`intervalSeconds += 5`), and cancellation while
 * suspended in a delay.
 *
 * Unlike [OnboardingPresenterTest] (which uses an unconfined dispatcher and
 * completes poll deferreds synchronously, so `delay` is never driven), the
 * presenter here runs on a [StandardTestDispatcher] sharing this test's virtual
 * clock, so `runCurrent`/`advanceTimeBy` advance the poll delays exactly.
 */
private const val CANONICAL = "https://romm.example.com"

@DisplayName("OnboardingPresenter — QR polling timing (virtual clock)")
class OnboardingPresenterQrPollingTest {

    private class BeginQrLoginFake : BeginQrLogin {
        var calls = 0
        var result: QrLoginStartResult = QrLoginStartResult.Unsupported

        override suspend fun invoke(origin: String): QrLoginStartResult {
            calls++
            return result
        }
    }

    /**
     * Scripted poll results: [enqueue] the outcomes in order; once the script is
     * exhausted every further call returns [QrLoginPollResult.Pending] so the
     * loop keeps polling (used by the cancellation test).
     */
    private class PollQrLoginFake : PollQrLogin {
        var calls = 0
        val origins = mutableListOf<String>()
        private val script = ArrayDeque<QrLoginPollResult>()

        fun enqueue(vararg results: QrLoginPollResult) {
            script.addAll(results)
        }

        override suspend fun invoke(origin: String, session: QrLoginSession): QrLoginPollResult {
            calls++
            origins.add(origin)
            return script.removeFirstOrNull() ?: QrLoginPollResult.Pending
        }
    }

    /**
     * Presenter pre-seeded straight into CREDENTIALS with the canonical origin so
     * its `init` block starts a QR login immediately — no validation/login fakes
     * are ever invoked (they are no-op lambdas, present only to satisfy the API).
     */
    private class Harness(
        val beginQrLogin: BeginQrLoginFake = BeginQrLoginFake(),
        val pollQrLogin: PollQrLoginFake = PollQrLoginFake(),
        scope: CoroutineScope,
    ) {
        val vm = OnboardingPresenter(
            scope = scope,
            validateRommServer = ValidateRommServer { ServerValidationResult.NetworkFailure },
            persistValidatedOrigin = PersistValidatedOrigin { false },
            loginToRomm = LoginToRomm { _, _, _ -> LoginCompletionResult.InvalidCredentials },
            removeOldestClientToken = RemoveOldestClientToken { false },
            establishKioskSession = EstablishKioskSession { true },
            beginQrLogin = beginQrLogin,
            pollQrLogin = pollQrLogin,
            initialServerInput = CANONICAL,
            initialStep = OnboardingStep.CREDENTIALS,
        )
    }

    @Test
    fun `first poll is immediate and second poll fires exactly after one interval`() = runTest {
        val presenterJob = SupervisorJob()
        val scope = CoroutineScope(presenterJob + StandardTestDispatcher(testScheduler))
        val h = Harness(scope = scope)
        h.beginQrLogin.result = QrLoginStartResult.Ready(qrSession()) // 5s interval
        h.pollQrLogin.enqueue(
            QrLoginPollResult.Pending,
            QrLoginPollResult.Success(verifiedUser()),
        )

        val collected = mutableListOf<OnboardingEffect>()
        val collector = launch { h.vm.effects.collect { collected.add(it) } }
        runCurrent() // start collector subscription + the (immediate) first poll

        assertThat(h.beginQrLogin.calls).isEqualTo(1)
        assertThat(h.vm.uiState.value.qrLoginState).isEqualTo(QrLoginUiState.Ready(qrSession()))
        assertThat(h.pollQrLogin.calls).isEqualTo(1) // no delay before the first poll

        advanceTimeBy(4_999) // one millisecond short of the 5s interval
        runCurrent()
        assertThat(h.pollQrLogin.calls).isEqualTo(1) // no early second poll

        advanceTimeBy(1) // exactly at the interval boundary
        runCurrent() // runs the resumed poll + drains effect delivery into the collector
        assertThat(h.pollQrLogin.calls).isEqualTo(2)
        assertThat(collected).containsExactly(OnboardingEffect.Completed) // emitted exactly once
        collector.cancel()
    }

    @Test
    fun `slow down extends the next poll delay by five seconds`() = runTest {
        val presenterJob = SupervisorJob()
        val scope = CoroutineScope(presenterJob + StandardTestDispatcher(testScheduler))
        val h = Harness(scope = scope)
        h.beginQrLogin.result = QrLoginStartResult.Ready(qrSession()) // 5s interval
        h.pollQrLogin.enqueue(
            QrLoginPollResult.Pending, // poll #1 -> delay(5s)
            QrLoginPollResult.SlowDown, // poll #2 -> interval becomes 10s -> delay(10s)
            QrLoginPollResult.Success(verifiedUser()), // poll #3
        )

        val collected = mutableListOf<OnboardingEffect>()
        val collector = launch { h.vm.effects.collect { collected.add(it) } }
        runCurrent()

        assertThat(h.pollQrLogin.calls).isEqualTo(1)

        advanceTimeBy(5_000) // t=5s: original interval elapses
        runCurrent()
        assertThat(h.pollQrLogin.calls).isEqualTo(2) // poll #2 -> SlowDown

        advanceTimeBy(5_000) // t=10s: another ORIGINAL interval elapsed since SlowDown
        runCurrent()
        assertThat(h.pollQrLogin.calls).isEqualTo(2) // ...but the backoff made it 10s, so no poll yet

        advanceTimeBy(5_000) // t=15s: the enlarged (10s) delay has now fully elapsed
        runCurrent()
        assertThat(h.pollQrLogin.calls).isEqualTo(3)
        assertThat(collected).containsExactly(OnboardingEffect.Completed)
        collector.cancel()
    }

    @Test
    fun `cancelling presenter scope mid-delay stops polling without crash or further calls`() = runTest {
        val presenterJob = SupervisorJob()
        val scope = CoroutineScope(presenterJob + StandardTestDispatcher(testScheduler))
        val h = Harness(scope = scope)
        h.beginQrLogin.result = QrLoginStartResult.Ready(qrSession()) // 5s interval
        // No script enqueued: the fake falls back to Pending forever, so the
        // loop would keep polling indefinitely if cancellation failed.

        runCurrent()
        assertThat(h.pollQrLogin.calls).isEqualTo(1)

        advanceTimeBy(2_000) // partway through the 5s delay between polls
        presenterJob.cancel() // cancel the presenter's scope while suspended in delay

        advanceTimeBy(60_000) // far past any remaining interval
        runCurrent()

        assertThat(h.pollQrLogin.calls).isEqualTo(1) // no poll after cancellation
        // State left untouched by the cancelled delay — no crash, no spurious update.
        assertThat(h.vm.uiState.value.qrLoginState).isEqualTo(QrLoginUiState.Ready(qrSession()))
    }

    private fun qrSession() = QrLoginSession(
        deviceCode = "device-code",
        userCode = "ABCD1234",
        verificationUrl = "$CANONICAL/pair/device?user_code=ABCD1234",
        expiresInSeconds = 600,
        pollIntervalSeconds = 5,
        installationId = "install-1",
    )

    private fun verifiedUser() = com.romm.androidtv.network.VerifiedUser(username = "zack", isAdmin = false)
}

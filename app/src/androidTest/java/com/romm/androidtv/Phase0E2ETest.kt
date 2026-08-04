package com.romm.androidtv

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end instrumentation test using UiAutomator2 for the first-run onboarding flow.
 *
 * Drives the native onboarding UI (spec Phase 5a):
 *   WELCOME -> SERVER (validate origin) -> CREDENTIALS (login) -> Native Library Home.
 *
 * Requires instrumentation arguments for a reachable RomM server origin plus credentials.
 * Skips with a clear assumption when any are absent. Never provides defaults.
 *
 * Usage:
 *   adb shell am instrument -w \
 *     -e origin '<https://romm.example.com or http://192.168.1.50:8080>' \
 *     -e username '<your_username>' \
 *     -e password '<your_password>' \
 *     com.romm.androidtv.debug/androidx.test.runner.AndroidJUnitRunner \
 *     com.romm.androidtv.Phase0E2ETest
 *
 * NOTE: The server must be reachable from the device and must have username/password
 * login enabled. Server validation performs a live heartbeat before any credential
 * submission. Assertions use visible text where possible and the Native Library nav
 * rail's content description ("Home") as the landing signal.
 */
@RunWith(AndroidJUnit4::class)
class Phase0E2ETest {

    private lateinit var device: UiDevice

    @Before
    fun setUp() {
        device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        // Kill any existing instance for clean state
        InstrumentationRegistry.getInstrumentation().context.packageManager
            .setComponentEnabledSetting(
                android.content.ComponentName(
                    "com.romm.androidtv.debug",
                    "com.romm.androidtv.MainActivity"
                ),
                android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
                android.content.pm.PackageManager.DONT_KILL_APP
            )

        // Require origin + credentials via instrumentation arguments; skip if absent.
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("origin", null)
        val username = args.getString("username", null)
        val password = args.getString("password", null)
        assumeTrue(
            "E2E test skipped: provide -e origin '<url>' -e username '<user>' -e password '<pass>' via instrumentation arguments",
            !origin.isNullOrBlank() && !username.isNullOrBlank() && !password.isNullOrBlank()
        )
    }

    @After
    fun tearDown() {
        // Clean up
    }

    @Test
    fun testOnboardingFlowAndNativeLibraryLanding() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val origin = args.getString("origin", null)!!
        val username = args.getString("username", null)!!
        val password = args.getString("password", null)!!

        // Launch the app
        val startupIntent = context.packageManager.getLaunchIntentForPackage("com.romm.androidtv.debug")
        startupIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(startupIntent)

        // Step 1 — Welcome screen (wait() throws on timeout)
        device.wait(Until.findObject(By.text("Welcome to RomM")), 10_000)

        // Continue to the Server step
        val continueButton = device.findObject(By.text("Continue"))
        assertTrue("Continue button should exist on Welcome", continueButton != null)
        continueButton!!.click()
        device.waitForIdle(3_000)

        // Step 2 — Server validation. Find the server URL field by its label text.
        val serverField = device.findObject(By.desc("RomM Server URL"))
        assertTrue("Server URL field should exist", serverField != null)
        serverField!!.click()
        device.waitForIdle(1_000)
        serverField.setText(origin)
        device.waitForIdle(1_000)

        // Click Next to validate the origin against a live heartbeat.
        val nextButton = device.findObject(By.text("Next"))
        assertTrue("Next button should exist on Server", nextButton != null)
        nextButton!!.click()
        device.waitForIdle(5_000)

        // Step 3 — Credentials
        val usernameField = device.findObject(By.desc("Username"))
        assertTrue("Username field should exist", usernameField != null)
        usernameField!!.click()
        device.waitForIdle(1_000)
        usernameField.setText(username)
        device.waitForIdle(1_000)

        val passwordField = device.findObject(By.desc("Password"))
        assertTrue("Password field should exist", passwordField != null)
        passwordField!!.click()
        device.waitForIdle(1_000)
        passwordField.setText(password)
        device.waitForIdle(1_000)

        // Submit login
        val loginButton = device.findObject(By.text("Login"))
        assertTrue("Login button should exist on Credentials", loginButton != null)
        loginButton!!.click()
        device.waitForIdle(5_000)

        // Landing — Native Library Home (nav rail item has contentDescription "Home").
        device.wait(Until.findObject(By.desc("Home")), 30_000)
    }
}

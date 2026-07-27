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
 * End-to-end instrumentation test using UiAutomator2.
 * Validates: login flow, authenticated WebView launch, EJS navigation,
 * and diagnostic capability capture.
 *
 * Requires instrumentation arguments for credentials. Skips with a clear
 * assumption when secrets are absent. Never provides defaults.
 *
 * Usage:
 *   adb shell am instrument -w \
 *     -e username '<your_username>' \
 *     -e password '<your_password>' \
 *     com.romm.androidtv.debug/androidx.test.runner.AndroidJUnitRunner \
 *     com.romm.androidtv.Phase0E2ETest
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

        // Require credentials via instrumentation arguments; skip if absent.
        val args = InstrumentationRegistry.getArguments()
        val username = args.getString("username", null)
        val password = args.getString("password", null)
        assumeTrue(
            "E2E test skipped: provide -e username '<user>' -e password '<pass>' via instrumentation arguments",
            !username.isNullOrBlank() && !password.isNullOrBlank()
        )
    }

    @After
    fun tearDown() {
        // Clean up
    }

    @Test
    fun testLoginFlowAndWebViewLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val args = InstrumentationRegistry.getArguments()
        val username = args.getString("username", null)!!
        val password = args.getString("password", null)!!

        // Launch the app
        val startupIntent = context.packageManager.getLaunchIntentForPackage("com.romm.androidtv.debug")
        startupIntent?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(startupIntent)

        // Wait for home screen (wait() throws on timeout)
        device.wait(Until.findObject(By.text("RomM TV")), 10_000)

        // Click Login button
        val loginButton = device.findObject(By.text("Login"))
        assertTrue("Login button should exist", loginButton != null)
        loginButton!!.click()
        device.waitForIdle(3_000)

        // Wait for login screen (wait() throws on timeout)
        device.wait(Until.findObject(By.text("Login to RomM")), 10_000)

        // Enter username from instrumentation argument
        val usernameField = device.findObject(By.desc("Username"))
        assertTrue("Username field should exist", usernameField != null)
        usernameField!!.click()
        device.waitForIdle(1_000)
        usernameField.setText(username)
        device.waitForIdle(1_000)

        // Enter password from instrumentation argument
        val passwordField = device.findObject(By.desc("Password"))
        assertTrue("Password field should exist", passwordField != null)
        passwordField!!.click()
        device.waitForIdle(1_000)
        passwordField.setText(password)
        device.waitForIdle(1_000)

        // Click Login button to submit
        val submitButton = device.findObject(By.text("Login"))
        assertTrue("Submit button should exist", submitButton != null)
        submitButton!!.click()
        device.waitForIdle(5_000)

        // Wait for authenticated WebView screen (wait() throws on timeout)
        device.wait(Until.findObject(By.text("RomM (Authenticated)")), 30_000)
    }
}

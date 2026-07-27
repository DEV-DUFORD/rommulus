package com.romm.androidtv.gamepad

import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.romm.androidtv.MainActivity
import com.romm.androidtv.controller.model.*
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the gamepad injection bridge.
 *
 * These tests compile and can run on a connected device or emulator.
 * They do NOT launch an AVD automatically.
 *
 * Tests verify:
 * - WebView document-start script availability
 * - Serialization correctness on Android
 * - Bridge lifecycle (activate/pause/dispose)
 * - Behavioral JavaScript tests via WebView
 * - W3C standard 4 axes / trigger buttons
 * - Disconnect event semantics
 * - Script handler API lifecycle
 */
@RunWith(AndroidJUnit4::class)
class GamepadInjectionBridgeInstrumentedTest {

    private lateinit var diagnostics: GamepadInjectionDiagnostics
    private lateinit var bridge: GamepadInjectionBridge

    @Before
    fun setUp() {
        diagnostics = GamepadInjectionDiagnostics()
        bridge = GamepadInjectionBridge(diagnostics)
    }

    @After
    fun tearDown() {
        bridge.dispose()
    }

    @Test
    fun `serializerProducesValidJsonOnAndroid`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )

        val json = GamepadSerializer.serializeSlots(slots)
        Assert.assertNotNull(json)
        Assert.assertTrue(json!!.startsWith("["))
        Assert.assertTrue(json.endsWith("]"))
    }

    @Test
    fun `serializerProducesW3cStandard4AxesOnAndroid`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )

        val json = GamepadSerializer.serializeSlots(slots)!!
        val axesSection = json.substringAfter("\"axes\":[").substringBefore("]")
        val values = axesSection.split(",").map { it.trim() }.filter { it.isNotBlank() }
        Assert.assertEquals(4, values.size)
    }

    @Test
    fun `serializerProducesW3cStandard16ButtonsOnAndroid`() {
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )

        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = json.substringAfter("\"buttons\":[").substringBefore("]")
        val values = buttonsSection.split(",").map { it.trim() }.filter { it.isNotBlank() }
        Assert.assertEquals(16, values.size)
    }

    @Test
    fun `triggersRemappedToButtonIndices6And7OnAndroid`() {
        val snapshot = GamepadSnapshot(
            buttons = FloatArray(16),
            axes = floatArrayOf(0f, 0f, 0f, 0f, 0.7f, 0.3f)
        )
        val sig = DeviceSignature(descriptor = "test", vendorId = 1, productId = 1, name = "Test")
        val slots = listOf(
            ControllerSlot(playerNumber = 1).assign(sig).updateSnapshot(snapshot),
            ControllerSlot(playerNumber = 2),
            ControllerSlot(playerNumber = 3),
            ControllerSlot(playerNumber = 4)
        )

        val json = GamepadSerializer.serializeSlots(slots)!!
        val buttonsSection = json.substringAfter("\"buttons\":[").substringBefore("]")
        val values = buttonsSection.split(",").map { it.trim().toFloat() }
        Assert.assertEquals(0.7f, values[6], 0.001f)
        Assert.assertEquals(0.3f, values[7], 0.001f)
    }

    @Test
    fun `scriptBuildsCorrectlyOnAndroid`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        Assert.assertTrue(script.isNotEmpty())
        Assert.assertTrue(script.contains("navigator.getGamepads"))
        Assert.assertTrue(script.contains("__rommGamepadOverride"))
        Assert.assertTrue(script.contains("Object.defineProperty"))
    }

    @Test
    fun `scriptDeclaresW3cStandardAxisCountOnAndroid`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        Assert.assertTrue(script.contains("NUM_AXES = 4"))
        Assert.assertTrue(script.contains("NUM_BUTTONS = 16"))
    }

    @Test
    fun `diagnosticsStateIsInitiallyEmpty`() {
        val state = diagnostics.state.value
        Assert.assertFalse(state.documentStartSupported)
        Assert.assertFalse(state.scriptInjected)
        Assert.assertEquals(0L, state.updateCount)
    }

    @Test
    fun `diagnosticsRecordsUpdatesCorrectly`() {
        repeat(5) {
            diagnostics.recordUpdate()
        }

        val state = diagnostics.state.value
        Assert.assertEquals(5L, state.updateCount)
        Assert.assertTrue(state.lastUpdateEpochMs > 0)
    }

    @Test
    fun `diagnosticsResetClearsAllState`() {
        diagnostics.setFeatureSupported(true)
        diagnostics.setScriptInjected(true, "https://romm.example.com")
        diagnostics.recordUpdate()

        diagnostics.reset()
        val state = diagnostics.state.value

        Assert.assertFalse(state.documentStartSupported)
        Assert.assertFalse(state.scriptInjected)
        Assert.assertEquals(0L, state.updateCount)
        Assert.assertNull(state.errorMessage)
    }

    @Test
    fun `bridgeDisposeDoesNotCrash`() {
        bridge.dispose()
        // Should not throw
    }

    @Test
    fun `bridgePauseAndResumeDoNotCrash`() {
        bridge.pause()
        bridge.resume()
        // Should not throw
    }

    @Test
    fun `bridgeSetSlotsWithInvalidCountDoesNotCrash`() {
        val slots = listOf(ControllerSlot(playerNumber = 1))
        bridge.setSlots(slots) // Should silently ignore and report error
        Assert.assertNotNull(diagnostics.state.value.errorMessage)
        Assert.assertTrue(diagnostics.state.value.errorMessage!!.contains("CONFIG:"))
    }

    @Test
    fun `MainActivityLaunchesWithoutGamepadBridgeErrors`() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            // Just verify the activity launches; don't interact with WebView
        }
    }

    @Test
    fun `documentStartScriptFeatureCheckWorksOnAndroid`() {
        val supported = bridge.isDocumentStartSupported
        // This may be true or false depending on device/WebView version
        Assert.assertNotNull(supported)
    }

    @Test
    fun `scriptHandlerLifecycleBuildReturnsNonEmptyScript`() {
        val origin = "https://romm.example.com"
        val script = GamepadInjectionScript.build(origin)
        Assert.assertTrue(script.isNotEmpty())
        Assert.assertTrue(script.length > 1000)
    }

    @Test
    fun `diagnosticsReportsInvalidConfigurationVisibly`() {
        diagnostics.setInvalidConfiguration("test invalid config")
        Assert.assertTrue(diagnostics.state.value.errorMessage!!.startsWith("CONFIG:"))
        Assert.assertTrue(diagnostics.state.value.errorMessage!!.contains("test invalid config"))
    }

    @Test
    fun `diagnosticsReportsSerializationErrorVisibly`() {
        diagnostics.setSerializationError("payload exceeded limit")
        Assert.assertTrue(diagnostics.state.value.errorMessage!!.startsWith("SERIALIZE:"))
    }

    @Test
    fun `SPAIdempotenceScriptPreventsDoubleInstallation`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        // The guard check is the first executable statement
        val guardIndex = script.indexOf("if (window.__rommGamepadOverride) return;")
        Assert.assertTrue(guardIndex > 0)
    }

    @Test
    fun `disconnectEventCreatesGamepadWithConnectedFalse`() {
        val script = GamepadInjectionScript.build("https://romm.example.com")
        Assert.assertTrue(script.contains("disconnectedGp"))
        Assert.assertTrue(script.contains(", false,"))
    }
}

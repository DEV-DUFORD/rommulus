package com.romm.androidtv.emulation.process

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.romm.androidtv.emulation.model.DescriptorState
import com.romm.androidtv.emulation.model.LaunchSessionJournal
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PcsxChdSmokeInstrumentedTest {
    @Test
    fun hostedChdLoadsWithPcsxRearmed() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val content = File(context.filesDir, "runtime_test/psx_chd_smoke.chd")
        val bios = File(context.filesDir, "system/scph5501.bin")
        assumeTrue("Stage the opt-in PSX CHD fixture before running this smoke test", content.isFile && bios.isFile)

        val sessionId = "pcsx-chd-smoke-${UUID.randomUUID()}"
        val save = File(context.filesDir, "save/pcsx_chd_smoke.srm")
        val journal = LaunchSessionJournal(File(context.filesDir, "launch_sessions"))
        val intent = Intent(context, EmulationActivity::class.java).apply {
            putExtra(EmulationActivity.EXTRA_APP_SESSION_ID, sessionId)
            putExtra(EmulationActivity.EXTRA_CORE_ID, "pcsx_rearmed")
            putExtra(EmulationActivity.EXTRA_CONTENT_PATH, content.absolutePath)
            putExtra(EmulationActivity.EXTRA_SAVE_PATH, save.absolutePath)
            putExtra(EmulationActivity.EXTRA_ROM_ID, 29122L)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync { context.startActivity(intent) }
        val deadline = System.currentTimeMillis() + 15_000L
        var descriptor = journal.read(sessionId)
        while (
            (descriptor == null || descriptor.state == DescriptorState.LAUNCHED) &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(100L)
            descriptor = journal.read(sessionId)
        }
        UiDevice.getInstance(instrumentation).pressBack()

        assertEquals(
            "PCSX load result: ${descriptor?.errorDetail}",
            DescriptorState.CORE_LOADED,
            descriptor?.state,
        )
        assertEquals(128L * 1024L, descriptor?.expectedSramSizeBytes)
    }
}

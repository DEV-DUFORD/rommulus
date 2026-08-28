package com.romm.androidtv

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainActivityLaunchPolicyTest {

    @Test
    fun `duplicate TV launcher activity above retained task is finished`() {
        assertTrue(
            shouldFinishDuplicateLauncherActivity(
                isTaskRoot = false,
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LEANBACK_LAUNCHER),
            )
        )
    }

    @Test
    fun `duplicate phone launcher activity above retained task is finished`() {
        assertTrue(
            shouldFinishDuplicateLauncherActivity(
                isTaskRoot = false,
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LAUNCHER),
            )
        )
    }

    @Test
    fun `root launcher activity is retained`() {
        assertFalse(
            shouldFinishDuplicateLauncherActivity(
                isTaskRoot = true,
                action = Intent.ACTION_MAIN,
                categories = setOf(Intent.CATEGORY_LEANBACK_LAUNCHER),
            )
        )
    }

    @Test
    fun `non-launcher activity is retained`() {
        assertFalse(
            shouldFinishDuplicateLauncherActivity(
                isTaskRoot = false,
                action = "com.romm.androidtv.OPEN_SETTINGS",
                categories = emptySet(),
            )
        )
    }
}

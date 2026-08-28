package com.romm.desktop.storage

import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

/**
 * [NoopSessionCookieSync] must return immediately from both methods — the desktop has no
 * WebView and device-token auth makes cookie sync unnecessary (plans/PHASE6.md §5 decision 3).
 */
class NoopSessionCookieSyncTest {

    private val sync = NoopSessionCookieSync()

    @Test
    fun `sync to web view is an immediate no-op`() {
        assertThatCode {
            runBlocking { sync.syncToWebView("https://romm.example.com") }
        }.doesNotThrowAnyException()
    }

    @Test
    fun `import from web view is an immediate no-op`() {
        assertThatCode {
            sync.importFromWebView("https://romm.example.com")
        }.doesNotThrowAnyException()
    }
}

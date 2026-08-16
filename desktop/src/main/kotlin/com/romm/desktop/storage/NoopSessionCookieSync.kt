package com.romm.desktop.storage

import com.romm.androidtv.network.SessionCookieSync

/**
 * No-op [SessionCookieSync] for the Linux desktop (plans/PHASE6.md §5 decision 3).
 *
 * The desktop product is browser-only with NO WebView/JCEF, so there is no platform cookie
 * store to sync to or import from: both methods return immediately. This is safe because
 * the portable RomM client authenticates exclusively with durable device/client tokens
 * ([com.romm.androidtv.auth.ClientTokenStorage]) — bearer auth needs no cookies at all, and
 * onboarding's new typed login path (`loginOnboarding`) makes token persistence required
 * before the session is considered complete.
 *
 * Revisit ONLY if a RomM endpoint ever requires cookie authentication (plans/PHASE6.md §5).
 */
class NoopSessionCookieSync : SessionCookieSync {

    override suspend fun syncToWebView(origin: String) {
        // No WebView on desktop; device-token auth makes cookies unnecessary. Intentionally empty.
    }

    override fun importFromWebView(origin: String) {
        // No platform cookie store on desktop. Intentionally empty.
    }
}

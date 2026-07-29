package com.romm.androidtv.romm

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that injects `Authorization: Bearer <token>` on every request.
 * Used exclusively by the worker's cookie-independent HTTP client — never imports
 * WebView cookies or session state into background execution.
 *
 * If the stored token is absent, requests are sent unauthenticated (the server
 * will respond 401, which the executor maps to [PendingOperationStatus.AUTH_REQUIRED]).
 */
class BearerAuthInterceptor(private val tokenProvider: () -> String?) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val token = tokenProvider()
        val request = if (token != null) {
            chain.request().newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        } else {
            chain.request()
        }
        return chain.proceed(request)
    }
}

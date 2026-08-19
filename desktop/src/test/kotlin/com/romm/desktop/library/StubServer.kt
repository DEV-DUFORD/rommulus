/*
 * Copyright (c) 2025 Romm Android TV contributors.
 * SPDX-License-Identifier: Apache-2.0
 */
package com.romm.desktop.library

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress

/**
 * Per-endpoint, per-test-response stub for the RomM firmware/platforms API.
 *
 * NOTE: `:desktop` does not declare `mockwebserver` (and the module's build file is
 * pinned by task constraints), so this stub uses the JDK's built-in [HttpServer] instead of
 * OkHttp's MockWebServer; it plays the exact same role: a local, in-process HTTP server with
 * per-test response configuration. Shared by [DesktopBiosConfigurationProviderTest] and the
 * coordinator launch-flow tests (Phase 11 work item 6).
 */
internal class StubServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var platformsStatus = 200
    @Volatile var platformsBody = "[]"
    @Volatile var firmwareStatus = 200
    @Volatile var firmwareBody = "[]"
    @Volatile var contentStatus = 200
    @Volatile var contentBytes = ByteArray(0)

    @Volatile var lastFirmwarePath: String? = null
    @Volatile var lastContentPath: String? = null

    val origin: String
        get() = "http://127.0.0.1:" + (server.address as InetSocketAddress).port

    fun start() {
        server.createContext("/api/platforms") { exchange ->
            respond(exchange, platformsStatus, platformsBody, json = true)
        }
        server.createContext("/api/firmware") { exchange ->
            val path = exchange.requestURI.toString()
            if (path == "/api/firmware" || path.startsWith("/api/firmware?")) {
                lastFirmwarePath = path
                respond(exchange, firmwareStatus, firmwareBody, json = true)
            } else {
                lastContentPath = path
                respond(exchange, contentStatus, contentBytes)
            }
        }
        server.start()
    }

    /** Convenience: 200 `[{id, slug}]` platform-list body. */
    fun platformsJson(id: Long, slug: String) {
        platformsBody = """[{"id": $id, "slug": "$slug", "fs_slug": "$slug"}]"""
        platformsStatus = 200
    }

    fun firmwareJson(vararg entries: String) {
        firmwareBody = "[${entries.joinToString(",")}]"
        firmwareStatus = 200
    }

    fun content(bytes: ByteArray, status: Int = 200) {
        contentBytes = bytes
        contentStatus = status
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any, json: Boolean = false) {
        val bytes = if (body is ByteArray) body else body.toString().toByteArray()
        try {
            exchange.responseHeaders.add("Content-Type", if (json) "application/json" else "application/octet-stream")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        } catch (_: IOException) {
            // Client went away mid-test; the assertion failure (if any) is what matters.
            exchange.close()
        }
    }

    override fun close() = server.stop(0)
}

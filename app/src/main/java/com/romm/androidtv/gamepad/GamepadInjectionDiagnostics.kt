package com.romm.androidtv.gamepad

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Observable diagnostics state for the Gamepad Injection Bridge.
 *
 * Exposes feature availability, injection status, and last-update timestamp
 * so the diagnostics screen can prove document-start injection succeeded
 * (or failed visibly) without requiring a WebView reload.
 *
 * Visible failure modes:
 * - Feature unsupported: DOCUMENT_START_SCRIPT not available
 * - Script injection failure: addDocumentStartJavaScript threw
 * - Invalid origin: origin string is malformed or empty
 * - Invalid slot count: setSlots called with != 4 slots
 * - Payload too large: serialized JSON exceeds MAX_PAYLOAD_BYTES
 */
class GamepadInjectionDiagnostics {

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Monotonically increasing update counter, incremented on each successful JS push. */
    private val updateCounter = AtomicLong(0)

    data class State(
        /** Whether DOCUMENT_START_SCRIPT feature is supported by the current WebView. */
        val documentStartSupported: Boolean = false,
        /** Whether the script was successfully added to the WebView. */
        val scriptInjected: Boolean = false,
        /** The allowed origin string used for injection. */
        val allowedOrigin: String? = null,
        /** Timestamp (epoch ms) of the last successful evaluateJavascript push. 0 if none. */
        val lastUpdateEpochMs: Long = 0,
        /** Monotonically increasing count of successful pushes. */
        val updateCount: Long = 0,
        /** Error message if injection failed. Null on success. */
        val errorMessage: String? = null
    )

    fun setFeatureSupported(supported: Boolean) {
        _state.value = _state.value.copy(
            documentStartSupported = supported,
            errorMessage = if (!supported) "DOCUMENT_START_SCRIPT not supported by this WebView" else null
        )
    }

    fun setScriptInjected(injected: Boolean, allowedOrigin: String?) {
        _state.value = _state.value.copy(
            scriptInjected = injected,
            allowedOrigin = allowedOrigin,
            errorMessage = if (!injected && _state.value.documentStartSupported) {
                "Document-start script addition failed"
            } else if (!injected && !_state.value.documentStartSupported) {
                // Preserve existing feature-unsupported error; don't overwrite it.
                _state.value.errorMessage
            } else {
                null
            }
        )
    }

    /** Record a visible failure for an invalid configuration. */
    fun setInvalidConfiguration(message: String) {
        _state.value = _state.value.copy(errorMessage = "CONFIG: $message")
    }

    /** Record a visible failure for serialization/payload issues. */
    fun setSerializationError(message: String) {
        _state.value = _state.value.copy(errorMessage = "SERIALIZE: $message")
    }

    fun recordUpdate() {
        val count = updateCounter.incrementAndGet()
        _state.value = _state.value.copy(
            lastUpdateEpochMs = System.currentTimeMillis(),
            updateCount = count,
            errorMessage = null // Clear error on successful update
        )
    }

    fun setError(message: String) {
        _state.value = _state.value.copy(errorMessage = message)
    }

    fun reset() {
        _state.value = State()
    }
}

package com.romm.androidtv.gamepad

/**
 * Document-start JavaScript that intercepts navigator.getGamepads().
 *
 * Installed before RomM/EmulatorJS execute, so they only ever see the
 * translated virtual gamepads. Physical controllers are completely hidden.
 *
 * Security: The script self-checks document.origin against an allowed-origin
 * whitelist embedded at injection time. On untrusted origins it becomes a no-op.
 *
 * Idempotence: A global marker (`__rommGamepadOverride`) prevents double-installation
 * across SPA route changes and repeated document-start invocations.
 *
 * W3C Gamepad API compliance:
 * - 16 buttons (indices 0-15), triggers mapped to button indices 6 (LT) and 7 (RT).
 * - 4 axes (left stick X/Y, right stick X/Y).
 * - connected=false set BEFORE gamepaddisconnected event dispatch.
 */
object GamepadInjectionScript {

    /**
     * The allowed-origin string passed from native code. Must be a valid origin
     * (scheme + host + optional port), e.g. "https://romm.example.com" or
     * "http://192.168.1.20:8080".
     */
    const val ALLOWED_ORIGIN_PLACEHOLDER = "__ALLOWED_ORIGIN__"

    /**
     * Full document-start script template. The placeholder is replaced with the
     * actual normalized RomM origin string before injection.
     */
    fun build(allowedOrigin: String): String {
        // Escape the allowed origin for safe embedding in JS single-quoted string literal.
        val escapedOrigin = allowedOrigin.replace("\\", "\\\\")
                                         .replace("'", "\\'")
                                         .replace("\n", "\\n")
                                         .replace("\r", "\\r")

        return """
(function() {
  if (window.__rommGamepadOverride) return;
  window.__rommGamepadOverride = true;

  var ALLOWED_ORIGIN = '$escapedOrigin';

  // Origin check: only inject on the exact configured RomM origin.
  if (document.location && document.location.origin !== ALLOWED_ORIGIN) {
    return;
  }

  // Exactly four browser-facing logical player slots, null when disconnected.
  var _virtualSlots = [null, null, null, null];

  // W3C Gamepad API standard counts.
  // Triggers are exposed as buttons at indices 6 (LT) and 7 (RT).
  var NUM_BUTTONS = 16;
  var NUM_AXES = 4;

  // Create a Gamepad-like object for a single slot.
  function createVirtualGamepad(index, id, connected, buttons, axes, timestamp) {
    var btns = [];
    for (var i = 0; i < NUM_BUTTONS; i++) {
      var b = buttons[i];
      if (typeof b === 'number') {
        btns.push({ pressed: b > 0.5, touched: b > 0.5, value: b });
      } else if (b && typeof b.pressed !== 'undefined') {
        btns.push(b);
      } else {
        btns.push({ pressed: false, touched: false, value: 0 });
      }
    }

    // Ensure axes array has exactly NUM_AXES entries.
    var ax = [];
    for (var i = 0; i < NUM_AXES; i++) {
      ax.push(typeof axes[i] === 'number' ? axes[i] : 0);
    }

    return {
      id: id,
      index: index,
      connected: connected,
      mapping: 'standard',
      timestamp: timestamp || performance.now(),
      buttons: btns,
      axes: ax
    };
  }

  // Override navigator.getGamepads using Object.defineProperty for robustness.
  // This prevents re-override attempts and works even if getGamepads is non-writable.
  try {
    Object.defineProperty(navigator, 'getGamepads', {
      value: function() {
        var result = [];
        for (var i = 0; i < 4; i++) {
          if (_virtualSlots[i] !== null) {
            result.push(_virtualSlots[i]);
          } else {
            // Return null for disconnected slots to preserve index alignment.
            result.push(null);
          }
        }
        return result;
      },
      configurable: true,
      enumerable: true,
      writable: true
    });
  } catch(e) {
    // Fallback if Object.defineProperty fails (rare on modern WebView).
    navigator.getGamepads = function() {
      var result = [];
      for (var i = 0; i < 4; i++) {
        result.push(_virtualSlots[i] !== null ? _virtualSlots[i] : null);
      }
      return result;
    };
  }

  // Global function called by native code via evaluateJavascript.
  // Accepts a JSON string of slot objects (length 4).
  window.__rommUpdateGamepads = function(jsonData) {
    var data;
    try {
      data = JSON.parse(jsonData);
    } catch(e) {
      return false;
    }

    if (!Array.isArray(data) || data.length !== 4) return false;

    var now = performance.now();

    for (var i = 0; i < 4; i++) {
      var slot = data[i];
      var wasConnected = _virtualSlots[i] !== null;

      if (!slot || slot.connected !== true) {
        // Slot is disconnected or not yet assigned.
        // CRITICAL: Set connected=false on the gamepad object BEFORE dispatching the event,
        // so event.gamepad reflects the disconnected state.
        var lastGamepad = _virtualSlots[i];
        if (wasConnected && lastGamepad) {
          // Create a disconnected version of the last known gamepad.
          var disconnectedGp = createVirtualGamepad(
            i, lastGamepad.id, false, [], [], performance.now()
          );
          emitGamepadEvent('gamepaddisconnected', disconnectedGp);
        }
        _virtualSlots[i] = null;
      } else {
        var id = slot.id || 'RomM Virtual Gamepad ' + (i + 1);
        var buttons = normalizeButtons(slot.buttons);
        var axes = normalizeAxes(slot.axes);
        var ts = (typeof slot.timestamp === 'number' && isFinite(slot.timestamp)) ? slot.timestamp : now;

        _virtualSlots[i] = createVirtualGamepad(i, id, true, buttons, axes, ts);

        if (!wasConnected) {
          emitGamepadEvent('gamepadconnected', _virtualSlots[i]);
        }
      }
    }
    return true;
  };

  // Normalize a buttons array to exactly NUM_BUTTONS numeric values.
  function normalizeButtons(raw) {
    var out = [];
    for (var i = 0; i < NUM_BUTTONS; i++) {
      if (raw && i < raw.length) {
        var v = Number(raw[i]);
        out.push(isFinite(v) ? Math.max(0, Math.min(1, v)) : 0);
      } else {
        out.push(0);
      }
    }
    return out;
  }

  // Normalize an axes array to exactly NUM_AXES numeric values.
  function normalizeAxes(raw) {
    var out = [];
    for (var i = 0; i < NUM_AXES; i++) {
      if (raw && i < raw.length) {
        var v = Number(raw[i]);
        out.push(isFinite(v) ? Math.max(-1, Math.min(1, v)) : 0);
      } else {
        out.push(0);
      }
    }
    return out;
  }

  // Emit a GamepadEvent (or compatible fallback).
  // [gamepad] is the gamepad object to attach to the event.
  function emitGamepadEvent(type, gamepad) {
    var event;

    if (typeof GamepadEvent === 'function') {
      try {
        event = new GamepadEvent(type, { gamepad: gamepad });
      } catch(e) {
        event = createFallbackGamepadEvent(type, gamepad);
      }
    } else {
      event = createFallbackGamepadEvent(type, gamepad);
    }

    try {
      window.dispatchEvent(event);
    } catch(e) {
      // Silently ignore dispatch failures
    }
  }

  function createFallbackGamepadEvent(type, gamepad) {
    var evt = document.createEvent('Event');
    evt.initEvent(type, true, true);
    evt.gamepad = gamepad || {
      id: '',
      index: -1,
      connected: false,
      mapping: 'standard',
      timestamp: performance.now(),
      buttons: [],
      axes: []
    };
    return evt;
  }

  // Report injection status for diagnostics.
  window.__rommGamepadStatus = {
    injected: true,
    origin: document.location.origin,
    allowedOrigin: ALLOWED_ORIGIN,
    numButtons: NUM_BUTTONS,
    numAxes: NUM_AXES
  };
})();
        """.trimIndent()
    }
}

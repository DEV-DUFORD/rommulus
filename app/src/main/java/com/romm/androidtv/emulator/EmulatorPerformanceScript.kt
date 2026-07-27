package com.romm.androidtv.emulator

/**
 * Applies TV-specific EmulatorJS performance defaults before RomM configures a game.
 *
 * RomM can enable rewind and a post-processing shader globally. Both are expensive
 * on low-power Android TV WebViews, so the native host forces them off without
 * changing the server-wide configuration used by desktop browsers. The host also
 * substitutes Picodrive for Genesis Plus GX because the latter cannot reserve its
 * Wasm heap in 32-bit Android WebView renderers.
 */
object EmulatorPerformanceScript {

    fun build(allowedOrigin: String): String {
        val escapedOrigin = allowedOrigin.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")

        return """
(function() {
  if (window.__rommEmulatorPerformancePolicy) return;

  var ALLOWED_ORIGIN = '$escapedOrigin';
  if (document.location && document.location.origin !== ALLOWED_ORIGIN) {
    return;
  }

  var options;
  var core;
  var forcedOptions = {
    shader: 'disabled',
    rewindEnabled: 'disabled'
  };
  var coreFallbacks = {
    genesis_plus_gx: 'picodrive'
  };

  Object.defineProperty(window, 'EJS_core', {
    configurable: true,
    enumerable: true,
    get: function() {
      return core;
    },
    set: function(value) {
      core = Object.prototype.hasOwnProperty.call(coreFallbacks, value)
        ? coreFallbacks[value]
        : value;
    }
  });

  function applyPolicy(value) {
    var target = value && typeof value === 'object' ? value : {};
    target.shader = forcedOptions.shader;
    target.rewindEnabled = forcedOptions.rewindEnabled;

    return new Proxy(target, {
      set: function(target, property, value) {
        target[property] = Object.prototype.hasOwnProperty.call(forcedOptions, property)
          ? forcedOptions[property]
          : value;
        return true;
      }
    });
  }

  Object.defineProperty(window, 'EJS_defaultOptions', {
    configurable: true,
    enumerable: true,
    get: function() {
      return options;
    },
    set: function(value) {
      options = applyPolicy(value);
    }
  });

  window.__rommEmulatorPerformancePolicy = {
    injected: true,
    shader: forcedOptions.shader,
    rewindEnabled: forcedOptions.rewindEnabled,
    coreFallbacks: coreFallbacks
  };
})();
        """.trimIndent()
    }
}

package com.romm.androidtv.diagnostic

/**
 * The complete HTML+JS diagnostic page loaded into the WebView via data: scheme.
 *
 * Explicitly treats crossOriginIsolated / SharedArrayBuffer failure as
 * EXPECTED when not served from a real COOP/COEP origin.
 *
 * Phase 0 diagnostics: tests SPA rendering context (data: URI),
 * WebAssembly, WebGL/WebGL2, IndexedDB, Worker, Gamepad API,
 * SharedArrayBuffer, crossOriginIsolated, AudioContext, and
 * fullscreen availability.
 */
object DiagnosticPageHtml {

    private const val CALLBACK_SCHEME = "rommdiag"
    private const val CALLBACK_URL = "$CALLBACK_SCHEME://results"

    fun build(): String {
        return """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>WebView Diagnostics</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: monospace; background: #111; color: #eee; padding: 24px; }
  h1 { font-size: 24px; margin-bottom: 16px; color: #fff; }
  table { width: 100%; border-collapse: collapse; margin-top: 8px; }
  th, td { text-align: left; padding: 10px 12px; border-bottom: 1px solid #333; }
  th { color: #aaa; font-size: 14px; }
  td { font-size: 16px; }
  .pass { color: #4caf50; }
  .fail { color: #f44336; }
  .expected-fail { color: #ff9800; }
  #json-output { margin-top: 20px; font-size: 12px; color: #777; white-space: pre-wrap; word-break: break-all; }
</style>
</head>
<body>
<h1>WebView Diagnostics</h1>
<table>
  <thead><tr><th>Capability</th><th>Status</th><th>Detail</th></tr></thead>
  <tbody id="results-body"></tbody>
</table>
<div id="json-output"></div>

<script>
(function() {
  var results = {};
  var pendingAsync = 1; // IndexedDB is async

  function addRow(name, status, detail) {
    var tr = document.createElement("tr");
    var tdName = document.createElement("td");
    tdName.textContent = name;
    var tdStatus = document.createElement("td");
    tdStatus.className = status;
    tdStatus.textContent = status.toUpperCase();
    var tdDetail = document.createElement("td");
    tdDetail.textContent = detail || "";
    tr.appendChild(tdName);
    tr.appendChild(tdStatus);
    tr.appendChild(tdDetail);
    document.getElementById("results-body").appendChild(tr);
  }

  // 1. JavaScript — if we are here, JS works
  results.javascript = true;
  addRow("JavaScript", "pass", "Running");

  // 2. WebAssembly
  try {
    var wasmCode = new Uint8Array([0,97,115,109,1,0,0,0]);
    var mod = new WebAssembly.Module(wasmCode);
    results.webAssembly = true;
    addRow("WebAssembly", "pass", "Module instantiation OK");
  } catch(e) {
    results.webAssembly = false;
    addRow("WebAssembly", "fail", e.message || "Unavailable");
  }

  // 3. WebGL
  try {
    var canvas = document.createElement("canvas");
    var gl = canvas.getContext("webgl") || canvas.getContext("experimental-webgl");
    if (gl) {
      var ver = gl.getParameter(gl.VERSION);
      results.webGl = ver;
      addRow("WebGL", "pass", ver);
    } else {
      results.webGl = false;
      addRow("WebGL", "fail", "No webgl context");
    }
  } catch(e) {
    results.webGl = false;
    addRow("WebGl", "fail", e.message || "Unavailable");
  }

  // 4. WebGL2
  try {
    var canvas2 = document.createElement("canvas");
    var gl2 = canvas2.getContext("webgl2");
    if (gl2) {
      results.webGl2 = true;
      addRow("WebGL2", "pass", gl2.getParameter(gl2.VERSION));
    } else {
      results.webGl2 = false;
      addRow("WebGL2", "fail", "No webgl2 context");
    }
  } catch(e) {
    results.webGl2 = false;
    addRow("WebGL2", "fail", e.message || "Unavailable");
  }

  // 5. IndexedDB (async)
  try {
    var idbReq = window.indexedDB.open("_diag_test", 1);
    idbReq.onupgradeneeded = function(e) {
      e.target.result.createObjectStore("test");
    };
    idbReq.onsuccess = function() {
      results.indexedDb = true;
      addRow("IndexedDB", "pass", "Open OK");
      idbReq.result.close();
      pendingAsync--;
      if (pendingAsync <= 0) finalize();
    };
    idbReq.onerror = function() {
      results.indexedDb = false;
      addRow("IndexedDB", "fail", "Open failed");
      pendingAsync--;
      if (pendingAsync <= 0) finalize();
    };
  } catch(e) {
    results.indexedDb = false;
    addRow("IndexedDB", "fail", e.message || "Unavailable");
    pendingAsync--;
    if (pendingAsync <= 0) finalize();
  }

  // 6. Web Worker
  try {
    var workerBlob = new Blob(["self.onmessage=function(){postMessage('ok');};"], {type:"text/javascript"});
    var workerUrl = URL.createObjectURL(workerBlob);
    var w = new Worker(workerUrl);
    w.onmessage = function() {
      results.worker = true;
      addRow("Worker", "pass", "Blob worker OK");
      w.terminate();
      URL.revokeObjectURL(workerUrl);
    };
    w.onerror = function() {
      results.worker = false;
      addRow("Worker", "fail", "Worker error");
      w.terminate();
      URL.revokeObjectURL(workerUrl);
    };
    w.postMessage("test");
    // Timeout fallback
    setTimeout(function() {
      if (results.worker === undefined) {
        results.worker = false;
        addRow("Worker", "fail", "Timeout");
        w.terminate();
        URL.revokeObjectURL(workerUrl);
      }
    }, 3000);
  } catch(e) {
    results.worker = false;
    addRow("Worker", "fail", e.message || "Unavailable");
  }

  // 7. AudioContext
  try {
    var AC = window.AudioContext || window.webkitAudioContext;
    if (AC) {
      var audioCtx = new AC();
      results.audio = true;
      addRow("AudioContext", "pass", "Sample rate: " + audioCtx.sampleRate);
      audioCtx.close();
    } else {
      results.audio = false;
      addRow("AudioContext", "fail", "Not available");
    }
  } catch(e) {
    results.audio = false;
    addRow("AudioContext", "fail", e.message || "Unavailable");
  }

  // 8. Fullscreen API
  try {
    var elem = document.documentElement;
    var fsMethod = elem.requestFullscreen || elem.webkitRequestFullscreen ||
                   elem.mozRequestFullScreen || elem.msRequestFullscreen;
    if (fsMethod) {
      results.fullscreen = true;
      addRow("Fullscreen", "pass", "Method: " + fsMethod.name);
    } else {
      results.fullscreen = false;
      addRow("Fullscreen", "fail", "No fullscreen method");
    }
  } catch(e) {
    results.fullscreen = false;
    addRow("Fullscreen", "fail", e.message || "Unavailable");
  }

  // 9. LocalStorage (EmulatorJS dependency)
  try {
    localStorage.setItem("_diag_test", "1");
    var val = localStorage.getItem("_diag_test");
    localStorage.removeItem("_diag_test");
    results.localStorage = true;
    addRow("LocalStorage", "pass", "Read/write OK");
  } catch(e) {
    results.localStorage = false;
    addRow("LocalStorage", "fail", e.message || "Unavailable");
  }

  // 10. Blob URL support (EmulatorJS uses for WASM loading)
  try {
    var blob = new Blob(["test"], {type: "text/plain"});
    var blobUrl = URL.createObjectURL(blob);
    results.blobUrls = true;
    addRow("Blob URLs", "pass", "Created blob URL");
    URL.revokeObjectURL(blobUrl);
  } catch(e) {
    results.blobUrls = false;
    addRow("Blob URLs", "fail", e.message || "Unavailable");
  }

  function finalize() {
    // Gamepad API
    if (results.gamepads === undefined) {
      try {
        results.gamepads = typeof navigator.getGamepads === "function";
        addRow("navigator.getGamepads", results.gamepads ? "pass" : "fail",
               results.gamepads ? "API present" : "Not available");
      } catch(e) {
        results.gamepads = false;
        addRow("navigator.getGamepads", "fail", e.message);
      }
    }

    // SharedArrayBuffer — expected to fail outside COOP/COEP
    if (results.sharedArrayBuffer === undefined) {
      try {
        var sabExists = typeof SharedArrayBuffer !== "undefined";
        if (sabExists) {
          new SharedArrayBuffer(4);
          results.sharedArrayBuffer = true;
          addRow("SharedArrayBuffer", "pass", "Allocated 4 bytes");
        } else {
          results.sharedArrayBuffer = false;
          addRow("SharedArrayBuffer", "expected-fail",
                 "Expected failure outside COOP/COEP origin");
        }
      } catch(e) {
        results.sharedArrayBuffer = false;
        addRow("SharedArrayBuffer", "expected-fail",
               "Expected failure: " + e.message);
      }
    }

    // crossOriginIsolated — expected to fail outside COOP/COEP
    if (results.crossOriginIsolated === undefined) {
      try {
        var coi = window.crossOriginIsolated === true;
        results.crossOriginIsolated = coi;
        addRow("crossOriginIsolated",
               coi ? "pass" : "expected-fail",
               coi ? "COOP+COEP active" : "Expected failure outside COOP/COEP origin");
      } catch(e) {
        results.crossOriginIsolated = false;
        addRow("crossOriginIsolated", "expected-fail",
               "Expected failure: property not available");
      }
    }

    // Send JSON back to native
    var json = JSON.stringify(results);
    document.getElementById("json-output").textContent = json;
    window.location.href = "$CALLBACK_URL?data=" + encodeURIComponent(json);
  }
})();
</script>
</body>
</html>""".trimIndent()
    }

    /**
     * The scheme prefix used by the diagnostic page to post results back.
     */
    fun callbackScheme(): String = CALLBACK_SCHEME
}

# WebView diagnostics — no aggressive shrinking of diagnostic code
-keep class com.romm.androidtv.diagnostic.DiagnosticResultParser { *; }
-keep class com.romm.androidtv.model.** { *; }

# Heartbeat, auth, and cookie sync — keep for reflection/proxy
-keep class com.romm.androidtv.network.** { *; }

# Gamepad injection bridge — keep serialization and diagnostics
-keep class com.romm.androidtv.gamepad.** { *; }

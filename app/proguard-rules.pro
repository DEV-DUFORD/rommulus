# WebView diagnostics — no aggressive shrinking of diagnostic code
-keep class com.romm.androidtv.diagnostic.DiagnosticResultParser { *; }
-keep class com.romm.androidtv.model.** { *; }

# Heartbeat, auth, and cookie sync — keep for reflection/proxy
-keep class com.romm.androidtv.network.** { *; }

# Moshi reflection models must retain their concrete classes and fields.
-keep @com.squareup.moshi.JsonClass class * { *; }

# Gamepad injection bridge — keep serialization and diagnostics
-keep class com.romm.androidtv.gamepad.** { *; }

// LogSink.h — platform-neutral logging seam for the RomMulus native engine.
//
// LINUX_X64.md sections 11/14, Phase 7 Wave 1: the engine tree
// (native/engine/) must never include platform headers (no Android log or
// JNI headers, no desktop toolkit headers). Platform code registers a
// LogSink implementation at startup; engine code emits diagnostics
// exclusively through romm::log::sink(). All other engine interfaces
// (video, audio, files, ...) arrive in later waves.
//
// The default sink (used when no platform sink is registered) writes to
// stderr, so diagnostics are never silently dropped in production.
#pragma once

#include <memory>
#include <string>

namespace romm::log {

// One-to-one with the severities the engine runs against:
// ANDROID_LOG_DEBUG/INFO/WARN/ERROR (Android) and the DEBUG/INFO/WARNING/
// ERROR levels of Libretro's retro_log_printf_t.
enum class Severity {
    Debug,
    Info,
    Warn,
    Error,
};

// A destination for engine diagnostics. Implementations must be safe to call
// from any thread the engine uses (the emulation thread, audio callbacks,
// and the main thread all log).
class LogSink {
public:
    virtual ~LogSink() = default;
    virtual void log(Severity severity, const std::string& tag, const std::string& message) = 0;
};

// Replaces the active sink (takes ownership); pass nullptr to fall back to
// the default stderr sink. Must complete before concurrent logging begins —
// on Android the platform sink is installed by a static initializer at
// library load time, before JNI_OnLoad.
void setSink(std::unique_ptr<LogSink> sink);

// The active sink, or the shared default stderr sink when none is
// registered. Never returns a null reference; messages are never dropped.
LogSink& sink();

}  // namespace romm::log

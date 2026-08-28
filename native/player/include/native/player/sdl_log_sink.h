// sdl_log_sink.h — stderr log sink for the Linux SDL3 player (Phase 8,
// LINUX_X64.md section 12).
//
// Registers as the engine's romm::log::LogSink: engine diagnostics are
// written to stderr with a "[severity][tag] message" prefix. The player's
// protocol channel (request/result JSON on disk) is strictly separate —
// this sink never writes protocol data to stderr.
#pragma once

#include <native/engine/LogSink.h>

namespace romm::player {

// Thread-safe: the engine logs from the emulation thread, the main thread,
// and (on other platforms) realtime callbacks; a single fprintf per line
// keeps concurrent lines from interleaving.
class SdlLogSink final : public romm::log::LogSink {
public:
    void log(romm::log::Severity severity, const std::string& tag,
             const std::string& message) override;
};

}  // namespace romm::player

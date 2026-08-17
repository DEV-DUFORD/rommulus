// sdl_log_sink.cpp — stderr log sink for the Linux SDL3 player.
#include "native/player/sdl_log_sink.h"

#include <cstdio>
#include <mutex>

namespace romm::player {

namespace {

const char* severityName(romm::log::Severity severity) {
    switch (severity) {
        case romm::log::Severity::Debug:
            return "DEBUG";
        case romm::log::Severity::Info:
            return "INFO";
        case romm::log::Severity::Warn:
            return "WARN";
        case romm::log::Severity::Error:
            return "ERROR";
    }
    return "UNKNOWN";
}

std::mutex& logMutex() {
    static std::mutex mutex;
    return mutex;
}

}  // namespace

void SdlLogSink::log(romm::log::Severity severity, const std::string& tag,
                     const std::string& message) {
    // One fprintf per line under a shared lock: concurrent loggers never
    // interleave a single line.
    std::lock_guard<std::mutex> lock(logMutex());
    std::fprintf(stderr, "[%s][%s] %s\n", severityName(severity), tag.c_str(),
                 message.c_str());
}

}  // namespace romm::player

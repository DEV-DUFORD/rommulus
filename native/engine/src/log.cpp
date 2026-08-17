// log.cpp — registry backing romm::log::setSink()/sink() (Phase 7 Wave 1).
#include <native/engine/LogSink.h>

#include <cstdio>
#include <mutex>

namespace romm::log {

namespace {

// Shared fallback sink: writes "<level>/<tag>: <message>" to stderr with a
// single-letter severity prefix, mirroring the "E/romm_...:" shape of
// Android's logcat output. Used until a platform registers its own sink, so
// diagnostics are never silently dropped.
class StderrSink final : public LogSink {
public:
    void log(Severity severity, const std::string& tag, const std::string& message) override {
        char level = 'E';
        switch (severity) {
            case Severity::Debug:
                level = 'D';
                break;
            case Severity::Info:
                level = 'I';
                break;
            case Severity::Warn:
                level = 'W';
                break;
            case Severity::Error:
                level = 'E';
                break;
        }
        std::fprintf(stderr, "%c/%s: %s\n", level, tag.c_str(), message.c_str());
    }
};

StderrSink& defaultSink() {
    static StderrSink sink;
    return sink;
}

std::mutex& registryMutex() {
    static std::mutex mutex;
    return mutex;
}

std::unique_ptr<LogSink>& activeSink() {
    static std::unique_ptr<LogSink> sink;
    return sink;
}

}  // namespace

void setSink(std::unique_ptr<LogSink> sink) {
    std::lock_guard<std::mutex> lock(registryMutex());
    activeSink() = std::move(sink);
}

LogSink& sink() {
    std::lock_guard<std::mutex> lock(registryMutex());
    if (activeSink() != nullptr) return *activeSink();
    return defaultSink();
}

}  // namespace romm::log

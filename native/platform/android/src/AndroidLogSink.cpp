// AndroidLogSink.cpp — Android's LogSink (Phase 7 Wave 1).
#include <native/platform/android/AndroidLogSink.h>

#include <android/log.h>
#include <memory>

namespace romm::android {

namespace {

int toPriority(romm::log::Severity severity) {
    switch (severity) {
        case romm::log::Severity::Debug:
            return ANDROID_LOG_DEBUG;
        case romm::log::Severity::Info:
            return ANDROID_LOG_INFO;
        case romm::log::Severity::Warn:
            return ANDROID_LOG_WARN;
        case romm::log::Severity::Error:
            return ANDROID_LOG_ERROR;
    }
    return ANDROID_LOG_ERROR;
}

// Registers AndroidLogSink as the engine's active log sink at library load
// time: static initializers in a shared library run when the library is
// loaded, before JNI_OnLoad, so jni_bridge.cpp stays untouched.
struct LogSinkRegistrar {
    LogSinkRegistrar() { romm::log::setSink(std::make_unique<AndroidLogSink>()); }
};
const LogSinkRegistrar kLogSinkRegistrar;

}  // namespace

void AndroidLogSink::log(romm::log::Severity severity, const std::string& tag,
                         const std::string& message) {
    __android_log_write(toPriority(severity), tag.c_str(), message.c_str());
}

}  // namespace romm::android

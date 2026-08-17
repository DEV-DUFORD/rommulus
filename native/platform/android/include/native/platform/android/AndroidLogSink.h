// AndroidLogSink.h — LogSink implementation backed by Android's log system.
//
// LINUX_X64.md sections 11/14, Phase 7 Wave 1: the only platform sink in
// Wave 1. It is registered as the engine's active sink by a static
// initializer in AndroidLogSink.cpp, so engine code never touches
// <android/log.h> directly and jni_bridge.cpp needs no changes.
#pragma once

#include <native/engine/LogSink.h>

namespace romm::android {

// Writes engine diagnostics through __android_log_write(), mapping engine
// severities onto ANDROID_LOG_* priorities one-to-one.
class AndroidLogSink final : public romm::log::LogSink {
public:
    void log(romm::log::Severity severity, const std::string& tag,
             const std::string& message) override;
};

}  // namespace romm::android

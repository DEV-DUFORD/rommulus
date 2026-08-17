// AndroidVideoSink.cpp — Android's VideoSink (Phase 7 Wave 4).
//
// Absorbs the software-frame logic that used to live in
// the legacy video_output.cpp (LIBRETRO_REFACTOR.md section 8.1):
// ANativeWindow_lock/unlock blits and per-geometry
// ANativeWindow_setBuffersGeometry. The 0RGB1555/RGB565/XRGB8888 ->
// RGBA_8888 row conversions moved to the neutral engine's
// romm::video::convertRow (Phase 7 Wave 9b) so they are host-testable and
// reusable by the future Linux player; the per-pixel math is unchanged.
// Behavior is unchanged; only the ownership boundary moved — the emulation
// session now talks to romm::video::sink() instead of constructing this
// class directly.
#include <native/platform/android/AndroidVideoSink.h>

#include <native/engine/LogSink.h>

#include "pixel_format.h"

#include <android/native_window.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <memory>

namespace romm::android {

namespace {

// Same logcat tag as the former VideoOutput, so existing diagnostics and
// log filters keep working through the engine's LogSink registry.
constexpr const char* kLogTag = "romm_video_output";

void logAt(romm::log::Severity severity, const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    romm::log::sink().log(severity, kLogTag, buffer);
}

// Registers AndroidVideoSink as the engine's active video sink at library
// load time: static initializers in a shared library run when the library
// is loaded, before JNI_OnLoad, so jni_bridge.cpp stays untouched.
struct VideoSinkRegistrar {
    VideoSinkRegistrar() { romm::video::setSink(std::make_unique<AndroidVideoSink>()); }
};
const VideoSinkRegistrar kVideoSinkRegistrar;

}  // namespace

AndroidVideoSink::~AndroidVideoSink() {
    detachWindow();
}

void AndroidVideoSink::attachWindow(romm::video::NativeWindowHandle window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = static_cast<ANativeWindow*>(window);
    // Force ANativeWindow_setBuffersGeometry to run again on the next frame,
    // since a fresh Surface has no geometry set yet.
    lastBufferWidth_ = 0;
    lastBufferHeight_ = 0;
}

void AndroidVideoSink::detachWindow() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void AndroidVideoSink::submitFrame(const void* data, unsigned width, unsigned height, size_t pitch,
                                    enum retro_pixel_format format) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return;
    if (data == nullptr) return;  // frame duplication: keep showing the last posted buffer
    if (width == 0 || height == 0) return;

    const auto bufferWidth = static_cast<int32_t>(width);
    const auto bufferHeight = static_cast<int32_t>(height);
    if (bufferWidth != lastBufferWidth_ || bufferHeight != lastBufferHeight_) {
        if (ANativeWindow_setBuffersGeometry(window_, bufferWidth, bufferHeight,
                                              WINDOW_FORMAT_RGBA_8888) != 0) {
            logAt(romm::log::Severity::Error, "ANativeWindow_setBuffersGeometry failed for %dx%d",
                  bufferWidth, bufferHeight);
            return;
        }
        lastBufferWidth_ = bufferWidth;
        lastBufferHeight_ = bufferHeight;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        // Transient failure (e.g. compositor busy). Drop this frame rather
        // than block the emulation thread for an unbounded time.
        return;
    }

    const auto* srcBytes = static_cast<const uint8_t*>(data);
    auto* dstBytes = static_cast<uint8_t*>(buffer.bits);
    const uint32_t rows = std::min(static_cast<uint32_t>(height), static_cast<uint32_t>(buffer.height));
    const uint32_t cols = std::min(static_cast<uint32_t>(width), static_cast<uint32_t>(buffer.width));
    const size_t dstStrideBytes = static_cast<size_t>(buffer.stride) * 4;

    for (uint32_t y = 0; y < rows; ++y) {
        const uint8_t* srcRow = srcBytes + static_cast<size_t>(y) * pitch;
        auto* dstRow = reinterpret_cast<uint32_t*>(dstBytes + static_cast<size_t>(y) * dstStrideBytes);
        romm::video::convertRow(format, srcRow, dstRow, cols);
    }

    ANativeWindow_unlockAndPost(window_);
}

}  // namespace romm::android

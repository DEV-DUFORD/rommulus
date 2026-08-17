// AndroidVideoSink.h — VideoSink implementation backed by the platform's
// native window.
//
// LINUX_X64.md sections 11.2/11.6/11.7, Phase 7 Wave 4: absorbs the
// software-frame path that used to live in the legacy video_output.cpp
// (LIBRETRO_REFACTOR.md section 8.1): native-window lock/unlock blits,
// per-geometry setBuffersGeometry, and the pixel-format conversions.
// Behavior is unchanged; only the ownership boundary moved — the emulation
// session now talks to romm::video::sink() instead of constructing this
// class directly.
//
// It is registered as the engine's active sink by a static initializer in
// AndroidVideoSink.cpp, so engine code never touches the platform window
// header directly and jni_bridge.cpp needs no changes beyond casting its
// platform window pointer to the engine's opaque handle.
#pragma once

#include <native/engine/VideoSink.h>

#include <cstdint>
#include <mutex>

struct ANativeWindow;

namespace romm::android {

class AndroidVideoSink final : public romm::video::VideoSink {
public:
    ~AndroidVideoSink() override;

    void attachWindow(romm::video::NativeWindowHandle window) override;
    void detachWindow() override;
    void submitFrame(const void* data, unsigned width, unsigned height, size_t pitch,
                     enum retro_pixel_format format) override;

private:
    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    int32_t lastBufferWidth_ = 0;
    int32_t lastBufferHeight_ = 0;
};

}  // namespace romm::android

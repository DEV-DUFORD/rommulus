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

#include <cstddef>
#include <cstdint>
#include <mutex>
#include <vector>

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
    uint32_t scaledSourceWidth_ = 0;
    uint32_t scaledSourceHeight_ = 0;
    uint32_t scaledDestinationWidth_ = 0;
    uint32_t scaledDestinationHeight_ = 0;
    std::vector<uint32_t> scaledSourceXs_;
    std::vector<uint32_t> scaledSourceYs_;
    std::vector<uint32_t> convertedSourceRow_;
    std::vector<uint32_t> expandedDestinationRow_;
};

}  // namespace romm::android

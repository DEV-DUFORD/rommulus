// video_output.h — converts the core's software framebuffer to a real
// on-screen ANativeWindow (LIBRETRO_REFACTOR.md section 8.1).
//
// Ownership/thread-safety contract:
//   - attachWindow()/detachWindow() are called from the UI thread whenever
//     EmulationActivity's Surface becomes available/is destroyed.
//   - submitFrame() is called only from the emulation thread, inside
//     EmulationSession's videoRefreshTrampoline.
//   - A mutex serializes all three so the UI thread can never release an
//     ANativeWindow the emulation thread is mid-blit on, and the emulation
//     thread never writes into a window that has already been released.
#pragma once

#include "libretro.h"

#include <android/native_window.h>
#include <cstddef>
#include <cstdint>
#include <mutex>

namespace romm {

class VideoOutput {
public:
    VideoOutput() = default;
    ~VideoOutput();

    VideoOutput(const VideoOutput&) = delete;
    VideoOutput& operator=(const VideoOutput&) = delete;

    // Takes ownership of an ANativeWindow reference already acquired by the
    // caller (e.g. via ANativeWindow_fromSurface). Releases any previously
    // attached window first. Pass nullptr to just detach.
    void attachWindow(ANativeWindow* window);

    // Releases the currently attached window, if any. Safe to call when
    // nothing is attached.
    void detachWindow();

    // Converts and blits one frame into the attached window, honoring
    // width/height/pitch and the negotiated pixel format. A null `data`
    // means "duplicate the last frame" (RETRO_ENVIRONMENT_GET_CAN_DUPE) —
    // this is a deliberate no-op, not an error, and must not clear the
    // screen. Never blocks for an unbounded time: a transient
    // ANativeWindow_lock failure just drops the frame.
    void submitFrame(const void* data, unsigned width, unsigned height, size_t pitch,
                      enum retro_pixel_format format);

private:
    std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    int32_t lastBufferWidth_ = 0;
    int32_t lastBufferHeight_ = 0;
};

}  // namespace romm

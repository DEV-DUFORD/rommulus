// frame_scheduler.h — fixed-rate pacing for the emulation thread.
//
// Deliberately simple for Phase 2's core-loading milestone: sleeps to
// maintain a target frame rate using a monotonic clock, tracking drift so
// occasional slow frames don't compound. Video/audio-aware pacing
// refinements land with the video/audio pipeline commit
// (LIBRETRO_REFACTOR.md section 8).
#pragma once

#include <chrono>
#include <thread>

namespace romm {

class FrameScheduler {
public:
    explicit FrameScheduler(double fps) { setFps(fps); }

    void setFps(double fps) {
        fps_ = fps;
        frameDuration_ = std::chrono::duration<double>(1.0 / fps);
        nextFrameTime_ = std::chrono::steady_clock::now();
    }

    // Blocks (if needed) until the next frame's scheduled time, then advances
    // the schedule by exactly one frame duration. Never sleeps backwards: if
    // the caller is already behind schedule, returns immediately and resets
    // the schedule from now to avoid unbounded catch-up bursts.
    void waitForNextFrame() {
        auto now = std::chrono::steady_clock::now();
        if (now < nextFrameTime_) {
            std::this_thread::sleep_until(nextFrameTime_);
            nextFrameTime_ += std::chrono::duration_cast<std::chrono::steady_clock::duration>(frameDuration_);
        } else {
            // Behind schedule (e.g. after a pause or a slow frame) — resync
            // instead of accumulating an ever-growing backlog of frames.
            nextFrameTime_ = now + std::chrono::duration_cast<std::chrono::steady_clock::duration>(frameDuration_);
        }
    }

    double fps() const { return fps_; }

private:
    double fps_ = 60.0;
    std::chrono::duration<double> frameDuration_{1.0 / 60.0};
    std::chrono::steady_clock::time_point nextFrameTime_;
};

}  // namespace romm

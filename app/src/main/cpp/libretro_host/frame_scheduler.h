// frame_scheduler.h — fixed-rate, low-latency pacing for the emulation thread.
#pragma once

#include <algorithm>
#include <chrono>
#include <thread>

namespace romm {

class FrameScheduler {
public:
    explicit FrameScheduler(double fps) { setFps(fps); }

    void setFps(double fps) {
        fps_ = fps;
        frameDuration_ = std::chrono::duration<double>(1.0 / fps);
        estimatedWorkDuration_ = frameDuration_ * 0.5;
        nextFrameTime_ = std::chrono::steady_clock::now();
    }

    // Called immediately before retro_run(). Delays input polling and frame
    // production within the current interval while retaining enough measured
    // headroom for the core to finish. This reduces the age of input in the
    // presented frame without changing emulation speed.
    void waitForNextFrame(bool resetIfBehind = true) {
        auto now = std::chrono::steady_clock::now();
        const auto duration =
            std::chrono::duration_cast<std::chrono::steady_clock::duration>(frameDuration_);

        if (resetIfBehind && now - nextFrameTime_ > duration) {
            nextFrameTime_ = now;
        }

        const auto availableHeadroom = frameDuration_ - estimatedWorkDuration_ - safetyMargin_;
        const auto frameDelay = availableHeadroom > std::chrono::duration<double>::zero()
            ? std::min(availableHeadroom, maxFrameDelay_)
            : std::chrono::duration<double>::zero();
        const auto delayedStart = nextFrameTime_ +
            std::chrono::duration_cast<std::chrono::steady_clock::duration>(frameDelay);
        if (now < delayedStart) {
            std::this_thread::sleep_until(delayedStart);
        }
        nextFrameTime_ += duration;
    }

    // A decaying peak reacts to an expensive frame immediately but releases
    // its extra headroom gradually, avoiding oscillation around the deadline.
    void reportFrameWorkDuration(std::chrono::steady_clock::duration workDuration) {
        const std::chrono::duration<double> sample = workDuration;
        estimatedWorkDuration_ = std::max(sample, estimatedWorkDuration_ * 0.95);
    }

    double fps() const { return fps_; }

private:
    static constexpr std::chrono::duration<double> safetyMargin_{0.002};
    static constexpr std::chrono::duration<double> maxFrameDelay_{0.006};

    double fps_ = 60.0;
    std::chrono::duration<double> frameDuration_{1.0 / 60.0};
    std::chrono::duration<double> estimatedWorkDuration_{1.0 / 120.0};
    std::chrono::steady_clock::time_point nextFrameTime_;
};

}  // namespace romm

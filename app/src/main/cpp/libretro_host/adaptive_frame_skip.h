// adaptive_frame_skip.h — bounded, deadline-based video frame skipping.
#pragma once

#include <algorithm>
#include <chrono>
#include <cmath>

namespace romm {

class AdaptiveFrameSkip {
public:
    explicit AdaptiveFrameSkip(double fps)
        : frameDuration_(1.0 / (fps > 0.0 ? fps : 60.0)) {}

    bool shouldRenderFrame() {
        if (framesLeftToSkip_ == 0) return true;
        --framesLeftToSkip_;
        return false;
    }

    void reportFrameWorkDuration(
            std::chrono::steady_clock::duration workDuration,
            bool videoRendered) {
        if (!videoRendered) return;

        const std::chrono::duration<double> sample = workDuration;
        if (sample <= frameDuration_ * kOverBudgetThreshold) {
            consecutiveSlowFrames_ = 0;
            return;
        }

        ++consecutiveSlowFrames_;
        if (consecutiveSlowFrames_ < kActivationFrames) return;

        const auto requiredIntervals =
            static_cast<unsigned>(std::ceil(sample.count() / frameDuration_.count()));
        framesLeftToSkip_ = std::clamp(
            requiredIntervals > 0 ? requiredIntervals - 1 : 0,
            1u,
            kMaxConsecutiveSkips);
    }

private:
    static constexpr double kOverBudgetThreshold = 1.05;
    static constexpr unsigned kActivationFrames = 2;
    static constexpr unsigned kMaxConsecutiveSkips = 4;

    std::chrono::duration<double> frameDuration_;
    unsigned consecutiveSlowFrames_ = 0;
    unsigned framesLeftToSkip_ = 0;
};

}  // namespace romm

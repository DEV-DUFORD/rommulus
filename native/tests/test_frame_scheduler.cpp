// test_frame_scheduler.cpp — fixed-rate pacing, drift recovery, and the
// work-duration headroom clamp. Timing checks are deliberately tolerant:
// sleep_until overshoots under host load but never undershoots.
#include "frame_scheduler.h"

#include "romm_test.h"

#include <chrono>
#include <thread>

using namespace std::chrono;
using romm::FrameScheduler;

namespace {

double elapsedMs(steady_clock::time_point start) {
    return duration<double>(steady_clock::now() - start).count() * 1000.0;
}

void testFpsAccessors() {
    FrameScheduler sched(60.0);
    CHECK_EQ(sched.fps(), 60.0);
    sched.setFps(30.0);
    CHECK_EQ(sched.fps(), 30.0);
}

void testFixedRatePacing() {
    // 30 frames at 60fps take ~489ms of wall time: the first call sleeps only
    // the 6ms frame-delay budget, the remaining 29 sleep the full 16.67ms
    // frame step.
    FrameScheduler sched(60.0);
    auto start = steady_clock::now();
    for (int i = 0; i < 30; ++i) sched.waitForNextFrame();
    const double ms = elapsedMs(start);
    // One-sided tolerance: sleep_until overshoots under host load but never
    // undershoots, so the lower bound is exact. The upper bound absorbs
    // sustained per-call overshoot (the behind-reset re-anchors the schedule,
    // capping cumulative drift) while still catching a no-op scheduler
    // (~0ms) or a half-rate one (~245ms).
    CHECK(ms >= 400.0);
    CHECK(ms <= 800.0);
}

void testDriftRecovery() {
    // Falling more than one frame behind must reset the schedule instead of
    // bursting frames to catch up: the deadline snaps to "now" and the next
    // call delays by exactly one frame's pacing budget.
    FrameScheduler sched(60.0);
    sched.waitForNextFrame();
    std::this_thread::sleep_for(milliseconds(100));  // ~6 frames behind

    // The deadline must be stale (well in the past) before recovery.
    CHECK(steady_clock::now() - sched.nextFrameTime() > milliseconds(50));

    auto start = steady_clock::now();
    sched.waitForNextFrame();
    const double ms = elapsedMs(start);

    // No catch-up burst: the call must not sleep for the accumulated deficit.
    CHECK(ms < 50.0);
    // ...but it must sleep for the full 6ms frame-delay budget. That only
    // happens if the reset snapped the stale deadline to "now"; without the
    // reset the deadline is in the past and the call returns in ~0ms.
    // (sleep_until never wakes early, so this bound is CI-safe.)
    CHECK(ms >= 5.0);

    // The reset deadline must be one frame step in the future (16.67ms step
    // minus the ~6ms delay already slept), not left stale in the past.
    const auto delta = sched.nextFrameTime() - steady_clock::now();
    CHECK(delta > milliseconds(1));
    CHECK(delta <= milliseconds(20));
}

void testBoundedCatchUp() {
    FrameScheduler sched(60.0);
    sched.waitForNextFrame();
    std::this_thread::sleep_for(milliseconds(350));

    sched.waitForNextFrame(false, milliseconds(200));

    const auto debt = steady_clock::now() - sched.nextFrameTime();
    CHECK(debt > milliseconds(150));
    CHECK(debt <= milliseconds(210));
}

void testExpensiveFrameClampsDelayToZero() {
    // Once the measured frame work exceeds the frame budget minus the safety
    // margin, the scheduler stops delaying (frameDelay clamps to zero).
    FrameScheduler sched(60.0);
    sched.reportFrameWorkDuration(milliseconds(50));
    sched.waitForNextFrame();  // establish a deadline
    auto start = steady_clock::now();
    sched.waitForNextFrame();
    const double ms = elapsedMs(start);
    CHECK(ms < 30.0);
}

}  // namespace

int main() {
    testFpsAccessors();
    testFixedRatePacing();
    testDriftRecovery();
    testBoundedCatchUp();
    testExpensiveFrameClampsDelayToZero();
    return rommtest::finish("test_frame_scheduler");
}

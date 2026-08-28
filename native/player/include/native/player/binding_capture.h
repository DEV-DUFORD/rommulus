// binding_capture.h — SDL-free capture state machine for the player-side
// controller-binding editor.
//
// Ported from Android's ControllerBindingCaptureCoordinator (one-shot
// lifecycle) plus ControllerCaptureDialog's hold-Back-to-clear:
//
//   Idle -> begin() -> AwaitingNeutral -> Capturing -> exactly one terminal
//   state: Result | Cancelled | TimedOut | Cleared | NoDeviceAssigned.
//
// Android semantics preserved by this port:
//  - Neutral gate: an axis counts as neutral at |value| < kNeutralThreshold
//    (0.25). Capture only starts once every observed button/axis is neutral,
//    so the press that opened the row cannot become the binding. A noisy
//    axis that never returns to neutral can never be captured.
//  - Enter threshold: an axis must first be observed neutral, then cross
//    kEnterThreshold (0.65) in either direction to be accepted.
//  - First press wins: the first new button press during Capturing is the
//    result for digital targets. A button press always beats a stick
//    deflection — including one observed in the same frame (button levels
//    are processed before axis values, mirroring Android's
//    keyPressedDuringCapture priority). Once a key press has been observed
//    during a digital capture, stick deflections are ignored for the rest of
//    the session.
//  - A button already held when begin() is called seeds the baseline: it
//    neither blocks the neutral gate nor becomes the binding (the row-opening
//    press's ACTION_DOWN passed dispatch before capture began on Android).
//  - Back: a quick Back cancels (kCancelled); holding Back for
//    kHeldBackClearMs (600) clears the selected slot's binding (kCleared) —
//    ControllerCaptureDialog's HOLD_BACK_TO_CLEAR_MAPPING_MILLIS.
//  - Timeout: no qualifying input within kDefaultTimeoutMs (15 s) ends the
//    capture with kTimedOut; nothing is saved.
//  - Device loss mid-capture drops that device's state and cancels when no
//    eligible device remains.
//
// The player polls gamepad LEVELS each frame rather than receiving events,
// so sample() takes current levels and derives edges internally (the first
// sample after begin() seeds the button baseline). advanceTime() drives an
// internal clock so the timeout and the held-Back clear fire deterministically
// — unit-testable on the host with no SDL and no real timer (the capture
// also arms AwaitingNeutral -> Capturing on a clock tick when nothing is
// observed, matching Android's "arm on the next coroutine turn").
#pragma once

#include <cmath>
#include <cstdint>
#include <map>
#include <set>
#include <utility>

#include "native/player/binding_table.h"

namespace romm::player {

// What kind of physical input the caller wants to capture. The editor's 12
// RetroPad slots are digital targets; analog is supported for parity with
// Android (a stick/trigger deflection yields a full axis, no polarity).
enum class CaptureTarget { kDigital, kAnalog };

// A captured physical input (the Result state's payload). Mirrors Android's
// PhysicalBinding Key / Axis / AxisDirection.
struct CapturedBinding {
    enum class Kind : int { kButton = 0, kAxis, kAxisDirection };

    Kind kind = Kind::kButton;
    PadButton button = PadButton::kSouth;  // kButton
    PadAxis axis = PadAxis::kLeftX;        // kAxis / kAxisDirection
    int polarity = 1;                      // kAxisDirection: +1 or -1

    static CapturedBinding ofButton(PadButton b) {
        CapturedBinding r;
        r.kind = Kind::kButton;
        r.button = b;
        return r;
    }
    static CapturedBinding ofAxis(PadAxis a) {
        CapturedBinding r;
        r.kind = Kind::kAxis;
        r.axis = a;
        return r;
    }
    static CapturedBinding axisDirection(PadAxis a, int polarity) {
        CapturedBinding r;
        r.kind = Kind::kAxisDirection;
        r.axis = a;
        r.polarity = polarity < 0 ? -1 : 1;
        return r;
    }
};

enum class CaptureState {
    kIdle,             // No capture in progress.
    kAwaitingNeutral,  // Waiting for all gamepad buttons/axes to be neutral.
    kCapturing,        // Neutral, waiting for the first qualifying input.
    kResult,           // Terminal: a binding was captured (result() is valid).
    kCancelled,        // Terminal: quick Back / tab change / stop.
    kTimedOut,         // Terminal: no qualifying input within the timeout.
    kCleared,          // Terminal: held-Back clear of the selected slot.
    kNoDeviceAssigned, // Terminal: no eligible controller is connected.
};

class BindingCaptureCoordinator {
public:
    static constexpr float kNeutralThreshold = 0.25f;
    static constexpr float kEnterThreshold = 0.65f;
    static constexpr int64_t kDefaultTimeoutMs = 15000;
    static constexpr int64_t kHeldBackClearMs = 600;

    explicit BindingCaptureCoordinator(int64_t timeoutMs = kDefaultTimeoutMs)
        : timeoutMs_(timeoutMs) {}

    CaptureState state() const { return state_; }
    bool isActive() const {
        return state_ == CaptureState::kAwaitingNeutral ||
               state_ == CaptureState::kCapturing;
    }
    int slotIndex() const { return slotIndex_; }
    CaptureTarget target() const { return target_; }
    // Valid only in kResult.
    const CapturedBinding* result() const {
        return state_ == CaptureState::kResult ? &result_ : nullptr;
    }

    // Milliseconds left on the capture timeout (0 when not actively
    // capturing) — the overlay shows it as a countdown.
    int64_t remainingTimeoutMs() const {
        if (!isActive()) return 0;
        const int64_t remaining = timeoutDeadline_ - nowMs_;
        return remaining > 0 ? remaining : 0;
    }

    // Begins capturing for `slotIndex` across the given eligible devices
    // (player port indices). With no eligible device the capture ends
    // immediately with kNoDeviceAssigned. Replaces any in-progress capture.
    void begin(int slotIndex, const int* deviceIds, int deviceCount,
               CaptureTarget target) {
        resetInternal();
        slotIndex_ = slotIndex;
        target_ = target;
        if (deviceCount <= 0) {
            state_ = CaptureState::kNoDeviceAssigned;
            return;
        }
        for (int i = 0; i < deviceCount; ++i) {
            eligibleDevices_.insert(deviceIds[i]);
        }
        state_ = CaptureState::kAwaitingNeutral;
        timeoutDeadline_ = nowMs_ + timeoutMs_;
    }

    // Explicit cancel (quick Back, tab change, activity stop). No-op when not
    // actively capturing.
    void cancel() {
        if (!isActive()) return;
        resetInternal();
        state_ = CaptureState::kCancelled;
    }

    // Back press/release edges (non-repeat only). A quick release cancels;
    // holding for kHeldBackClearMs clears the selected slot's binding.
    void onBackDown() {
        if (!isActive()) return;
        backHeld_ = true;
        backHeldSince_ = nowMs_;
    }

    void onBackUp() {
        if (!backHeld_) return;
        backHeld_ = false;
        if (state_ == CaptureState::kCleared) return;  // clear already fired
        cancel();
    }

    // A device disconnected mid-capture: drop its state; cancel when no
    // eligible device remains.
    void onDeviceRemoved(int deviceId) {
        if (eligibleDevices_.erase(deviceId) == 0) return;
        for (auto it = buttonState_.begin(); it != buttonState_.end();) {
            if (it->first.first == deviceId) {
                it = buttonState_.erase(it);
            } else {
                ++it;
            }
        }
        for (auto it = axisValue_.begin(); it != axisValue_.end();) {
            if (it->first.first == deviceId) {
                it = axisValue_.erase(it);
            } else {
                ++it;
            }
        }
        for (auto it = axisSeenNeutral_.begin(); it != axisSeenNeutral_.end();) {
            if (it->first == deviceId) {  // set of (deviceId, axisId) pairs
                it = axisSeenNeutral_.erase(it);
            } else {
                ++it;
            }
        }
        if (eligibleDevices_.empty() && isActive()) {
            cancel();
        }
    }

    // Advances the internal clock by `ms` and fires the held-Back clear or
    // the timeout when their deadlines pass. Also arms AwaitingNeutral ->
    // Capturing when everything observed is neutral (Android's next-turn
    // arming, which happens even with no further input).
    void advanceTime(int64_t ms) {
        if (ms < 0) ms = 0;
        nowMs_ += ms;
        checkNeutralAndAdvance();
        if (backHeld_ && isActive() &&
            nowMs_ - backHeldSince_ >= kHeldBackClearMs) {
            resetInternal();
            state_ = CaptureState::kCleared;
            return;
        }
        if (isActive() && nowMs_ >= timeoutDeadline_) {
            resetInternal();
            state_ = CaptureState::kTimedOut;
        }
    }

    // One frame of LEVEL samples from one eligible device. Button levels are
    // processed before axis values so a new button press wins over a stick
    // deflection in the same frame (Android's keyPressedDuringCapture rule).
    // Samples from non-eligible devices are ignored.
    void sample(int deviceId, const PadButton* buttons, const bool* buttonPressed,
                int buttonCount, const PadAxis* axes, const float* axisValues,
                int axisCount) {
        if (!isActive() || eligibleDevices_.find(deviceId) == eligibleDevices_.end()) {
            return;
        }

        // --- Buttons: derive edges from levels. The first sample after
        // begin() seeds the baseline: a button held at that moment neither
        // blocks the neutral gate nor becomes the binding.
        for (int i = 0; i < buttonCount; ++i) {
            const DeviceControlKey key{deviceId, static_cast<int>(buttons[i])};
            const bool pressed = buttonPressed[i];
            auto it = buttonState_.find(key);
            if (it == buttonState_.end()) {
                buttonState_.emplace(
                    key, ButtonLevel{pressed /* wasPressed */, false /* observedPress */});
                continue;
            }
            ButtonLevel& level = it->second;
            if (pressed && !level.wasPressed) {
                // A new press.
                level.observedPress = true;
                if (target_ == CaptureTarget::kDigital) {
                    // An armed button press indicates the user intends a
                    // button; stick deflections must not capture this row.
                    keyPressedDuringCapture_ = true;
                    // Digital targets: the first new press is the result
                    // (first-press-wins). Analog targets ignore key presses
                    // during Capturing entirely (Android parity).
                    if (state_ == CaptureState::kCapturing) {
                        finishWith(CapturedBinding::ofButton(buttons[i]));
                        return;
                    }
                }
            } else if (!pressed && level.observedPress) {
                level.observedPress = false;
            }
            level.wasPressed = pressed;
        }

        // --- Axes: record every sample so neutral gating sees current state.
        for (int i = 0; i < axisCount; ++i) {
            const DeviceControlKey key{deviceId, static_cast<int>(axes[i])};
            const float value = axisValues[i];
            axisValue_[key] = value;
            if (std::fabs(value) < kNeutralThreshold) {
                axisSeenNeutral_.insert(key);
            }
        }

        if (state_ == CaptureState::kAwaitingNeutral) {
            checkNeutralAndAdvance();
            return;
        }

        // Capturing phase. A prior key press on a digital target blocks stick
        // capture for the rest of the session.
        if (target_ == CaptureTarget::kDigital && keyPressedDuringCapture_) return;

        if (target_ == CaptureTarget::kAnalog || axisCount <= 1) {
            // Analog targets (and single-axis sample sets): the first axis
            // that crosses the enter threshold wins.
            for (int i = 0; i < axisCount; ++i) {
                const DeviceControlKey key{deviceId, static_cast<int>(axes[i])};
                if (!seenNeutral(key)) continue;  // noisy axis: never captured
                const float value = axisValue_[key];
                if (std::fabs(value) < kEnterThreshold) continue;
                if (target_ == CaptureTarget::kAnalog) {
                    finishWith(CapturedBinding::ofAxis(axes[i]));
                } else {
                    finishWith(CapturedBinding::axisDirection(
                        axes[i], value > 0 ? 1 : -1));
                }
                return;
            }
            return;
        }

        // Digital multi-axis: capture only the DOMINANT qualifying
        // deflection, so a stick push is attributed to its dominant axis and
        // two directions can never share a captured (axis, polarity).
        int best = -1;
        float bestMagnitude = 0.0f;
        for (int i = 0; i < axisCount; ++i) {
            const DeviceControlKey key{deviceId, static_cast<int>(axes[i])};
            if (!seenNeutral(key)) continue;
            const float value = axisValue_[key];
            const float magnitude = std::fabs(value);
            if (magnitude < kEnterThreshold) continue;
            if (best < 0 || magnitude > bestMagnitude) {
                best = i;
                bestMagnitude = magnitude;
            }
        }
        if (best >= 0) {
            const float value =
                axisValue_[DeviceControlKey{deviceId, static_cast<int>(axes[best])}];
            finishWith(CapturedBinding::axisDirection(axes[best], value > 0 ? 1 : -1));
        }
    }

private:
    using DeviceControlKey = std::pair<int, int>;  // (deviceId, control id)

    struct ButtonLevel {
        bool wasPressed;      // level on the previous sample (baseline at first)
        bool observedPress;   // a press observed since begin() still held down
    };

    bool seenNeutral(const DeviceControlKey& key) const {
        return axisSeenNeutral_.find(key) != axisSeenNeutral_.end();
    }

    // AwaitingNeutral -> Capturing once every observed button/axis is neutral.
    void checkNeutralAndAdvance() {
        if (state_ != CaptureState::kAwaitingNeutral) return;
        for (const auto& entry : buttonState_) {
            if (entry.second.observedPress) return;
        }
        for (const auto& entry : axisValue_) {
            if (std::fabs(entry.second) >= kNeutralThreshold) return;
        }
        state_ = CaptureState::kCapturing;
    }

    void finishWith(CapturedBinding binding) {
        result_ = binding;
        resetInternal();
        state_ = CaptureState::kResult;
    }

    // Clears all per-capture state (Android's cancelInternal). Never touches
    // the clock.
    void resetInternal() {
        eligibleDevices_.clear();
        buttonState_.clear();
        axisValue_.clear();
        axisSeenNeutral_.clear();
        keyPressedDuringCapture_ = false;
        backHeld_ = false;
        backHeldSince_ = 0;
    }

    int64_t timeoutMs_;
    int64_t nowMs_ = 0;
    int64_t timeoutDeadline_ = 0;
    CaptureState state_ = CaptureState::kIdle;
    int slotIndex_ = -1;
    CaptureTarget target_ = CaptureTarget::kDigital;
    CapturedBinding result_{};

    std::set<int> eligibleDevices_;
    std::map<DeviceControlKey, ButtonLevel> buttonState_;
    std::map<DeviceControlKey, float> axisValue_;
    std::set<DeviceControlKey> axisSeenNeutral_;
    bool keyPressedDuringCapture_ = false;
    bool backHeld_ = false;
    int64_t backHeldSince_ = 0;
};

}  // namespace romm::player

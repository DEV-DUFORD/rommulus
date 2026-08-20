// test_controller_capture.cpp — host tests for the SDL-free binding capture
// state machine (native/player/include/native/player/binding_capture.h),
// ported from Android's ControllerBindingCaptureCoordinator +
// ControllerCaptureDialog hold-Back-to-clear. Covers: neutral gate, enter
// threshold, first-press-wins (button beats stick in the same frame), noisy
// axes, 15 s timeout, quick-Back cancel, held-Back clear, device loss, and
// analog vs digital targets.
#include "romm_test.h"

#include <vector>

#include "native/player/binding_capture.h"

namespace {

using romm::player::BindingCaptureCoordinator;
using romm::player::CapturedBinding;
using romm::player::CaptureState;
using romm::player::CaptureTarget;
using romm::player::PadAxis;
using romm::player::PadButton;

const PadButton kButtons[romm::player::kPadButtonCount] = {
    PadButton::kSouth,      PadButton::kEast,     PadButton::kWest,
    PadButton::kNorth,      PadButton::kBack,     PadButton::kStart,
    PadButton::kLeftShoulder, PadButton::kRightShoulder, PadButton::kDpadUp,
    PadButton::kDpadDown,   PadButton::kDpadLeft, PadButton::kDpadRight,
    PadButton::kLeftStick,  PadButton::kRightStick,
};

// Feeds one frame of LEVEL samples for `device`: the listed buttons held
// down plus the four stick axes at the given values (triggers rest at 0).
void feed(BindingCaptureCoordinator& c, int device,
          const std::vector<PadButton>& down = {}, float leftX = 0.0f,
          float leftY = 0.0f, float rightX = 0.0f, float rightY = 0.0f) {
    bool pressed[romm::player::kPadButtonCount] = {};
    for (const PadButton b : down) {
        pressed[static_cast<int>(b)] = true;
    }
    const PadAxis axes[] = {PadAxis::kLeftX, PadAxis::kLeftY, PadAxis::kRightX,
                            PadAxis::kRightY};
    const float values[] = {leftX, leftY, rightX, rightY};
    c.sample(device, kButtons, pressed, romm::player::kPadButtonCount, axes, values, 4);
}

// Arms a fresh coordinator into kCapturing: begin with device 0 eligible and
// feed one all-neutral frame (the neutral gate passes on the first sample).
BindingCaptureCoordinator arm(CaptureTarget target = CaptureTarget::kDigital) {
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, target);
    feed(c, device);  // all neutral -> Capturing
    return c;
}

void testNoDeviceAssigned() {
    BindingCaptureCoordinator c;
    c.begin(3, nullptr, 0, CaptureTarget::kDigital);
    CHECK(c.state() == CaptureState::kNoDeviceAssigned);
    CHECK(!c.isActive());
    // Terminal: input and time no longer do anything.
    feed(c, 0, {PadButton::kSouth});
    c.advanceTime(BindingCaptureCoordinator::kDefaultTimeoutMs + 1000);
    CHECK(c.state() == CaptureState::kNoDeviceAssigned);
    CHECK(c.result() == nullptr);
}

void testArmsOnTickWithoutInput() {
    // Android arms "on the next coroutine turn" even with no input observed.
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    CHECK(c.state() == CaptureState::kAwaitingNeutral);
    c.advanceTime(16);
    CHECK(c.state() == CaptureState::kCapturing);
}

void testHeldButtonAtBeginIsBaseline() {
    // A button held when capture begins (the row-opening press) seeds the
    // baseline: it neither blocks the neutral gate nor becomes the binding.
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c, device, {PadButton::kSouth});  // held at begin -> baseline
    CHECK(c.state() == CaptureState::kCapturing);  // not blocked by it
    feed(c, device, {});                       // release (no edge expected)
    CHECK(c.state() == CaptureState::kCapturing);
    feed(c, device, {PadButton::kEast});       // the NEXT new press wins
    CHECK(c.state() == CaptureState::kResult);
    const CapturedBinding* r = c.result();
    CHECK(r != nullptr && r->kind == CapturedBinding::Kind::kButton &&
          r->button == PadButton::kEast);
}

void testNewPressDuringAwaitingNeutralBlocksAndArmsKeyPriority() {
    // A non-neutral axis holds the gate; a button pressed while blocked is
    // an observed press (blocks until released) and arms key-priority for
    // digital targets (sticks must not capture this row afterwards).
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c, device, {}, /*leftX=*/0.9f);  // axis deflected: stays awaiting
    CHECK(c.state() == CaptureState::kAwaitingNeutral);
    feed(c, device, {PadButton::kWest}, /*leftX=*/0.9f);  // new press, blocked
    CHECK(c.state() == CaptureState::kAwaitingNeutral);
    feed(c, device, {PadButton::kWest});                  // axis neutral, held
    CHECK(c.state() == CaptureState::kAwaitingNeutral);   // observed press blocks
    feed(c, device, {});                                  // release -> armed
    CHECK(c.state() == CaptureState::kCapturing);
    // Key priority: a stick deflection must NOT capture this digital row.
    feed(c, device, {}, /*leftX=*/0.8f);
    CHECK(c.state() == CaptureState::kCapturing);
    // ...and the capture eventually times out with nothing saved.
    c.advanceTime(BindingCaptureCoordinator::kDefaultTimeoutMs);
    CHECK(c.state() == CaptureState::kTimedOut);
    CHECK(c.result() == nullptr);
}

void testFirstPressWinsAndButtonBeatsStickSameFrame() {
    BindingCaptureCoordinator c = arm();
    feed(c, 0, {PadButton::kSouth});  // first new press is the result
    CHECK(c.state() == CaptureState::kResult);
    const CapturedBinding* r = c.result();
    CHECK(r != nullptr && r->kind == CapturedBinding::Kind::kButton &&
          r->button == PadButton::kSouth);
    // Terminal: further input is ignored.
    feed(c, 0, {PadButton::kEast});
    CHECK(c.state() == CaptureState::kResult);
    CHECK(r->button == PadButton::kSouth);

    // Same-frame race: a new button press beats a stick deflection.
    BindingCaptureCoordinator c2 = arm();
    feed(c2, 0, {PadButton::kStart}, /*leftX=*/0.9f);
    CHECK(c2.state() == CaptureState::kResult);
    const CapturedBinding* r2 = c2.result();
    CHECK(r2 != nullptr && r2->kind == CapturedBinding::Kind::kButton &&
          r2->button == PadButton::kStart);
}

void testAxisNeutralGateAndEnterThreshold() {
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c, device, {}, /*leftX=*/0.9f);  // never seen neutral yet
    CHECK(c.state() == CaptureState::kAwaitingNeutral);  // gate holds
    feed(c, device, {}, /*leftX=*/0.0f);  // returns to neutral -> armed
    CHECK(c.state() == CaptureState::kCapturing);
    feed(c, device, {}, /*leftX=*/0.6f);  // below enter threshold: ignored
    CHECK(c.state() == CaptureState::kCapturing);
    feed(c, device, {}, /*leftX=*/0.7f);  // crosses 0.65 -> captured
    CHECK(c.state() == CaptureState::kResult);
    const CapturedBinding* r = c.result();
    CHECK(r != nullptr && r->kind == CapturedBinding::Kind::kAxisDirection &&
          r->axis == PadAxis::kLeftX && r->polarity == 1);

    // Negative deflection captures the negative half.
    BindingCaptureCoordinator c2;
    c2.begin(1, &device, 1, CaptureTarget::kDigital);
    feed(c2, device, {}, /*leftX=*/0.0f, /*leftY=*/0.0f);
    CHECK(c2.state() == CaptureState::kCapturing);
    feed(c2, device, {}, /*leftX=*/0.0f, /*leftY=*/-0.7f);
    CHECK(c2.state() == CaptureState::kResult);
    const CapturedBinding* r2 = c2.result();
    CHECK(r2 != nullptr && r2->kind == CapturedBinding::Kind::kAxisDirection &&
          r2->axis == PadAxis::kLeftY && r2->polarity == -1);
}

void testNoisyAxisNeverCaptures() {
    // An axis that is never observed neutral cannot be captured, no matter
    // how far it deflects — and it holds the neutral gate too, so the capture
    // times out with nothing saved.
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    for (int frame = 0; frame < 10; ++frame) {
        feed(c, device, {}, /*leftX=*/0.95f);  // deflected from the first sample
        CHECK(c.state() == CaptureState::kAwaitingNeutral);
    }
    c.advanceTime(BindingCaptureCoordinator::kDefaultTimeoutMs);
    CHECK(c.state() == CaptureState::kTimedOut);
    CHECK(c.result() == nullptr);
}

void testAnalogTargetCapturesFullAxis() {
    // Analog targets capture a full axis (no polarity) and are NOT gated by
    // key priority.
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(2, &device, 1, CaptureTarget::kAnalog);
    feed(c, device, {}, /*leftX=*/0.0f, /*leftY=*/0.0f, /*rightX=*/0.0f);
    CHECK(c.state() == CaptureState::kCapturing);
    // Key presses are ignored during an analog capture (Android parity) —
    // neither captured nor gating the stick.
    feed(c, device, {PadButton::kSouth}, 0.0f, 0.0f, 0.0f);
    CHECK(c.state() == CaptureState::kCapturing);
    feed(c, device, {}, /*leftX=*/0.0f, /*leftY=*/0.0f, /*rightX=*/0.8f);
    CHECK(c.state() == CaptureState::kResult);
    const CapturedBinding* r = c.result();
    CHECK(r != nullptr && r->kind == CapturedBinding::Kind::kAxis &&
          r->axis == PadAxis::kRightX);
}

void testDigitalMultiAxisDominantWins() {
    // With two qualifying deflections in one frame, the dominant axis (largest
    // |value|) is captured — so two directions never share a half-axis.
    BindingCaptureCoordinator c = arm();
    feed(c, 0, {}, /*leftX=*/0.7f, /*leftY=*/0.0f, /*rightX=*/0.9f);
    CHECK(c.state() == CaptureState::kResult);
    const CapturedBinding* r = c.result();
    CHECK(r != nullptr && r->kind == CapturedBinding::Kind::kAxisDirection &&
          r->axis == PadAxis::kRightX && r->polarity == 1);
}

void testTimeout() {
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c, device);  // arm
    CHECK(c.state() == CaptureState::kCapturing);
    c.advanceTime(BindingCaptureCoordinator::kDefaultTimeoutMs - 1);
    CHECK(c.isActive());
    c.advanceTime(1);  // crosses the 15 s deadline
    CHECK(c.state() == CaptureState::kTimedOut);
    CHECK(c.result() == nullptr);

    // The countdown accessor tracks the remaining time.
    BindingCaptureCoordinator c2;
    c2.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c2, device);
    c2.advanceTime(5000);
    CHECK_EQ(c2.remainingTimeoutMs(), BindingCaptureCoordinator::kDefaultTimeoutMs - 5000);
}

void testQuickBackCancels() {
    BindingCaptureCoordinator c = arm();
    c.cancel();
    CHECK(c.state() == CaptureState::kCancelled);
    CHECK(!c.isActive());

    // Back press released before the hold threshold cancels (not clears).
    BindingCaptureCoordinator c2 = arm();
    c2.onBackDown();
    c2.advanceTime(BindingCaptureCoordinator::kHeldBackClearMs - 100);
    CHECK(c2.state() == CaptureState::kCapturing);  // still capturing
    c2.onBackUp();
    CHECK(c2.state() == CaptureState::kCancelled);
}

void testHeldBackClears() {
    BindingCaptureCoordinator c = arm();
    c.onBackDown();
    c.advanceTime(BindingCaptureCoordinator::kHeldBackClearMs - 1);
    CHECK(c.isActive());  // not yet
    c.advanceTime(1);     // crosses the 600 ms hold threshold
    CHECK(c.state() == CaptureState::kCleared);
    CHECK(!c.isActive());

    // The held clear works from AwaitingNeutral too (the dialog is up either
    // way), and a release after the clear fired is a no-op.
    BindingCaptureCoordinator c2;
    const int device = 0;
    c2.begin(4, &device, 1, CaptureTarget::kDigital);
    feed(c2, device, {}, /*leftX=*/0.9f);  // held in AwaitingNeutral
    CHECK(c2.state() == CaptureState::kAwaitingNeutral);
    c2.onBackDown();
    c2.advanceTime(BindingCaptureCoordinator::kHeldBackClearMs);
    CHECK(c2.state() == CaptureState::kCleared);
    c2.onBackUp();
    CHECK(c2.state() == CaptureState::kCleared);  // no-op after clear
}

void testDeviceLoss() {
    // Losing the last eligible device mid-capture cancels it.
    int devices[2] = {0, 1};
    BindingCaptureCoordinator c;
    c.begin(0, devices, 2, CaptureTarget::kDigital);
    feed(c, 0);  // arm via device 0
    CHECK(c.state() == CaptureState::kCapturing);
    c.onDeviceRemoved(0);
    CHECK(c.isActive());  // device 1 still eligible
    c.onDeviceRemoved(1);
    CHECK(c.state() == CaptureState::kCancelled);

    // Samples from non-eligible devices are ignored.
    BindingCaptureCoordinator c2;
    const int only = 0;
    c2.begin(0, &only, 1, CaptureTarget::kDigital);
    feed(c2, 5, {PadButton::kSouth});  // device 5 is not eligible
    CHECK(c2.result() == nullptr);
    CHECK(c2.isActive());
}

void testBeginReplacesInProgress() {
    BindingCaptureCoordinator c;
    const int device = 0;
    c.begin(0, &device, 1, CaptureTarget::kDigital);
    feed(c, device);
    CHECK(c.state() == CaptureState::kCapturing);
    // A fresh begin for another slot resets everything (Android's
    // cancelInternal-then-restart).
    c.begin(7, nullptr, 0, CaptureTarget::kAnalog);
    CHECK(c.state() == CaptureState::kNoDeviceAssigned);
    CHECK_EQ(c.slotIndex(), 7);
    CHECK(c.target() == CaptureTarget::kAnalog);
}

}  // namespace

int main() {
    testNoDeviceAssigned();
    testArmsOnTickWithoutInput();
    testHeldButtonAtBeginIsBaseline();
    testNewPressDuringAwaitingNeutralBlocksAndArmsKeyPriority();
    testFirstPressWinsAndButtonBeatsStickSameFrame();
    testAxisNeutralGateAndEnterThreshold();
    testNoisyAxisNeverCaptures();
    testAnalogTargetCapturesFullAxis();
    testDigitalMultiAxisDominantWins();
    testTimeout();
    testQuickBackCancels();
    testHeldBackClears();
    testDeviceLoss();
    testBeginReplacesInProgress();
    return rommtest::finish("test_controller_capture");
}

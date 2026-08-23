// SDL-free keyboard binding model. Scancodes are persisted as SDL3's stable
// USB usage IDs, but remain plain integers here so protocol and runtime logic
// can be host-tested without SDL.
#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include <optional>
#include <string>
#include <utility>

#include "native/player/binding_table.h"

namespace romm::player {

enum KeyboardTarget : int {
    kKeyboardA = 0,
    kKeyboardB,
    kKeyboardX,
    kKeyboardY,
    kKeyboardSelect,
    kKeyboardStart,
    kKeyboardLeftShoulder,
    kKeyboardRightShoulder,
    kKeyboardDpadUp,
    kKeyboardDpadDown,
    kKeyboardDpadLeft,
    kKeyboardDpadRight,
    kKeyboardLeftTrigger,
    kKeyboardRightTrigger,
    kKeyboardLeftStick,
    kKeyboardRightStick,
    kKeyboardLeftXNegative,
    kKeyboardLeftXPositive,
    kKeyboardLeftYNegative,
    kKeyboardLeftYPositive,
    kKeyboardRightXNegative,
    kKeyboardRightXPositive,
    kKeyboardRightYNegative,
    kKeyboardRightYPositive,
};

constexpr int kKeyboardDigitalTargetCount = 16;
constexpr int kKeyboardTargetCount = 24;
constexpr int kKeyboardScancodeMax = 511;
constexpr int kKeyboardScancodeCount = kKeyboardScancodeMax + 1;

inline bool validKeyboardScancode(int scancode) {
    return scancode >= 0 && scancode <= kKeyboardScancodeMax;
}

inline const char* keyboardTargetName(int target) {
    static constexpr const char* kNames[kKeyboardTargetCount] = {
        "a", "b", "x", "y", "select", "start",
        "left_shoulder", "right_shoulder",
        "dpad_up", "dpad_down", "dpad_left", "dpad_right",
        "left_trigger", "right_trigger", "left_stick", "right_stick",
        "left_x_negative", "left_x_positive",
        "left_y_negative", "left_y_positive",
        "right_x_negative", "right_x_positive",
        "right_y_negative", "right_y_positive",
    };
    return target >= 0 && target < kKeyboardTargetCount ? kNames[target] : "";
}

inline int keyboardTargetFromName(const std::string& name) {
    for (int target = 0; target < kKeyboardTargetCount; ++target) {
        if (name == keyboardTargetName(target)) return target;
    }
    return -1;
}

inline const char* keyboardTargetLabel(int target) {
    if (target >= 0 && target < kKeyboardDigitalTargetCount) {
        return retroPadSlotLabel(target);
    }
    switch (target) {
        case kKeyboardLeftXNegative: return "Left Stick Left";
        case kKeyboardLeftXPositive: return "Left Stick Right";
        case kKeyboardLeftYNegative: return "Left Stick Up";
        case kKeyboardLeftYPositive: return "Left Stick Down";
        case kKeyboardRightXNegative: return "Right Stick Left";
        case kKeyboardRightXPositive: return "Right Stick Right";
        case kKeyboardRightYNegative: return "Right Stick Up";
        case kKeyboardRightYPositive: return "Right Stick Down";
        default: return "";
    }
}

struct KeyboardBinding {
    std::optional<int> primaryScancode;
    std::optional<int> secondaryScancode;

    bool operator==(const KeyboardBinding& other) const {
        return primaryScancode == other.primaryScancode &&
               secondaryScancode == other.secondaryScancode;
    }
    bool operator!=(const KeyboardBinding& other) const { return !(*this == other); }
};

inline KeyboardBinding defaultKeyboardBinding(int target) {
    switch (target) {
        case kKeyboardA: return {40, 44};            // Return, Space
        case kKeyboardB: return {225, 229};          // Left/Right Shift
        case kKeyboardX: return {27, std::nullopt};  // X
        case kKeyboardY: return {29, std::nullopt};  // Z
        case kKeyboardSelect: return {224, std::nullopt};  // Left Ctrl
        case kKeyboardStart: return {228, std::nullopt};   // Right Ctrl
        case kKeyboardDpadUp: return {26, 82};        // W, Up
        case kKeyboardDpadDown: return {22, 81};      // S, Down
        case kKeyboardDpadLeft: return {4, 80};       // A, Left
        case kKeyboardDpadRight: return {7, 79};      // D, Right
        default: return {};
    }
}

class KeyboardBindingTable {
public:
    explicit KeyboardBindingTable(bool useDefaults = true) {
        if (useDefaults) reset();
        else clear();
    }

    const KeyboardBinding& get(int target) const { return bindings_[target]; }

    void set(int target, KeyboardBinding binding) {
        if (target < 0 || target >= kKeyboardTargetCount) return;
        bindings_[target] = std::move(binding);
    }

    void setScancode(int target, int column, std::optional<int> scancode) {
        if (target < 0 || target >= kKeyboardTargetCount) return;
        if (scancode.has_value() && !validKeyboardScancode(*scancode)) return;
        if (column == 1) bindings_[target].secondaryScancode = scancode;
        else bindings_[target].primaryScancode = scancode;
    }

    void reset() {
        for (int target = 0; target < kKeyboardTargetCount; ++target) {
            bindings_[target] = defaultKeyboardBinding(target);
        }
    }

    void clear() { bindings_.fill(KeyboardBinding{}); }

    bool isDefault() const {
        for (int target = 0; target < kKeyboardTargetCount; ++target) {
            if (bindings_[target] != defaultKeyboardBinding(target)) return false;
        }
        return true;
    }

private:
    std::array<KeyboardBinding, kKeyboardTargetCount> bindings_{};
};

inline int coreKeyboardTargetAt(const std::string& coreId, int row) {
    const int digitalRows = coreId == "mupen64plus_next" ? 14
        : coreId == "pcsx_rearmed" ? 16 : 12;
    if (row < digitalRows) return coreBindingSlotAt(coreId, row);
    const int analogRow = row - digitalRows;
    if (coreId == "mupen64plus_next" && analogRow >= 0 && analogRow < 4) {
        return kKeyboardLeftXNegative + analogRow;
    }
    if (coreId == "pcsx_rearmed" && analogRow >= 0 && analogRow < 8) {
        return kKeyboardLeftXNegative + analogRow;
    }
    return -1;
}

inline int coreKeyboardRowCount(const std::string& coreId) {
    if (coreId == "mupen64plus_next") return 18;
    if (coreId == "pcsx_rearmed") return 24;
    return 12;
}

struct KeyboardRuntimeState {
    int32_t buttonsMask = 0;
    int16_t leftX = 0;
    int16_t leftY = 0;
    int16_t rightX = 0;
    int16_t rightY = 0;
};

inline KeyboardRuntimeState synthesizeKeyboardState(
    const KeyboardBindingTable& table,
    const std::array<bool, kKeyboardScancodeCount>& held) {
    const auto pressed = [&](int target) {
        const KeyboardBinding& binding = table.get(target);
        const auto heldScancode = [&](const std::optional<int>& scancode) {
            return scancode.has_value() && validKeyboardScancode(*scancode) &&
                   held[static_cast<size_t>(*scancode)];
        };
        return heldScancode(binding.primaryScancode) ||
               heldScancode(binding.secondaryScancode);
    };

    KeyboardRuntimeState state;
    for (int target = 0; target < kKeyboardDigitalTargetCount; ++target) {
        if (pressed(target)) {
            state.buttonsMask |= 1 << retroPadSlotJoypadBit(target);
        }
    }
    const auto axis = [&](int negative, int positive) -> int16_t {
        const bool neg = pressed(negative);
        const bool pos = pressed(positive);
        if (neg == pos) return 0;
        return neg ? std::numeric_limits<int16_t>::min()
                   : std::numeric_limits<int16_t>::max();
    };
    state.leftX = axis(kKeyboardLeftXNegative, kKeyboardLeftXPositive);
    state.leftY = axis(kKeyboardLeftYNegative, kKeyboardLeftYPositive);
    state.rightX = axis(kKeyboardRightXNegative, kKeyboardRightXPositive);
    state.rightY = axis(kKeyboardRightYNegative, kKeyboardRightYPositive);
    return state;
}

}  // namespace romm::player

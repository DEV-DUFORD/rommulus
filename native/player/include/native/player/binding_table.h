// binding_table.h — SDL-free RetroPad slot -> physical input binding table.
//
// The player's gamepad ingestion (SdlInput::poll) maps each of the 12
// digital RetroPad slots to a physical gamepad control. This header owns
// that mapping as data so the pause menu's Physical Controller Settings
// editor can read and modify it at runtime, and so the table can be
// unit-tested on the host without SDL (native/tests/test_binding_table.cpp):
//
//   - PadButton / PadAxis name physical controls in platform-neutral terms
//     (LINUX_X64.md section 11.9: "Do not put SDL constants in shared
//     controller models"); SdlInput translates them to SDL enums;
//   - BindingSource is one slot's source: a button, an axis half (axis +
//     polarity), or unbound;
//   - BindingTable holds the 12 sources and knows its own defaults — the
//     built-in mapping SdlInput::poll() used to hardcode.
#pragma once

#include <array>
#include <cstdint>
#include <optional>
#include <string>

namespace romm::player {

// The 12 digital RetroPad slots, in the display order of the binding editor
// (the pause menu's Physical Controller Settings list).
enum RetroPadSlot : int {
    kSlotA = 0,
    kSlotB,
    kSlotX,
    kSlotY,
    kSlotSelect,
    kSlotStart,
    kSlotLeftShoulder,
    kSlotRightShoulder,
    kSlotDpadUp,
    kSlotDpadDown,
    kSlotDpadLeft,
    kSlotDpadRight,
    kSlotLeftTrigger,
    kSlotRightTrigger,
    kSlotLeftStick,
    kSlotRightStick,
};
constexpr int kRetroPadSlotCount = 16;

// RETRO_DEVICE_ID_JOYPAD_* bit positions (libretro ABI, stable). Kept here
// as plain constants — rather than including <libretro.h> — so this header
// stays usable by the host test project without the libretro include path.
// sdl_input.cpp static_asserts these against the real libretro constants.
constexpr int kJoypadBitB = 0;
constexpr int kJoypadBitY = 1;
constexpr int kJoypadBitSelect = 2;
constexpr int kJoypadBitStart = 3;
constexpr int kJoypadBitUp = 4;
constexpr int kJoypadBitDown = 5;
constexpr int kJoypadBitLeft = 6;
constexpr int kJoypadBitRight = 7;
constexpr int kJoypadBitA = 8;
constexpr int kJoypadBitX = 9;
constexpr int kJoypadBitL = 10;
constexpr int kJoypadBitR = 11;
constexpr int kJoypadBitL2 = 12;
constexpr int kJoypadBitR2 = 13;
constexpr int kJoypadBitL3 = 14;
constexpr int kJoypadBitR3 = 15;

// The RETRO_DEVICE_ID_JOYPAD_* bit for a slot, or -1 for an invalid slot.
inline int retroPadSlotJoypadBit(int slot) {
    switch (slot) {
        case kSlotA: return kJoypadBitA;
        case kSlotB: return kJoypadBitB;
        case kSlotX: return kJoypadBitX;
        case kSlotY: return kJoypadBitY;
        case kSlotSelect: return kJoypadBitSelect;
        case kSlotStart: return kJoypadBitStart;
        case kSlotLeftShoulder: return kJoypadBitL;
        case kSlotRightShoulder: return kJoypadBitR;
        case kSlotDpadUp: return kJoypadBitUp;
        case kSlotDpadDown: return kJoypadBitDown;
        case kSlotDpadLeft: return kJoypadBitLeft;
        case kSlotDpadRight: return kJoypadBitRight;
        case kSlotLeftTrigger: return kJoypadBitL2;
        case kSlotRightTrigger: return kJoypadBitR2;
        case kSlotLeftStick: return kJoypadBitL3;
        case kSlotRightStick: return kJoypadBitR3;
        default: return -1;
    }
}

// Display label for a slot (pause menu editor list).
inline const char* retroPadSlotLabel(int slot) {
    switch (slot) {
        case kSlotA: return "A";
        case kSlotB: return "B";
        case kSlotX: return "X";
        case kSlotY: return "Y";
        case kSlotSelect: return "Select";
        case kSlotStart: return "Start";
        case kSlotLeftShoulder: return "Left Shoulder";
        case kSlotRightShoulder: return "Right Shoulder";
        case kSlotDpadUp: return "D-Pad Up";
        case kSlotDpadDown: return "D-Pad Down";
        case kSlotDpadLeft: return "D-Pad Left";
        case kSlotDpadRight: return "D-Pad Right";
        case kSlotLeftTrigger: return "L2";
        case kSlotRightTrigger: return "R2";
        case kSlotLeftStick: return "L3";
        case kSlotRightStick: return "R3";
        default: return "";
    }
}

// Canonical (lowercase) name for a slot in the sidecar JSON.
inline const char* retroPadSlotName(int slot) {
    switch (slot) {
        case kSlotA: return "a";
        case kSlotB: return "b";
        case kSlotX: return "x";
        case kSlotY: return "y";
        case kSlotSelect: return "select";
        case kSlotStart: return "start";
        case kSlotLeftShoulder: return "left_shoulder";
        case kSlotRightShoulder: return "right_shoulder";
        case kSlotDpadUp: return "dpad_up";
        case kSlotDpadDown: return "dpad_down";
        case kSlotDpadLeft: return "dpad_left";
        case kSlotDpadRight: return "dpad_right";
        case kSlotLeftTrigger: return "left_trigger";
        case kSlotRightTrigger: return "right_trigger";
        case kSlotLeftStick: return "left_stick";
        case kSlotRightStick: return "right_stick";
        default: return "";
    }
}

// Core-facing display order, matching CoreControllerProfiles.controls in the
// Settings UI for every console control represented by the 16 RetroPad slots.
inline int coreBindingSlotAt(const std::string& coreId, int row) {
    static constexpr std::array<int, kRetroPadSlotCount> kDefault = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotX, kSlotY, kSlotLeftShoulder,
        kSlotRightShoulder, kSlotSelect, kSlotStart, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kGenesis = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotY, kSlotB, kSlotA, kSlotLeftShoulder, kSlotX,
        kSlotRightShoulder, kSlotSelect, kSlotStart, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kPce = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotY, kSlotX, kSlotLeftShoulder,
        kSlotRightShoulder, kSlotSelect, kSlotStart, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kN64 = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotB, kSlotY, kSlotX, kSlotA, kSlotLeftShoulder,
        kSlotRightShoulder, kSlotLeftTrigger, kSlotSelect,
        kSlotRightTrigger, kSlotStart, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kTwoButton = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotSelect, kSlotStart, kSlotX, kSlotY,
        kSlotLeftShoulder, kSlotRightShoulder, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kGba = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotLeftShoulder, kSlotRightShoulder,
        kSlotSelect, kSlotStart, kSlotX, kSlotY, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kStella = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotY, kSlotSelect, kSlotStart, kSlotX,
        kSlotLeftShoulder, kSlotRightShoulder, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kNgp = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotB, kSlotA, kSlotStart, kSlotSelect, kSlotX, kSlotY,
        kSlotLeftShoulder, kSlotRightShoulder, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kHandy = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotLeftShoulder, kSlotRightShoulder,
        kSlotStart, kSlotSelect, kSlotX, kSlotY, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kProsystem = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotB, kSlotA, kSlotStart, kSlotSelect, kSlotX, kSlotY,
        kSlotLeftShoulder, kSlotRightShoulder, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotLeftStick, kSlotRightStick,
    };
    // Shared by pcsx_rearmed and lrps2: the DualShock 2 keeps the DualShock
    // layout and RetroPad mapping (Cross->B, Circle->A, Triangle->X,
    // Square->Y, L1->L, R1->R, L2/R2 triggers, L3/R3 stick clicks), so the
    // PS2 editor shows the same 16 rows.
    static constexpr std::array<int, kRetroPadSlotCount> kPsx = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotB, kSlotA, kSlotX, kSlotY, kSlotLeftShoulder,
        kSlotRightShoulder, kSlotLeftTrigger, kSlotRightTrigger,
        kSlotLeftStick, kSlotRightStick, kSlotSelect, kSlotStart,
    };
    static constexpr std::array<int, kRetroPadSlotCount> kGameCube = {
        kSlotDpadUp, kSlotDpadDown, kSlotDpadLeft, kSlotDpadRight,
        kSlotA, kSlotB, kSlotX, kSlotY, kSlotLeftTrigger,
        kSlotRightTrigger, kSlotRightShoulder, kSlotStart,
        kSlotSelect, kSlotLeftShoulder, kSlotLeftStick, kSlotRightStick,
    };
    const auto& order = coreId == "genesis_plus_gx" ? kGenesis
        : coreId == "beetle_pce_fast" ? kPce
        : coreId == "mupen64plus_next" ? kN64
        : coreId == "mgba" ? kGba
        : coreId == "stella" ? kStella
        : coreId == "mednafen_ngp" ? kNgp
        : coreId == "handy" ? kHandy
        : coreId == "prosystem" ? kProsystem
        : (coreId == "pcsx_rearmed" || coreId == "lrps2") ? kPsx
        : coreId == "dolphin" ? kGameCube
        : (coreId == "fceumm" || coreId == "gambatte" ||
           coreId == "mednafen_wswan") ? kTwoButton
        : kDefault;
    return row >= 0 && row < kRetroPadSlotCount ? order[row] : row;
}

// Physical gamepad buttons, named in platform-neutral terms. The values are
// stable identifiers (used by the sidecar JSON via padButtonName), NOT SDL
// enum ordinals — SdlInput owns the translation to SDL_GamepadButton.
enum class PadButton : int {
    kSouth = 0,
    kEast,
    kWest,
    kNorth,
    kBack,
    kStart,
    kLeftShoulder,
    kRightShoulder,
    kDpadUp,
    kDpadDown,
    kDpadLeft,
    kDpadRight,
    kLeftStick,   // L3 (not bindable to a RetroPad slot; capture-only)
    kRightStick,  // R3 (not bindable to a RetroPad slot; capture-only)
};
constexpr int kPadButtonCount = 14;

// Physical gamepad axes. Sticks are signed (-1..+1); triggers are
// unidirectional (0..+1).
enum class PadAxis : int {
    kLeftX = 0,
    kLeftY,
    kRightX,
    kRightY,
    kLeftTrigger,
    kRightTrigger,
};
constexpr int kPadAxisCount = 6;

// Canonical (lowercase) names for the sidecar JSON.
inline const char* padButtonName(PadButton button) {
    switch (button) {
        case PadButton::kSouth: return "south";
        case PadButton::kEast: return "east";
        case PadButton::kWest: return "west";
        case PadButton::kNorth: return "north";
        case PadButton::kBack: return "back";
        case PadButton::kStart: return "start";
        case PadButton::kLeftShoulder: return "left_shoulder";
        case PadButton::kRightShoulder: return "right_shoulder";
        case PadButton::kDpadUp: return "dpad_up";
        case PadButton::kDpadDown: return "dpad_down";
        case PadButton::kDpadLeft: return "dpad_left";
        case PadButton::kDpadRight: return "dpad_right";
        case PadButton::kLeftStick: return "left_stick";
        case PadButton::kRightStick: return "right_stick";
    }
    return "";
}

inline const char* padAxisName(PadAxis axis) {
    switch (axis) {
        case PadAxis::kLeftX: return "left_x";
        case PadAxis::kLeftY: return "left_y";
        case PadAxis::kRightX: return "right_x";
        case PadAxis::kRightY: return "right_y";
        case PadAxis::kLeftTrigger: return "left_trigger";
        case PadAxis::kRightTrigger: return "right_trigger";
    }
    return "";
}

// Strict name -> enum parsers (the inverses of the getters above), used by
// the v2 launch-request parser to decode sidecar-shaped binding entries.
// Unknown names yield std::nullopt / -1 so the strict parsers can reject
// them with a precise error instead of guessing.
inline int retroPadSlotFromName(const std::string& name) {
    for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
        if (name == retroPadSlotName(slot)) return slot;
    }
    return -1;
}

inline std::optional<PadButton> padButtonFromName(const std::string& name) {
    for (int i = 0; i < kPadButtonCount; ++i) {
        const PadButton button = static_cast<PadButton>(i);
        if (name == padButtonName(button)) return button;
    }
    return std::nullopt;
}

inline std::optional<PadAxis> padAxisFromName(const std::string& name) {
    for (int i = 0; i < kPadAxisCount; ++i) {
        const PadAxis axis = static_cast<PadAxis>(i);
        if (name == padAxisName(axis)) return axis;
    }
    return std::nullopt;
}

// User-facing names match the shared Android/Desktop binding formatter.
inline const char* padButtonDisplay(PadButton button) {
    switch (button) {
        case PadButton::kSouth: return "Button A";
        case PadButton::kEast: return "Button B";
        case PadButton::kWest: return "Button X";
        case PadButton::kNorth: return "Button Y";
        case PadButton::kBack: return "Select";
        case PadButton::kStart: return "Start";
        case PadButton::kLeftShoulder: return "L1";
        case PadButton::kRightShoulder: return "R1";
        case PadButton::kDpadUp: return "D-Pad Up";
        case PadButton::kDpadDown: return "D-Pad Down";
        case PadButton::kDpadLeft: return "D-Pad Left";
        case PadButton::kDpadRight: return "D-Pad Right";
        case PadButton::kLeftStick: return "L3";
        case PadButton::kRightStick: return "R3";
    }
    return "";
}

inline const char* padAxisDisplay(PadAxis axis) {
    switch (axis) {
        case PadAxis::kLeftX: return "Left Stick X";
        case PadAxis::kLeftY: return "Left Stick Y";
        case PadAxis::kRightX: return "Right Stick X";
        case PadAxis::kRightY: return "Right Stick Y";
        case PadAxis::kLeftTrigger: return "Left Trigger";
        case PadAxis::kRightTrigger: return "Right Trigger";
    }
    return "";
}

// One physical source. Most RetroPad slots are digital and use a button or
// axis half; GameCube's otherwise-unused slots carry the four full stick axes.
struct BindingSource {
    enum class Kind : int { kUnbound = 0, kButton, kAxis, kAxisDirection };

    Kind kind = Kind::kUnbound;
    PadButton button = PadButton::kSouth;  // valid when kind == kButton
    PadAxis axis = PadAxis::kLeftX;        // valid when kind == kAxis/kAxisDirection
    int polarity = 1;                      // +1 or -1, kAxisDirection only

    BindingSource() = default;

    static BindingSource unbound() { return {}; }
    // (Named ofButton because a static member cannot share the field name.)
    static BindingSource ofButton(PadButton b) {
        BindingSource s;
        s.kind = Kind::kButton;
        s.button = b;
        return s;
    }
    static BindingSource ofAxis(PadAxis a) {
        BindingSource s;
        s.kind = Kind::kAxis;
        s.axis = a;
        return s;
    }
    static BindingSource axisDirection(PadAxis a, int polarity) {
        BindingSource s;
        s.kind = Kind::kAxisDirection;
        s.axis = a;
        s.polarity = polarity < 0 ? -1 : 1;
        return s;
    }

    bool operator==(const BindingSource& other) const {
        if (kind != other.kind) return false;
        if (kind == Kind::kButton) return button == other.button;
        if (kind == Kind::kAxis) return axis == other.axis;
        if (kind == Kind::kAxisDirection) {
            return axis == other.axis && polarity == other.polarity;
        }
        return true;  // kUnbound
    }
    bool operator!=(const BindingSource& other) const { return !(*this == other); }

    // Short display label for the editor list: "Button A", "Left Stick X +", ...
    std::string display() const {
        switch (kind) {
            case Kind::kUnbound:
                return "Unmapped";
            case Kind::kButton:
                return padButtonDisplay(button);
            case Kind::kAxis:
                return padAxisDisplay(axis);
            case Kind::kAxisDirection:
                return std::string(padAxisDisplay(axis)) + (polarity > 0 ? " +" : " -");
        }
        return "";
    }
};

inline bool isGameCubeAnalogSlot(int slot) {
    return slot == kSlotSelect || slot == kSlotLeftShoulder ||
           slot == kSlotLeftStick || slot == kSlotRightStick;
}

inline BindingSource gameCubeAnalogSourceForSlot(int slot) {
    switch (slot) {
        case kSlotSelect: return BindingSource::ofAxis(PadAxis::kLeftX);
        case kSlotLeftShoulder: return BindingSource::ofAxis(PadAxis::kLeftY);
        case kSlotLeftStick: return BindingSource::ofAxis(PadAxis::kRightX);
        case kSlotRightStick: return BindingSource::ofAxis(PadAxis::kRightY);
        default: return BindingSource::unbound();
    }
}

// The built-in gamepad -> RetroPad mapping (what SdlInput::poll() hardcoded
// before the editor landed): every slot bound to its standard SDL button.
inline BindingSource defaultSourceForSlot(int slot) {
    switch (slot) {
        case kSlotA: return BindingSource::ofButton(PadButton::kSouth);
        case kSlotB: return BindingSource::ofButton(PadButton::kEast);
        case kSlotX: return BindingSource::ofButton(PadButton::kWest);
        case kSlotY: return BindingSource::ofButton(PadButton::kNorth);
        case kSlotSelect: return BindingSource::ofButton(PadButton::kBack);
        case kSlotStart: return BindingSource::ofButton(PadButton::kStart);
        case kSlotLeftShoulder: return BindingSource::ofButton(PadButton::kLeftShoulder);
        case kSlotRightShoulder: return BindingSource::ofButton(PadButton::kRightShoulder);
        case kSlotDpadUp: return BindingSource::ofButton(PadButton::kDpadUp);
        case kSlotDpadDown: return BindingSource::ofButton(PadButton::kDpadDown);
        case kSlotDpadLeft: return BindingSource::ofButton(PadButton::kDpadLeft);
        case kSlotDpadRight: return BindingSource::ofButton(PadButton::kDpadRight);
        case kSlotLeftTrigger:
            return BindingSource::axisDirection(PadAxis::kLeftTrigger, 1);
        case kSlotRightTrigger:
            return BindingSource::axisDirection(PadAxis::kRightTrigger, 1);
        case kSlotLeftStick: return BindingSource::ofButton(PadButton::kLeftStick);
        case kSlotRightStick: return BindingSource::ofButton(PadButton::kRightStick);
        default: return BindingSource::unbound();  // unreachable for valid slots
    }
}

// PlayStation cores use the conventional libretro semantic slots
// Cross=B, Circle=A, Triangle=X, Square=Y. Bind those slots by physical
// position so standard south/east/north/west controllers match the console.
inline BindingSource playStationSourceForSlot(int slot) {
    switch (slot) {
        case kSlotB: return BindingSource::ofButton(PadButton::kSouth);
        case kSlotA: return BindingSource::ofButton(PadButton::kEast);
        case kSlotX: return BindingSource::ofButton(PadButton::kNorth);
        case kSlotY: return BindingSource::ofButton(PadButton::kWest);
        default: return defaultSourceForSlot(slot);
    }
}

// The 16-slot table SdlInput::poll() consults. GameCube reuses four controls
// it does not expose as full-axis targets; other cores retain the RetroPad defaults.
class BindingTable {
public:
    explicit BindingTable(bool useDefaults = true) {
        if (useDefaults) {
            reset();
        } else {
            clear();
        }
    }

    const BindingSource& get(int slot) const { return sources_[slot]; }

    void set(int slot, BindingSource source) {
        if (slot < 0 || slot >= kRetroPadSlotCount) return;
        sources_[slot] = source;
    }

    // Restores the built-in default mapping.
    void reset() {
        for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
            sources_[slot] = defaultSourceForSlot(slot);
        }
    }

    void clear() {
        sources_.fill(BindingSource::unbound());
    }

    bool isDefault() const {
        for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
            if (sources_[slot] != defaultSourceForSlot(slot)) return false;
        }
        return true;
    }

    bool isUnmapped() const {
        for (const BindingSource& source : sources_) {
            if (source.kind != BindingSource::Kind::kUnbound) return false;
        }
        return true;
    }

private:
    std::array<BindingSource, kRetroPadSlotCount> sources_{};
};

}  // namespace romm::player

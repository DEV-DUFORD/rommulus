// sdl_input.cpp — SDL3 keyboard + gamepad → four-port RetroPad state.
//
// See sdl_input.h for the port-assignment and key-mapping contract. All
// SDL calls here run on the main thread only (event handling, per-frame
// polling, and updateSession), so no synchronization is needed inside this
// class; EmulationSession::updateInputState() is itself safe from any
// thread other than the emulation thread.
#include "native/player/sdl_input.h"

#include <libretro.h>

#include <algorithm>
#include <cstdlib>
#include <limits>

#include "emulation_session.h"
#include "native/player/pause_chord.h"

namespace romm::player {
namespace {

// A small deadzone (~12% of full scale) so a resting stick does not drift
// into the first few RETRO_DEVICE_ID_ANALOG steps.
constexpr Sint16 kAxisDeadzone = 4096;

// Half-scale level an axis must reach (in the bound polarity's direction,
// after deadzone) to count as "pressed" for a slot bound to an axis half.
constexpr Sint16 kAxisBindLevel = 16384;

// The binding table names physical controls in platform-neutral terms
// (LINUX_X64.md section 11.9 — no SDL constants in the model); this file is
// the single translation point to SDL enums.
SDL_GamepadButton toSdlButton(PadButton button) {
    switch (button) {
        case PadButton::kSouth: return SDL_GAMEPAD_BUTTON_SOUTH;
        case PadButton::kEast: return SDL_GAMEPAD_BUTTON_EAST;
        case PadButton::kWest: return SDL_GAMEPAD_BUTTON_WEST;
        case PadButton::kNorth: return SDL_GAMEPAD_BUTTON_NORTH;
        case PadButton::kBack: return SDL_GAMEPAD_BUTTON_BACK;
        case PadButton::kStart: return SDL_GAMEPAD_BUTTON_START;
        case PadButton::kLeftShoulder: return SDL_GAMEPAD_BUTTON_LEFT_SHOULDER;
        case PadButton::kRightShoulder: return SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER;
        case PadButton::kDpadUp: return SDL_GAMEPAD_BUTTON_DPAD_UP;
        case PadButton::kDpadDown: return SDL_GAMEPAD_BUTTON_DPAD_DOWN;
        case PadButton::kDpadLeft: return SDL_GAMEPAD_BUTTON_DPAD_LEFT;
        case PadButton::kDpadRight: return SDL_GAMEPAD_BUTTON_DPAD_RIGHT;
        case PadButton::kLeftStick: return SDL_GAMEPAD_BUTTON_LEFT_STICK;
        case PadButton::kRightStick: return SDL_GAMEPAD_BUTTON_RIGHT_STICK;
    }
    return SDL_GAMEPAD_BUTTON_SOUTH;  // unreachable
}

SDL_GamepadAxis toSdlAxis(PadAxis axis) {
    switch (axis) {
        case PadAxis::kLeftX: return SDL_GAMEPAD_AXIS_LEFTX;
        case PadAxis::kLeftY: return SDL_GAMEPAD_AXIS_LEFTY;
        case PadAxis::kRightX: return SDL_GAMEPAD_AXIS_RIGHTX;
        case PadAxis::kRightY: return SDL_GAMEPAD_AXIS_RIGHTY;
        case PadAxis::kLeftTrigger: return SDL_GAMEPAD_AXIS_LEFT_TRIGGER;
        case PadAxis::kRightTrigger: return SDL_GAMEPAD_AXIS_RIGHT_TRIGGER;
    }
    return SDL_GAMEPAD_AXIS_LEFTX;  // unreachable
}

// The binding table's slot bit positions must match the libretro ABI exactly
// (poll() uses them to set the core's button mask).
static_assert(kJoypadBitB == RETRO_DEVICE_ID_JOYPAD_B, "slot bits drifted from libretro");
static_assert(kJoypadBitY == RETRO_DEVICE_ID_JOYPAD_Y, "slot bits drifted from libretro");
static_assert(kJoypadBitSelect == RETRO_DEVICE_ID_JOYPAD_SELECT, "slot bits drifted from libretro");
static_assert(kJoypadBitStart == RETRO_DEVICE_ID_JOYPAD_START, "slot bits drifted from libretro");
static_assert(kJoypadBitUp == RETRO_DEVICE_ID_JOYPAD_UP, "slot bits drifted from libretro");
static_assert(kJoypadBitDown == RETRO_DEVICE_ID_JOYPAD_DOWN, "slot bits drifted from libretro");
static_assert(kJoypadBitLeft == RETRO_DEVICE_ID_JOYPAD_LEFT, "slot bits drifted from libretro");
static_assert(kJoypadBitRight == RETRO_DEVICE_ID_JOYPAD_RIGHT, "slot bits drifted from libretro");
static_assert(kJoypadBitA == RETRO_DEVICE_ID_JOYPAD_A, "slot bits drifted from libretro");
static_assert(kJoypadBitX == RETRO_DEVICE_ID_JOYPAD_X, "slot bits drifted from libretro");
static_assert(kJoypadBitL == RETRO_DEVICE_ID_JOYPAD_L, "slot bits drifted from libretro");
static_assert(kJoypadBitR == RETRO_DEVICE_ID_JOYPAD_R, "slot bits drifted from libretro");
static_assert(kJoypadBitL2 == RETRO_DEVICE_ID_JOYPAD_L2, "slot bits drifted from libretro");
static_assert(kJoypadBitR2 == RETRO_DEVICE_ID_JOYPAD_R2, "slot bits drifted from libretro");
static_assert(kJoypadBitL3 == RETRO_DEVICE_ID_JOYPAD_L3, "slot bits drifted from libretro");
static_assert(kJoypadBitR3 == RETRO_DEVICE_ID_JOYPAD_R3, "slot bits drifted from libretro");

}  // namespace

SdlInput::SdlInput() {
    int count = 0;
    SDL_JoystickID* ids = SDL_GetGamepads(&count);
    if (ids != nullptr) {
        for (int i = 0; i < count; ++i) {
            const int port = findFreePort();
            if (port < 0) break;  // all four ports busy; ignore the rest
            if (!SDL_IsGamepad(ids[i])) continue;
            openGamepad(port, ids[i]);
        }
        SDL_free(ids);
    }
}

SdlInput::~SdlInput() {
    for (int port = 0; port < kPorts; ++port) {
        closeGamepad(port);
    }
}

int SdlInput::findFreePort() const {
    for (int port = 0; port < kPorts; ++port) {
        if (gamepads_[port].gamepad == nullptr) return port;
    }
    return -1;
}

void SdlInput::openGamepad(int port, SDL_JoystickID instanceId) {
    if (port < 0 || port >= kPorts || gamepads_[port].gamepad != nullptr) return;
    SDL_Gamepad* gamepad = SDL_OpenGamepad(instanceId);
    if (gamepad == nullptr) {
        // The device vanished between enumeration and open — leave the
        // slot free and move on.
        return;
    }
    gamepads_[port].gamepad = gamepad;
}

void SdlInput::closeGamepad(int port) {
    if (port < 0 || port >= kPorts) return;
    if (gamepads_[port].gamepad != nullptr) {
        SDL_CloseGamepad(gamepads_[port].gamepad);
        gamepads_[port].gamepad = nullptr;
    }
    // Neutralize the port so the core never sees a stuck button after the
    // pad goes away.
    ports_[port] = PortState{};
}

int16_t SdlInput::applyDeadzone(Sint16 value) {
    if (value > -kAxisDeadzone && value < kAxisDeadzone) return 0;
    return value;
}

void SdlInput::handleEvent(const SDL_Event& event) {
    switch (event.type) {
        case SDL_EVENT_GAMEPAD_ADDED: {
            const int port = findFreePort();
            if (port >= 0) openGamepad(port, event.gdevice.which);
            break;
        }
        case SDL_EVENT_GAMEPAD_REMOVED: {
            for (int port = 0; port < kPorts; ++port) {
                if (gamepads_[port].gamepad != nullptr &&
                    SDL_GetGamepadID(gamepads_[port].gamepad) == event.gdevice.which) {
                    closeGamepad(port);
                    break;
                }
            }
            break;
        }
        case SDL_EVENT_KEY_DOWN:
        case SDL_EVENT_KEY_UP: {
            const int scancode = static_cast<int>(event.key.scancode);
            if (validKeyboardScancode(scancode)) {
                heldScancodes_[static_cast<size_t>(scancode)] =
                    event.type == SDL_EVENT_KEY_DOWN;
            }
            break;
        }
        default:
            break;
    }
}

void SdlInput::refreshGamepads() {
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad != nullptr && !SDL_GamepadConnected(gamepad)) {
            closeGamepad(port);
        }
    }

    int count = 0;
    SDL_JoystickID* ids = SDL_GetGamepads(&count);
    if (ids == nullptr) return;
    for (int i = 0; i < count; ++i) {
        if (!SDL_IsGamepad(ids[i])) continue;
        bool alreadyOpen = false;
        for (int port = 0; port < kPorts; ++port) {
            if (gamepads_[port].gamepad != nullptr &&
                SDL_GetGamepadID(gamepads_[port].gamepad) == ids[i]) {
                alreadyOpen = true;
                break;
            }
        }
        if (alreadyOpen) continue;
        const int port = findFreePort();
        if (port < 0) break;
        openGamepad(port, ids[i]);
    }
    SDL_free(ids);
}

int SdlInput::connectedGamepadCount() const {
    return static_cast<int>(std::count_if(
        gamepads_.begin(),
        gamepads_.end(),
        [](const GamepadSlot& slot) { return slot.gamepad != nullptr; }));
}

void SdlInput::poll() {
    const Uint64 now = SDL_GetTicks();
    if (now - lastGamepadRefreshMs_ >= 1000) {
        refreshGamepads();
        lastGamepadRefreshMs_ = now;
    }
    const KeyboardRuntimeState keyboard =
        synthesizeKeyboardState(keyboardBindings_, heldScancodes_);
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        PortState& p = ports_[port];

        // Table-driven: each RetroPad slot consults the binding table (the
        // pause menu's Physical Controller Settings editor mutates it at
        // runtime). A slot bound to a button polls that button; a slot bound
        // to an axis half polls the axis in the bound polarity past
        // kAxisBindLevel (after deadzone); an unbound slot never fires. The
        // defaults reproduce the built-in mapping exactly.
        int32_t mask = 0;
        if (gamepad != nullptr) {
            for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
                const bool pressed =
                    sourcePressed(gamepad, bindings_.get(slot)) ||
                    sourcePressed(gamepad, secondaryBindings_.get(slot));
                if (pressed) {
                    mask |= (1 << retroPadSlotJoypadBit(slot));
                }
            }
        }

        // Port 0 carries both the gamepad and the keyboard overlay (see
        // header): merge in the event-accumulated keyboard state instead
        // of clobbering it, so keyboard buttons keep working while a pad
        // occupies port 0. Both halves are level truth — the gamepad mask
        // is re-polled fresh each frame and keyboard bits persist only
        // until their KEY_UP event — so a released button clears on the
        // very next poll instead of latching into the accumulator.
        p.buttonsMask = mask | (port == 0 ? keyboard.buttonsMask : 0);

        if (gamepad != nullptr) {
            if (gameCubeBindings_) {
                p.leftX = analogValue(gamepad, kSlotSelect);
                p.leftY = analogValue(gamepad, kSlotLeftShoulder);
                p.rightX = analogValue(gamepad, kSlotLeftStick);
                p.rightY = analogValue(gamepad, kSlotRightStick);
            } else {
                p.leftX = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTX));
                p.leftY = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTY));
                p.rightX = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTX));
                p.rightY = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTY));
            }
            p.leftTrigger = analogValue(gamepad, kSlotLeftTrigger);
            p.rightTrigger = analogValue(gamepad, kSlotRightTrigger);
        } else {
            // No pad on this port: keep the snapshot neutral (closeGamepad
            // already resets the port when a device is removed).
            p.leftX = p.leftY = p.rightX = p.rightY = 0;
            p.leftTrigger = p.rightTrigger = 0;
        }

        if (port == 0) {
            if (keyboard.leftX != 0) p.leftX = keyboard.leftX;
            if (keyboard.leftY != 0) p.leftY = keyboard.leftY;
            if (keyboard.rightX != 0) p.rightX = keyboard.rightX;
            if (keyboard.rightY != 0) p.rightY = keyboard.rightY;
        }
    }
}

void SdlInput::configureForCore(const std::string& coreId) {
    coreId_ = coreId;
    gameCubeBindings_ = coreId == "dolphin";
    playStationBindings_ = coreId == "pcsx_rearmed" || coreId == "lrps2";
    applyCoreBindingDefaults();
    keyboardBindings_.resetForCore(coreId_);
}

void SdlInput::applyCoreBindingDefaults() {
    if (gameCubeBindings_) {
        for (int slot : {kSlotSelect, kSlotLeftShoulder, kSlotLeftStick, kSlotRightStick}) {
            bindings_.set(slot, gameCubeAnalogSourceForSlot(slot));
        }
    }
    if (playStationBindings_) {
        for (int slot : {kSlotA, kSlotB, kSlotX, kSlotY}) {
            bindings_.set(slot, playStationSourceForSlot(slot));
        }
    }
}

int16_t SdlInput::analogValue(SDL_Gamepad* gamepad, int slot) const {
    const auto read = [gamepad](const BindingSource& source) -> int16_t {
        if (source.kind == BindingSource::Kind::kButton) {
            return SDL_GetGamepadButton(gamepad, toSdlButton(source.button))
                ? std::numeric_limits<int16_t>::max() : 0;
        }
        if (source.kind != BindingSource::Kind::kAxis &&
            source.kind != BindingSource::Kind::kAxisDirection) {
            return 0;
        }
        const int16_t value = applyDeadzone(SDL_GetGamepadAxis(gamepad, toSdlAxis(source.axis)));
        if (source.kind == BindingSource::Kind::kAxis) return value;
        const int directed = static_cast<int>(value) * source.polarity;
        return directed > 0
            ? static_cast<int16_t>(std::min(directed, static_cast<int>(
                  std::numeric_limits<int16_t>::max())))
            : 0;
    };
    const int16_t primary = read(bindings_.get(slot));
    const int16_t secondary = read(secondaryBindings_.get(slot));
    return std::abs(static_cast<int>(secondary)) > std::abs(static_cast<int>(primary))
        ? secondary : primary;
}

std::optional<int> SdlInput::captureKeyboardScancode(const SDL_Event& event) {
    if (event.type != SDL_EVENT_KEY_DOWN || event.key.repeat ||
        event.key.scancode == SDL_SCANCODE_ESCAPE) {
        return std::nullopt;
    }
    const int scancode = static_cast<int>(event.key.scancode);
    return validKeyboardScancode(scancode) ? std::optional<int>(scancode)
                                           : std::nullopt;
}

void SdlInput::updateSession(romm::EmulationSession& session) {
    for (int port = 0; port < kPorts; ++port) {
        const PortState& p = ports_[port];
        session.updateInputState(
            port, p.buttonsMask, p.leftX, p.leftY, p.rightX, p.rightY,
            p.leftTrigger, p.rightTrigger);
    }
}

bool SdlInput::sourcePressed(
    SDL_Gamepad* gamepad,
    const BindingSource& source
) const {
    switch (source.kind) {
        case BindingSource::Kind::kButton:
            return SDL_GetGamepadButton(gamepad, toSdlButton(source.button));
        case BindingSource::Kind::kAxis:
            return false;
        case BindingSource::Kind::kAxisDirection: {
            const Sint16 raw = SDL_GetGamepadAxis(gamepad, toSdlAxis(source.axis));
            const int16_t value = applyDeadzone(raw);
            return source.polarity > 0 ? (value >= kAxisBindLevel)
                                       : (value <= -kAxisBindLevel);
        }
        case BindingSource::Kind::kUnbound:
            return false;
    }
    return false;
}

bool SdlInput::pollPauseTrigger() {
    bool trigger = false;
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad == nullptr) continue;

        PrevButtons& prev = prevButtons_[port];
        const bool primary = sourcePressed(gamepad, pauseMenuPrimary_);
        const bool secondary = sourcePressed(gamepad, pauseMenuSecondary_);

        if (pauseChordPressed(
                primary,
                secondary,
                prev.pausePrimary,
                prev.pauseSecondary
            )) {
            trigger = true;
        }

        prev.pausePrimary = primary;
        prev.pauseSecondary = secondary;
    }
    return trigger;
}

PauseMenuActions SdlInput::pollMenuActions() {
    PauseMenuActions actions{};
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad == nullptr) continue;

        PrevButtons& prev = prevButtons_[port];
        const bool up = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_UP);
        const bool down = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_DOWN);
        const bool left = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_LEFT);
        const bool right = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_RIGHT);
        const bool south = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_SOUTH);
        const bool east = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_EAST);
        const bool back = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_BACK);
        const bool start = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_START);

        if (up && !prev.up) actions.up = true;
        if (down && !prev.down) actions.down = true;
        if (left && !prev.left) actions.left = true;
        if (right && !prev.right) actions.right = true;
        // A or Start confirms; B or Back cancels (Back is the menu's "back"
        // control, matching Android's quick-Back dismissal).
        if ((south && !prev.south) || (start && !prev.start)) actions.confirm = true;
        if ((east && !prev.east) || (back && !prev.back)) actions.cancel = true;

        prev.up = up;
        prev.down = down;
        prev.left = left;
        prev.right = right;
        prev.south = south;
        prev.east = east;
        prev.back = back;
        prev.start = start;
    }
    return actions;
}

SdlInput::CaptureFrame SdlInput::captureFrame() {
    CaptureFrame frame{};
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad == nullptr) continue;

        CapturePortSample& s = frame.ports[frame.count++];
        s.port = port;
        for (int b = 0; b < kPadButtonCount; ++b) {
            s.buttons[b] = SDL_GetGamepadButton(gamepad, toSdlButton(static_cast<PadButton>(b)));
        }
        // Back edges for the coordinator's quick-cancel / held-clear logic.
        const bool backNow = s.buttons[static_cast<int>(PadButton::kBack)];
        s.backDown = backNow && !prevBackHeld_[port];
        s.backUp = !backNow && prevBackHeld_[port];
        prevBackHeld_[port] = backNow;
        for (int a = 0; a < kPadAxisCount; ++a) {
            const PadAxis axis = static_cast<PadAxis>(a);
            const Sint16 raw = SDL_GetGamepadAxis(gamepad, toSdlAxis(axis));
            // Sticks: -32768..32767 -> ~-1..+1. Triggers: 0..32767 -> 0..+1
            // (unidirectional, like Android's trigger normalization).
            s.axes[a] = static_cast<float>(raw) / 32767.0f;
        }
    }
    return frame;
}

void SdlInput::resetMenuEdges() {
    prevButtons_ = {};
    prevBackHeld_ = {};
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad == nullptr) continue;

        PrevButtons& prev = prevButtons_[port];
        prev.up = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_UP);
        prev.down = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_DOWN);
        prev.left = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_LEFT);
        prev.right = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_RIGHT);
        prev.south = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_SOUTH);
        prev.east = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_EAST);
        prev.back = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_BACK);
        prev.start = SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_START);
        prev.pausePrimary = sourcePressed(gamepad, pauseMenuPrimary_);
        prev.pauseSecondary = sourcePressed(gamepad, pauseMenuSecondary_);
        prevBackHeld_[port] = prev.back;
    }
}

std::string SdlInput::joystickGuidString(int port) const {
    if (!hasGamepad(port)) return "";
    SDL_Joystick* joystick = SDL_GetGamepadJoystick(gamepads_[port].gamepad);
    if (joystick == nullptr) return "";
    char buffer[64] = {};
    SDL_GUIDToString(SDL_GetJoystickGUIDForID(SDL_GetJoystickID(joystick)), buffer,
                     sizeof(buffer));
    return buffer;
}

void SdlInput::reset() {
    heldScancodes_.fill(false);
    for (auto& port : ports_) {
        port = PortState{};
    }
    // Seed edge history too: a held button must not become a fresh press.
    resetMenuEdges();
}

}  // namespace romm::player

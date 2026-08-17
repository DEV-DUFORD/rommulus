// sdl_input.cpp — SDL3 keyboard + gamepad → four-port RetroPad state.
//
// See sdl_input.h for the port-assignment and key-mapping contract. All
// SDL calls here run on the main thread only (event handling, per-frame
// polling, and updateSession), so no synchronization is needed inside this
// class; EmulationSession::updateInputState() is itself safe from any
// thread other than the emulation thread.
#include "native/player/sdl_input.h"

#include <libretro.h>

#include "emulation_session.h"

namespace romm::player {
namespace {

// A small deadzone (~12% of full scale) so a resting stick does not drift
// into the first few RETRO_DEVICE_ID_ANALOG steps.
constexpr Sint16 kAxisDeadzone = 4096;

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

int SdlInput::keyboardButtonBit(SDL_Scancode scancode) {
    switch (scancode) {
        case SDL_SCANCODE_W:
        case SDL_SCANCODE_UP:
            return RETRO_DEVICE_ID_JOYPAD_UP;
        case SDL_SCANCODE_S:
        case SDL_SCANCODE_DOWN:
            return RETRO_DEVICE_ID_JOYPAD_DOWN;
        case SDL_SCANCODE_A:
        case SDL_SCANCODE_LEFT:
            return RETRO_DEVICE_ID_JOYPAD_LEFT;
        case SDL_SCANCODE_D:
        case SDL_SCANCODE_RIGHT:
            return RETRO_DEVICE_ID_JOYPAD_RIGHT;
        case SDL_SCANCODE_RETURN:
        case SDL_SCANCODE_KP_ENTER:
        case SDL_SCANCODE_SPACE:
            return RETRO_DEVICE_ID_JOYPAD_A;  // south
        case SDL_SCANCODE_LSHIFT:
        case SDL_SCANCODE_RSHIFT:
            return RETRO_DEVICE_ID_JOYPAD_B;  // east
        case SDL_SCANCODE_X:
            return RETRO_DEVICE_ID_JOYPAD_X;  // west
        case SDL_SCANCODE_Z:
            return RETRO_DEVICE_ID_JOYPAD_Y;  // north
        case SDL_SCANCODE_LCTRL:
            return RETRO_DEVICE_ID_JOYPAD_SELECT;
        case SDL_SCANCODE_RCTRL:
            return RETRO_DEVICE_ID_JOYPAD_START;
        default:
            return -1;  // Escape and everything else is main()'s business
    }
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
            const int bit = keyboardButtonBit(event.key.scancode);
            if (bit < 0) break;
            if (event.type == SDL_EVENT_KEY_DOWN) {
                ports_[0].buttonsMask |= (1 << bit);
            } else {
                ports_[0].buttonsMask &= ~(1 << bit);
            }
            break;
        }
        default:
            break;
    }
}

void SdlInput::poll() {
    for (int port = 0; port < kPorts; ++port) {
        SDL_Gamepad* gamepad = gamepads_[port].gamepad;
        if (gamepad == nullptr) continue;

        PortState& p = ports_[port];
        int32_t mask = 0;
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_SOUTH)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_A);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_EAST)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_B);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_WEST)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_X);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_NORTH)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_Y);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_BACK)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_SELECT);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_START)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_START);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_LEFT_SHOULDER)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_L);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_R);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_UP)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_UP);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_DOWN)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_DOWN);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_LEFT)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_LEFT);
        }
        if (SDL_GetGamepadButton(gamepad, SDL_GAMEPAD_BUTTON_DPAD_RIGHT)) {
            mask |= (1 << RETRO_DEVICE_ID_JOYPAD_RIGHT);
        }
        // Port 0 carries both the gamepad and the keyboard overlay (see
        // header): OR in the accumulated keyboard state instead of
        // clobbering it, so keyboard buttons keep working while a pad
        // occupies port 0. Keyboard bits persist until their KEY_UP event;
        // gamepad bits are re-polled fresh each frame.
        if (port == 0) {
            p.buttonsMask = mask | p.buttonsMask;
        } else {
            p.buttonsMask = mask;
        }

        p.leftX = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTX));
        p.leftY = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_LEFTY));
        p.rightX = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTX));
        p.rightY = applyDeadzone(SDL_GetGamepadAxis(gamepad, SDL_GAMEPAD_AXIS_RIGHTY));
    }
}

void SdlInput::updateSession(romm::EmulationSession& session) {
    for (int port = 0; port < kPorts; ++port) {
        const PortState& p = ports_[port];
        session.updateInputState(port, p.buttonsMask, p.leftX, p.leftY, p.rightX, p.rightY);
    }
}

void SdlInput::reset() {
    for (auto& port : ports_) {
        port = PortState{};
    }
}

}  // namespace romm::player

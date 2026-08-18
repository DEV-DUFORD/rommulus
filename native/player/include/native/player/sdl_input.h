// sdl_input.h — SDL3 keyboard + gamepad input for rommulus-player.
//
// Converts SDL keyboard and up-to-four-gamepad state into the engine's
// four-port normalized RetroPad snapshot (LIBRETRO_REFACTOR.md section 9)
// and pushes it to an EmulationSession once per frame.
//
// Port assignment: gamepads are assigned to ports 0..3 in the order SDL
// enumerates them at construction (SDL_GetGamepads), and newly added
// gamepads take the lowest free port. The keyboard is always merged into
// port 0 (it never steals a port from a connected gamepad — it overlays
// on top of whatever that gamepad produces each frame).
//
// Keyboard mapping (physical scancodes, so layout-independent):
//   W / Up arrow       = d-pad up        S / Down arrow     = d-pad down
//   A / Left arrow     = d-pad left      D / Right arrow    = d-pad right
//   Enter / Space      = A (south)       LShift / RShift    = B (east)
//   X                  = X (west)        Z                  = Y (north)
//   LCtrl              = select          RCtrl              = start
// Escape is deliberately NOT handled here — main() owns quit/pause.
#pragma once

#include <SDL3/SDL.h>

#include <array>
#include <cstdint>

namespace romm {
class EmulationSession;
}

namespace romm::player {

class SdlInput {
public:
    static constexpr int kPorts = 4;

    // Opens every currently-connected gamepad and assigns it to ports 0..3
    // in SDL enumeration order. Requires SDL_INIT_GAMEPAD to be active.
    SdlInput();
    ~SdlInput();

    SdlInput(const SdlInput&) = delete;
    SdlInput& operator=(const SdlInput&) = delete;

    // Routes one event: opens newly added gamepads (lowest free port),
    // closes removed ones (neutralizing their port), and tracks key
    // down/up state for the port 0 keyboard mapping. Safe to call with
    // any event type — uninteresting events are ignored.
    void handleEvent(const SDL_Event& event);

    // Per-frame poll: re-reads every connected gamepad's buttons and
    // sticks (with a small deadzone applied to the analog axes) into the
    // per-port snapshot. Gamepad buttons are level-polled fresh each
    // frame, so released buttons clear immediately — the mask handed to
    // the core is the current state at poll time. The keyboard state
    // accumulated by handleEvent() is merged into port 0 (SDL synthesizes
    // key-up events on focus loss, so no keys can stick).
    void poll();

    // Pushes the current four-port snapshot to the session (one
    // updateInputState() call per port, so ports with no device report
    // neutral state rather than going stale).
    void updateSession(romm::EmulationSession& session);

    // Clears all four ports to neutral (no buttons, centered sticks).
    // Call on window focus loss and before quit so a held key/button can
    // never leak into the core after we stop pumping events.
    void reset();

private:
    struct PortState {
        int32_t buttonsMask = 0;  // RETRO_DEVICE_ID_JOYPAD_* bit flags
        int16_t leftX = 0;
        int16_t leftY = 0;
        int16_t rightX = 0;
        int16_t rightY = 0;
    };

    struct GamepadSlot {
        SDL_Gamepad* gamepad = nullptr;
    };

    // Lowest port with no gamepad assigned, or -1 when all four are busy.
    int findFreePort() const;
    void openGamepad(int port, SDL_JoystickID instanceId);
    void closeGamepad(int port);

    // The RETRO_DEVICE_ID_JOYPAD_* value for a physical key scancode, or
    // -1 when the key is not part of the mapping.
    static int keyboardButtonBit(SDL_Scancode scancode);
    static int16_t applyDeadzone(Sint16 value);

    std::array<PortState, kPorts> ports_{};
    std::array<GamepadSlot, kPorts> gamepads_{};

    // Port 0 bit flags accumulated from KEY_DOWN/KEY_UP events (see
    // keyboardButtonBit). Merged into port 0's snapshot by poll() each
    // frame and cleared by reset(). Kept separate from ports_[0] so the
    // gamepad half of port 0 can be re-polled fresh without latching.
    int32_t keyboardMask_ = 0;
};

}  // namespace romm::player

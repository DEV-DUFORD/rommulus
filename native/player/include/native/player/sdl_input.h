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
#include <string>

#include "native/player/binding_table.h"
#include "native/player/pause_menu.h"

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

    // Edge-detected pause trigger: true on the single frame in which a
    // gamepad's L3+R3 pause combination transitions from not-held to held.
    // Call once per frame while gameplay input is active.
    bool pollPauseTrigger();

    // Edge-detected menu navigation for the pause overlay: d-pad up/down/
    // left/right, A or Start (confirm), B or Back (cancel) — each true only
    // on the frame the control newly pressed. Call once per frame while the
    // overlay is open. Shares its per-button edge state with
    // pollPauseTrigger() so a button held across an open/close transition
    // cannot re-trigger either path.
    PauseMenuActions pollMenuActions();

    // --- Binding editor support (the pause menu's Physical Controller
    // Settings). The table maps each of the 16 RetroPad slots to a physical
    // control; poll() consults it instead of a hardcoded mapping. Defaults
    // are the built-in mapping; the editor mutates it at runtime.
    const BindingTable& bindings() const { return bindings_; }
    const BindingTable& secondaryBindings() const { return secondaryBindings_; }
    const BindingSource& bindingForSlot(int slot, int bindingSlot = 0) const {
        return bindingSlot == 1 ? secondaryBindings_.get(slot) : bindings_.get(slot);
    }
    void setBinding(int slot, BindingSource source, int bindingSlot = 0) {
        (bindingSlot == 1 ? secondaryBindings_ : bindings_).set(slot, source);
    }
    // Replaces the ENTIRE table at once (the v2 launch request seeds stored
    // bindings before the first frame; the editor mutates per slot).
    void setBindings(const BindingTable& table, const BindingTable& secondary = BindingTable(false)) {
        bindings_ = table;
        secondaryBindings_ = secondary;
    }
    // Restores the built-in default mapping (the editor's Reset to Default).
    void resetBindings() {
        bindings_.reset();
        secondaryBindings_.clear();
    }
    // Explicitly unmaps every slot (the editor's Clear Mappings action).
    void clearBindings() {
        bindings_.clear();
        secondaryBindings_.clear();
    }

    // True when a gamepad occupies the port.
    bool hasGamepad(int port) const {
        return port >= 0 && port < kPorts && gamepads_[port].gamepad != nullptr;
    }

    // One frame of capture samples for the binding editor: current button
    // levels and normalized axis values for every connected pad, plus Back
    // press/release edges. Call ONCE per frame while the pause menu is in
    // its binding-capture state — pollMenuActions() must NOT be called then
    // (the capture coordinator owns gamepad input). Levels are read fresh
    // from SDL each call, so a pad that hot-unplugs simply drops out of the
    // next frame.
    struct CapturePortSample {
        int port = -1;                       // player port index of this pad
        bool backDown = false;               // Back newly pressed this frame
        bool backUp = false;                 // Back released this frame
        bool buttons[kPadButtonCount] = {};  // level per PadButton
        float axes[kPadAxisCount] = {};      // sticks -1..+1, triggers 0..+1
    };
    struct CaptureFrame {
        CapturePortSample ports[kPorts]{};
        int count = 0;  // number of connected pads
    };
    CaptureFrame captureFrame();

    // Seeds menu/capture edge history from current controller levels. Call
    // when entering/leaving capture so the button that opened or completed
    // capture cannot become a fresh confirm/cancel on the next frame.
    void resetMenuEdges();

    // The canonical SDL joystick GUID string for a port's pad (the stable
    // persistence key, LINUX_X64.md section 11.9), or "" when no pad is on
    // the port.
    std::string joystickGuidString(int port) const;

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

    // The RetroPad slot -> physical control table poll() consults. Owned by
    // the editor (setBinding / resetBindings); defaults are the built-in
    // mapping. See binding_table.h.
    BindingTable bindings_{};
    BindingTable secondaryBindings_{false};

    // Port 0 bit flags accumulated from KEY_DOWN/KEY_UP events (see
    // keyboardButtonBit). Merged into port 0's snapshot by poll() each
    // frame and cleared by reset(). Kept separate from ports_[0] so the
    // gamepad half of port 0 can be re-polled fresh without latching.
    int32_t keyboardMask_ = 0;

    // Per-port previous-frame levels for the buttons consumed by
    // pollPauseTrigger()/pollMenuActions(). Shared between the two so edge
    // detection stays correct across a pause open/close transition (a button
    // still held when the menu closes must not immediately re-trigger).
    struct PrevButtons {
        bool back = false;
        bool start = false;
        bool leftStick = false;   // L3
        bool rightStick = false;  // R3
        bool up = false;
        bool down = false;
        bool left = false;
        bool right = false;
        bool south = false;       // A — menu confirm
        bool east = false;        // B — menu cancel
    };
    std::array<PrevButtons, kPorts> prevButtons_{};

    // Per-port previous-frame Back level for the capture editor's
    // press/release edges (captureFrame). Separate from prevButtons_.back so
    // the two consumers never disturb each other's edge state.
    std::array<bool, kPorts> prevBackHeld_{};
};

}  // namespace romm::player

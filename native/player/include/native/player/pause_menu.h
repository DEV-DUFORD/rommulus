// pause_menu.h — SDL-free state machine for the in-game pause overlay.
//
// Mirrors the Android host's pause behavior (EmulationActivity.kt,
// LIBRETRO_REFACTOR.md section 13): a single menu with Resume / Video
// Options / Controller Settings / Quit items, opened by a quick Back tap or
// the controller's pause combination, plus a "Quit game?" Yes/No confirm
// dialog on top. While the state machine is in any open state the caller
// must keep the EmulationSession paused (setPaused(true)); the kResume
// effect means "unpause now" and kQuit means "checkpoint and exit cleanly".
//
// Video Options and Controller Settings are present but DISABLED placeholders
// in this sub-unit (follow-up sub-units wire them to real settings screens);
// navigation skips over them. The item list is structured so enabling them
// later is a one-line change in itemEnabled().
//
// This class is intentionally SDL-free so it can be unit-tested on the host
// (native/tests/test_pause_menu.cpp). The caller feeds it one frame of
// edge-detected actions and executes the returned effect.
#pragma once

namespace romm::player {

enum class PauseMenuState {
    kClosed,      // No overlay; gameplay input is routed normally.
    kOpen,        // The pause menu (Resume / Video Options / Controller Settings / Quit) is visible.
    kQuitConfirm, // The "Quit game?" Yes/No dialog is visible on top of the menu.
};

// One frame of edge-detected input for the overlay. Each field is true only
// on the frame a control newly transitioned to pressed (the SDL layer does
// level-to-edge conversion; keyboard events arrive as edges naturally).
struct PauseMenuActions {
    bool up = false;
    bool down = false;
    bool left = false;
    bool right = false;
    bool confirm = false;  // A / Start / Enter / Space
    bool cancel = false;   // B / Back / Escape

    bool any() const { return up || down || left || right || confirm || cancel; }
};

// What the caller must do after handle() returns.
enum class PauseMenuEffect {
    kNone,    // No state change requiring action.
    kResume,  // The menu closed via Resume (or cancel): call setPaused(false).
    kQuit,    // Quit was confirmed: checkpoint and exit the session cleanly.
};

class PauseMenu {
public:
    static constexpr int kItemCount = 4;
    // Item indices, in the same order as Android's PauseMenuOverlay.
    static constexpr int kResumeItem = 0;
    static constexpr int kVideoOptionsItem = 1;
    static constexpr int kControllerSettingsItem = 2;
    static constexpr int kQuitItem = 3;
    // Selection indices while in kQuitConfirm.
    static constexpr int kConfirmYes = 0;
    static constexpr int kConfirmNo = 1;

    PauseMenuState state() const { return state_; }
    bool isOpen() const { return state_ != PauseMenuState::kClosed; }
    // Current selection: an item index in kOpen, a confirm option in
    // kQuitConfirm. Meaningless in kClosed.
    int selection() const { return selection_; }

    static const char* itemLabel(int index);
    // Video Options / Controller Settings are disabled placeholders until the
    // follow-up sub-units land.
    static bool itemEnabled(int index);
    static const char* confirmOptionLabel(int index);  // "Yes" / "No"

    // CLOSED -> OPEN with Resume focused (Android requests focus on RESUME
    // for a fresh CLOSED -> MENU transition). No-op when already open.
    void open() {
        if (state_ == PauseMenuState::kClosed) {
            state_ = PauseMenuState::kOpen;
            selection_ = kResumeItem;
        }
    }

    // Any open state -> CLOSED, resetting focus to Resume for the next open.
    void close() {
        state_ = PauseMenuState::kClosed;
        selection_ = kResumeItem;
    }

    // Feeds one frame of actions and returns the effect to execute. In
    // kClosed this is a no-op (the caller opens the menu from its own pause
    // trigger, not from these actions).
    PauseMenuEffect handle(const PauseMenuActions& a) {
        switch (state_) {
            case PauseMenuState::kClosed:
                return PauseMenuEffect::kNone;
            case PauseMenuState::kOpen:
                return handleOpen(a);
            case PauseMenuState::kQuitConfirm:
                return handleQuitConfirm(a);
        }
        return PauseMenuEffect::kNone;
    }

private:
    // Moves the selection by +/-1, wrapping and skipping disabled items.
    void moveSelection(int delta) {
        int candidate = selection_;
        for (int step = 0; step < kItemCount; ++step) {
            candidate = (candidate + delta + kItemCount) % kItemCount;
            if (itemEnabled(candidate)) break;
        }
        selection_ = candidate;
    }

    PauseMenuEffect handleOpen(const PauseMenuActions& a) {
        if (a.up) moveSelection(-1);
        if (a.down) moveSelection(+1);
        if (a.cancel) {
            // Back/Escape closes the menu and resumes (Android's
            // quickBackTransition: MENU -> CLOSED).
            close();
            return PauseMenuEffect::kResume;
        }
        if (a.confirm && itemEnabled(selection_)) {
            switch (selection_) {
                case kResumeItem:
                    close();
                    return PauseMenuEffect::kResume;
                case kQuitItem:
                    // The user already expressed intent to quit, so the
                    // dialog defaults to Yes (No is one press away).
                    state_ = PauseMenuState::kQuitConfirm;
                    selection_ = kConfirmYes;
                    return PauseMenuEffect::kNone;
                default:
                    break;  // Disabled placeholders are unreachable via navigation.
            }
        }
        return PauseMenuEffect::kNone;
    }

    PauseMenuEffect handleQuitConfirm(const PauseMenuActions& a) {
        if (a.up || a.down || a.left || a.right) {
            selection_ = 1 - selection_;  // toggle Yes/No
        }
        if (a.cancel) {
            // Dismiss the dialog back to the menu, focused on Quit
            // (Android's AlertDialog onDismissRequest).
            state_ = PauseMenuState::kOpen;
            selection_ = kQuitItem;
            return PauseMenuEffect::kNone;
        }
        if (a.confirm) {
            if (selection_ == kConfirmYes) {
                close();
                return PauseMenuEffect::kQuit;
            }
            // "No": back to the menu, focused on Quit.
            state_ = PauseMenuState::kOpen;
            selection_ = kQuitItem;
        }
        return PauseMenuEffect::kNone;
    }

    PauseMenuState state_ = PauseMenuState::kClosed;
    int selection_ = 0;
};

inline const char* PauseMenu::itemLabel(int index) {
    switch (index) {
        case kResumeItem: return "Resume";
        case kVideoOptionsItem: return "Video Options";
        case kControllerSettingsItem: return "Controller Settings";
        case kQuitItem: return "Quit";
        default: return "";
    }
}

inline bool PauseMenu::itemEnabled(int index) {
    // Video Options and Controller Settings are disabled placeholders in this
    // sub-unit; the follow-up settings sub-units flip them to enabled.
    return index == kResumeItem || index == kQuitItem;
}

inline const char* PauseMenu::confirmOptionLabel(int index) {
    return index == kConfirmYes ? "Yes" : "No";
}

}  // namespace romm::player

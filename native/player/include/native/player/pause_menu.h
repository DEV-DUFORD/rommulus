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
// Video Options opens a submenu (kVideoOptions) with three runtime toggles
// mirroring Android's VideoOptionsDialog: Scanlines, Integer Scaling, and
// Sharp Filter. The state machine owns the toggle states (so the overlay can
// draw ON/OFF); handle() reports each change as a kToggle* effect and the
// caller applies it to the video sink immediately. Controller Settings opens
// the active core's editable configuration directly, matching Android. The
// kPhysicalBindings list contains the core's digital RetroPad slots (each
// showing its current binding, read from SdlInput's BindingTable) plus Reset
// Controller and Clear Mappings actions.
// Confirming a slot row enters kBindingCapture: the caller starts the
// capture coordinator (binding_capture.h, ported Android semantics) for that
// slot and feeds it raw gamepad levels each frame — while capturing, the
// overlay owns NO menu actions (confirm/cancel come from the coordinator's
// terminal states, not handle()). Confirming Reset to Default reports a
// kResetDefault effect; the caller restores the default mapping. Back/Escape
// from kPhysicalBindings returns to the pause menu; capture completion or
// cancellation returns to the same focused binding row.
//
// This class is intentionally SDL-free so it can be unit-tested on the host
// (native/tests/test_pause_menu.cpp). The caller feeds it one frame of
// edge-detected actions and executes the returned effect.
#pragma once

#include "native/player/binding_table.h"

namespace romm::player {

enum class PauseMenuState {
    kClosed,             // No overlay; gameplay input is routed normally.
    kOpen,               // The pause menu (Resume / Video Options / Controller Settings / Quit) is visible.
    kQuitConfirm,        // The "Quit game?" Yes/No dialog is visible on top of the menu.
    kVideoOptions,       // The Video Options submenu (Scanlines / Integer Scaling / Sharp Filter) is visible.
    kPhysicalBindings,   // The editable binding list plus reset/clear header actions.
    kBindingCapture,     // Capturing a new binding for the slot in selection() —
                         // gamepad input is owned by the capture coordinator;
                         // handle() only serves cancel (keyboard Escape).
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
    kNone,                 // No state change requiring action.
    kResume,               // The menu closed via Resume (or cancel): call setPaused(false).
    kQuit,                 // Quit was confirmed: checkpoint and exit the session cleanly.
    // A Video Options toggle changed: apply the menu's NEW state for that
    // setting to the video sink (read it back via scanlinesEnabled() /
    // integerScalingEnabled() / sharpFilterEnabled()).
    kToggleScanlines,
    kToggleIntegerScaling,
    kToggleSharpFilter,
    // A slot row was confirmed in kPhysicalBindings: start capturing a new
    // binding for selection() (the capture coordinator; the menu is now in
    // kBindingCapture).
    kBeginCapture,
    // Reset to Default was confirmed in kPhysicalBindings: restore SdlInput's
    // default binding table.
    kResetDefault,
    // Clear Mappings was confirmed in kPhysicalBindings: explicitly unmap all slots.
    kClearMappings,
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
    // Toggle-row indices while in kVideoOptions, in the same order as
    // Android's VideoOptionsDialog.
    static constexpr int kVideoOptionCount = 3;
    static constexpr int kScanlinesItem = 0;
    static constexpr int kIntegerScalingItem = 1;
    static constexpr int kSharpFilterItem = 2;
    // Maximum selection indices while in kPhysicalBindings: the 16 RetroPad
    // slots followed by the Reset Controller and Clear Mappings actions.
    static constexpr int kBindingSlotCount = kRetroPadSlotCount;
    static constexpr int kResetDefaultItem = kBindingSlotCount;
    static constexpr int kClearMappingsItem = kBindingSlotCount + 1;
    static constexpr int kPhysicalRowCount = kBindingSlotCount + 2;

    PauseMenuState state() const { return state_; }
    bool isOpen() const { return state_ != PauseMenuState::kClosed; }
    // Current selection: an item index in kOpen, a confirm option in
    // kQuitConfirm, a toggle-row index in kVideoOptions, or a row index
    // (0..12) in kPhysicalBindings. In kBindingCapture it is the
    // RetroPad slot being captured. Meaningless in kClosed.
    int selection() const { return selection_; }
    int bindingSlotCount() const { return bindingSlotCount_; }
    int resetDefaultItem() const { return bindingSlotCount_; }
    int clearMappingsItem() const { return bindingSlotCount_ + 1; }
    void setBindingSlotCount(int count) {
        bindingSlotCount_ = count < 1 ? 1 : (count > kBindingSlotCount ? kBindingSlotCount : count);
    }
    // 0 = Primary, 1 = Secondary while a binding row is selected.
    int bindingColumn() const { return bindingColumn_; }

    // First binding row to draw for a viewport of `visibleRows`. The
    // selection drives the viewport, so controller navigation always keeps
    // the focused row visible without coupling this state machine to SDL.
    int bindingViewportStart(int visibleRows) const {
        if (visibleRows <= 0 || visibleRows >= bindingSlotCount_) return 0;
        const int focusedRow =
            selection_ < bindingSlotCount_ ? selection_ : bindingSlotCount_ - 1;
        const int maxStart = bindingSlotCount_ - visibleRows;
        const int desired = focusedRow - visibleRows + 1;
        return desired < 0 ? 0 : (desired > maxStart ? maxStart : desired);
    }

    // True while capturing a binding for the slot in selection(). While this
    // is true the caller must feed the capture coordinator raw gamepad
    // levels (NOT pollMenuActions) and act on its terminal states.
    bool isCapturingBinding() const { return state_ == PauseMenuState::kBindingCapture; }

    // Returns from kBindingCapture to the slot list, keeping focus on the
    // captured slot. No-op in any other state. Called by the caller when the
    // capture coordinator reaches a terminal state (or Escape is pressed).
    void exitCapture() {
        if (state_ == PauseMenuState::kBindingCapture) {
            state_ = PauseMenuState::kPhysicalBindings;
        }
    }

    // The Video Options toggle states. The menu owns them so the overlay can
    // draw ON/OFF; the caller seeds them from the launch request and applies
    // each kToggle* effect back to the video sink.
    bool scanlinesEnabled() const { return scanlinesEnabled_; }
    bool integerScalingEnabled() const { return integerScalingEnabled_; }
    bool sharpFilterEnabled() const { return sharpFilterEnabled_; }
    void setVideoToggles(bool scanlines, bool integerScaling, bool sharpFilter) {
        scanlinesEnabled_ = scanlines;
        integerScalingEnabled_ = integerScaling;
        sharpFilterEnabled_ = sharpFilter;
    }

    static const char* itemLabel(int index);
    static bool itemEnabled(int index);  // all four items are live now
    static const char* confirmOptionLabel(int index);  // "Yes" / "No"
    // Toggle-row labels while in kVideoOptions ("Scanlines" / ...).
    static const char* videoOptionLabel(int index);
    // Labels while in kPhysicalBindings.
    static const char* physicalRowLabel(int index);

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
            case PauseMenuState::kVideoOptions:
                return handleVideoOptions(a);
            case PauseMenuState::kPhysicalBindings:
                return handlePhysicalBindings(a);
            case PauseMenuState::kBindingCapture:
                return handleBindingCapture(a);
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

    // Moves the Video Options submenu selection by +/-1, wrapping over its
    // three (always-enabled) toggle rows.
    void moveVideoSelection(int delta) {
        selection_ = (selection_ + delta + kVideoOptionCount) % kVideoOptionCount;
    }

    // Moves the physical-binding list selection by +/-1, wrapping over the
    // configured core rows plus the two header actions.
    void movePhysicalSelection(int delta) {
        const int rowCount = bindingSlotCount_ + 2;
        selection_ = (selection_ + delta + rowCount) % rowCount;
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
                case kVideoOptionsItem:
                    // Open the Video Options submenu, focused on the first
                    // toggle (Android requests focus on the scanlines row).
                    state_ = PauseMenuState::kVideoOptions;
                    selection_ = kScanlinesItem;
                    return PauseMenuEffect::kNone;
                case kControllerSettingsItem:
                    // Android opens the active core's full controller
                    // configuration directly; desktop does the same.
                    state_ = PauseMenuState::kPhysicalBindings;
                    selection_ = kSlotA;
                    bindingColumn_ = 0;
                    return PauseMenuEffect::kNone;
                case kQuitItem:
                    // The user already expressed intent to quit, so the
                    // dialog defaults to Yes (No is one press away).
                    state_ = PauseMenuState::kQuitConfirm;
                    selection_ = kConfirmYes;
                    return PauseMenuEffect::kNone;
                default:
                    break;  // All items are enabled; unreachable.
            }
        }
        return PauseMenuEffect::kNone;
    }

    PauseMenuEffect handleVideoOptions(const PauseMenuActions& a) {
        if (a.up) moveVideoSelection(-1);
        if (a.down) moveVideoSelection(+1);
        if (a.cancel) {
            // Back/Escape returns to the main menu, focused on Video Options
            // (Android's dialog dismissRequest — it does NOT close the pause
            // menu itself).
            state_ = PauseMenuState::kOpen;
            selection_ = kVideoOptionsItem;
            return PauseMenuEffect::kNone;
        }
        if (a.confirm || a.left || a.right) {
            switch (selection_) {
                case kScanlinesItem:
                    scanlinesEnabled_ = !scanlinesEnabled_;
                    return PauseMenuEffect::kToggleScanlines;
                case kIntegerScalingItem:
                    integerScalingEnabled_ = !integerScalingEnabled_;
                    return PauseMenuEffect::kToggleIntegerScaling;
                case kSharpFilterItem:
                    sharpFilterEnabled_ = !sharpFilterEnabled_;
                    return PauseMenuEffect::kToggleSharpFilter;
                default:
                    break;  // unreachable: the submenu only has these three rows
            }
        }
        return PauseMenuEffect::kNone;
    }

    PauseMenuEffect handlePhysicalBindings(const PauseMenuActions& a) {
        if (a.up) movePhysicalSelection(-1);
        if (a.down) movePhysicalSelection(+1);
        if (selection_ < bindingSlotCount_) {
            if (a.left) bindingColumn_ = 0;
            if (a.right) bindingColumn_ = 1;
        } else {
            bindingColumn_ = 0;
        }
        if (a.cancel) {
            // Back/Escape returns directly to the pause menu, focused on the
            // Controller Settings item, matching Android's active-core page.
            state_ = PauseMenuState::kOpen;
            selection_ = kControllerSettingsItem;
            return PauseMenuEffect::kNone;
        }
        if (a.confirm) {
            if (selection_ < bindingSlotCount_) {
                // Enter capture mode for the focused slot. The caller starts
                // the capture coordinator (kBeginCapture) and feeds it raw
                // gamepad levels each frame.
                state_ = PauseMenuState::kBindingCapture;
                return PauseMenuEffect::kBeginCapture;
            }
            return selection_ == resetDefaultItem()
                ? PauseMenuEffect::kResetDefault
                : PauseMenuEffect::kClearMappings;
        }
        return PauseMenuEffect::kNone;
    }

    PauseMenuEffect handleBindingCapture(const PauseMenuActions& a) {
        // While capturing, gamepad input is owned by the capture coordinator
        // (confirm/cancel arrive as its terminal states, not menu actions).
        // The only menu action honored here is cancel — keyboard Escape —
        // which returns to the slot list focused on the captured slot.
        if (a.cancel) {
            exitCapture();
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
    int bindingColumn_ = 0;
    int bindingSlotCount_ = kBindingSlotCount;
    bool scanlinesEnabled_ = false;
    bool integerScalingEnabled_ = false;
    bool sharpFilterEnabled_ = false;
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
    // All four items are live.
    return index == kResumeItem || index == kVideoOptionsItem ||
           index == kControllerSettingsItem || index == kQuitItem;
}

inline const char* PauseMenu::confirmOptionLabel(int index) {
    return index == kConfirmYes ? "Yes" : "No";
}

inline const char* PauseMenu::videoOptionLabel(int index) {
    switch (index) {
        case kScanlinesItem: return "Scanlines";
        case kIntegerScalingItem: return "Integer Scaling";
        case kSharpFilterItem: return "Sharp Filter";
        default: return "";
    }
}

inline const char* PauseMenu::physicalRowLabel(int index) {
    if (index == kResetDefaultItem) return "Reset to Default";
    if (index == kClearMappingsItem) return "Clear Mappings";
    if (index >= 0 && index < kBindingSlotCount) return retroPadSlotLabel(index);
    return "";
}

}  // namespace romm::player

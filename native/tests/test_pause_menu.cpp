// test_pause_menu.cpp — host unit tests for the SDL-free pause overlay state
// machine (native/player/include/native/player/pause_menu.h). Covers open/
// close, D-pad navigation with wrapping, Resume, the "Quit game?" confirm
// dialog (Yes/No/cancel), the Video Options submenu (enter/focus, toggle
// rows via confirm or left/right, Return to menu), the Controller Settings
// submenu (enter/focus, navigation, the Return item and Back to menu, the
// editable physical binding list: 12 slots + Reset to Default, capture-mode
// entry/exit), and effect reporting.

#include <string>

#include "romm_test.h"

#include "native/player/pause_menu.h"

using romm::player::PauseMenu;
using romm::player::PauseMenuActions;
using romm::player::PauseMenuEffect;
using romm::player::PauseMenuState;

namespace {

PauseMenuActions upAction() {
    PauseMenuActions a{};
    a.up = true;
    return a;
}

PauseMenuActions downAction() {
    PauseMenuActions a{};
    a.down = true;
    return a;
}

PauseMenuActions confirmAction() {
    PauseMenuActions a{};
    a.confirm = true;
    return a;
}

PauseMenuActions cancelAction() {
    PauseMenuActions a{};
    a.cancel = true;
    return a;
}

PauseMenuActions leftAction() {
    PauseMenuActions a{};
    a.left = true;
    return a;
}

PauseMenuActions rightAction() {
    PauseMenuActions a{};
    a.right = true;
    return a;
}

// Opens the menu and navigates to Quit (three Downs: Resume -> Video Options
// -> Controller Settings -> Quit).
void selectQuit(PauseMenu& menu) {
    menu.open();
    menu.handle(downAction());
    menu.handle(downAction());
    menu.handle(downAction());
}

// Opens the menu and navigates to Controller Settings (two Downs: Resume ->
// Video Options -> Controller Settings).
void selectControllerSettings(PauseMenu& menu) {
    menu.open();
    menu.handle(downAction());
    menu.handle(downAction());
}

void testInitialAndClosedNoop() {
    PauseMenu menu;
    CHECK(menu.state() == PauseMenuState::kClosed);
    CHECK(!menu.isOpen());
    // Actions are ignored while closed (the caller opens from its own trigger).
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kClosed);
}

void testOpenFocusesResume() {
    PauseMenu menu;
    menu.open();
    CHECK(menu.isOpen());
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);
    // open() is idempotent and never moves the selection.
    menu.handle(downAction());
    menu.open();
    CHECK(menu.state() == PauseMenuState::kOpen);
}

void testNavigationWalksAllItemsAndWraps() {
    PauseMenu menu;
    menu.open();
    // All four items are enabled: Down walks Resume -> Video Options ->
    // Controller Settings -> Quit, then wraps back to Resume.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kVideoOptionsItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kControllerSettingsItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);
    // Wrap: Down from Quit returns to Resume.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);
    // Wrap the other way: Up from Resume lands on Quit.
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);
    CHECK(menu.state() == PauseMenuState::kOpen);  // navigation never closes
}

void testLeftRightIgnoredInMenu() {
    PauseMenu menu;
    selectQuit(menu);
    PauseMenuActions left{};
    left.left = true;
    PauseMenuActions right{};
    right.right = true;
    CHECK_EQ(menu.handle(left), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(right), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);  // unchanged in the main menu
}

void testResumeClosesAndReports() {
    PauseMenu menu;
    menu.open();
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kResume);
    CHECK(!menu.isOpen());
    CHECK(menu.state() == PauseMenuState::kClosed);
}

void testCancelClosesAndResumes() {
    PauseMenu menu;
    selectQuit(menu);
    // Back/Escape closes the menu and resumes (quickBackTransition MENU->CLOSED).
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kResume);
    CHECK(!menu.isOpen());
}

void testQuitOpensConfirmWithYesDefault() {
    PauseMenu menu;
    selectQuit(menu);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kQuitConfirm);
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmYes);
}

void testConfirmYesQuits() {
    PauseMenu menu;
    selectQuit(menu);
    menu.handle(confirmAction());  // -> confirm dialog (Yes selected)
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kQuit);
    CHECK(!menu.isOpen());
    CHECK(menu.state() == PauseMenuState::kClosed);
}

void testConfirmNoReturnsToMenuOnQuit() {
    PauseMenu menu;
    selectQuit(menu);
    menu.handle(confirmAction());  // -> confirm dialog
    menu.handle(downAction());  // toggle to No
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmNo);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);  // back on Quit
}

void testConfirmCancelReturnsToMenuOnQuit() {
    PauseMenu menu;
    selectQuit(menu);
    menu.handle(confirmAction());  // -> confirm dialog
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);
}

void testConfirmNavigationToggles() {
    PauseMenu menu;
    selectQuit(menu);
    menu.handle(confirmAction());  // -> confirm dialog, Yes selected
    // Up wraps No -> ... i.e. Yes -> No.
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmNo);
    // Down wraps back to Yes; left/right also toggle.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmYes);
    PauseMenuActions right{};
    right.right = true;
    CHECK_EQ(menu.handle(right), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmNo);
}

void testLabels() {
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kResumeItem)) == "Resume");
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kVideoOptionsItem)) == "Video Options");
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kControllerSettingsItem)) ==
          "Controller Settings");
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kQuitItem)) == "Quit");
    CHECK(PauseMenu::itemEnabled(PauseMenu::kResumeItem));
    CHECK(PauseMenu::itemEnabled(PauseMenu::kVideoOptionsItem));  // live submenu
    CHECK(PauseMenu::itemEnabled(PauseMenu::kControllerSettingsItem));  // live submenu now
    CHECK(PauseMenu::itemEnabled(PauseMenu::kQuitItem));
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmYes)) == "Yes");
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmNo)) == "No");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kScanlinesItem)) == "Scanlines");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kIntegerScalingItem)) ==
          "Integer Scaling");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kSharpFilterItem)) == "Sharp Filter");
    CHECK(std::string(PauseMenu::controllerOptionLabel(PauseMenu::kPhysicalItem)) ==
          "Physical Controller Settings");
    CHECK(std::string(PauseMenu::controllerOptionLabel(PauseMenu::kReturnItem)) == "Return");
}

void testCloseResetsFocus() {
    PauseMenu menu;
    selectQuit(menu);
    menu.close();
    CHECK(!menu.isOpen());
    menu.open();
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);  // fresh open focuses Resume
}

// ---------------------------------------------------------------------------
// Video Options submenu (mirrors Android's VideoOptionsDialog)

void testVideoOptionsOpensFocusedOnScanlines() {
    PauseMenu menu;
    menu.open();
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);  // -> Video Options
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kVideoOptions);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), PauseMenu::kScanlinesItem);  // first toggle focused
}

void testVideoOptionsNavigationWraps() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu, Scanlines selected
    // Down walks the three rows; Up wraps Sharp Filter -> Scanlines and
    // Down wraps Scanlines -> Sharp Filter.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kIntegerScalingItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kSharpFilterItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kScanlinesItem);  // wrap
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kSharpFilterItem);  // wrap the other way
    CHECK(menu.state() == PauseMenuState::kVideoOptions);  // navigation never leaves
}

void testVideoOptionsToggleOnConfirm() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu, Scanlines selected (default OFF)
    CHECK(!menu.scanlinesEnabled());
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kToggleScanlines);
    CHECK(menu.scanlinesEnabled());  // toggled ON
    CHECK_EQ(menu.selection(), PauseMenu::kScanlinesItem);  // confirm does not move
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kToggleScanlines);
    CHECK(!menu.scanlinesEnabled());  // toggled back OFF
}

void testVideoOptionsToggleOnLeftRight() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu, Scanlines selected
    CHECK_EQ(menu.handle(leftAction()), PauseMenuEffect::kToggleScanlines);
    CHECK(menu.scanlinesEnabled());
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kToggleScanlines);
    CHECK(!menu.scanlinesEnabled());
}

void testVideoOptionsTogglesAllThreeRows() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu, Scanlines selected
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kToggleScanlines);
    CHECK(menu.scanlinesEnabled());
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);  // -> Integer Scaling
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kToggleIntegerScaling);
    CHECK(menu.integerScalingEnabled());
    CHECK(!menu.sharpFilterEnabled());  // untouched so far
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);  // -> Sharp Filter
    CHECK_EQ(menu.handle(leftAction()), PauseMenuEffect::kToggleSharpFilter);
    CHECK(menu.sharpFilterEnabled());
    CHECK(menu.scanlinesEnabled());      // state persists across rows
    CHECK(menu.integerScalingEnabled());
}

void testVideoOptionsSeededToggles() {
    PauseMenu menu;
    menu.setVideoToggles(true, false, true);  // e.g. from the launch request
    CHECK(menu.scanlinesEnabled());
    CHECK(!menu.integerScalingEnabled());
    CHECK(menu.sharpFilterEnabled());
}

void testVideoOptionsReturnToMenu() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu
    menu.handle(rightAction());  // toggle something while in there
    CHECK(menu.state() == PauseMenuState::kVideoOptions);
    // Back/Escape returns to the MAIN menu (does not close it), focused on
    // Video Options.
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), PauseMenu::kVideoOptionsItem);
}

void testVideoOptionsCancelThenMenuCancelCloses() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu
    menu.handle(cancelAction());  // -> main menu (still open)
    CHECK(menu.isOpen());
    // A second Back closes the whole menu and resumes.
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kResume);
    CHECK(!menu.isOpen());
}

void testCloseFromVideoOptionsResetsFocus() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());   // -> Video Options
    menu.handle(confirmAction());  // -> submenu
    menu.close();
    CHECK(!menu.isOpen());
    menu.open();
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);  // fresh open focuses Resume
}

// ---------------------------------------------------------------------------
// Controller Settings submenu (mirrors Android's controller-settings subpage,
// minus its touch-only rows) and the read-only physical binding placeholder.

void testControllerSettingsOpensFocusedOnPhysical() {
    PauseMenu menu;
    selectControllerSettings(menu);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kControllerSettings);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), PauseMenu::kPhysicalItem);  // first row focused
}

void testControllerSettingsNavigationWraps() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu, Physical selected
    // Down walks to Return; Up wraps Return -> Physical and Down wraps
    // Physical -> Return.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kReturnItem);
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kPhysicalItem);  // wrap the other way
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kReturnItem);  // wrap
    CHECK(menu.state() == PauseMenuState::kControllerSettings);  // never leaves
}

void testControllerSettingsLeftRightIgnored() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu
    CHECK_EQ(menu.handle(leftAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kPhysicalItem);  // unchanged
}

void testControllerSettingsReturnItemToMenu() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu, Physical selected
    menu.handle(downAction());     // -> Return
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), PauseMenu::kControllerSettingsItem);  // back on the item
}

void testControllerSettingsCancelToMenuThenClose() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu
    // Back/Escape returns to the MAIN menu (does not close it), focused on
    // Controller Settings.
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), PauseMenu::kControllerSettingsItem);
    // A second Back closes the whole menu and resumes.
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kResume);
    CHECK(!menu.isOpen());
}

// Opens the menu and enters the editable binding list (Controller Settings ->
// Physical Controller Settings).
void selectBindingList(PauseMenu& menu) {
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu, Physical selected
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
}

void testBindingListOpensFromSubmenu() {
    PauseMenu menu;
    selectBindingList(menu);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), 0);  // first slot (A) focused
}

void testBindingListNavigationWrapsOverThirteenRows() {
    PauseMenu menu;
    selectBindingList(menu);
    // Down walks the 12 slot rows to Reset to Default.
    for (int i = 0; i < PauseMenu::kBindingSlotCount; ++i) {
        CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    }
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
    // Down wraps to the first slot; Up wraps back to Reset to Default.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), 0);
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);  // never leaves
}

void testBindingListConfirmEntersCapture() {
    PauseMenu menu;
    selectBindingList(menu);
    menu.handle(downAction());  // -> slot B (index 1)
    // Confirming a slot row reports kBeginCapture and enters capture mode
    // for that slot.
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kBeginCapture);
    CHECK(menu.state() == PauseMenuState::kBindingCapture);
    CHECK(menu.isCapturingBinding());
    CHECK_EQ(menu.selection(), 1);
}

void testBindingCaptureCancelExitsToList() {
    // While capturing, only cancel (keyboard Escape) is honored by the menu;
    // it returns to the slot list focused on the captured slot. Confirm and
    // navigation are ignored (gamepad input belongs to the coordinator).
    PauseMenu menu;
    selectBindingList(menu);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kBeginCapture);
    CHECK(menu.state() == PauseMenuState::kBindingCapture);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kBindingCapture);
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
    CHECK_EQ(menu.selection(), 0);  // back on the captured slot
    // exitCapture() is idempotent and no-op outside capture mode.
    menu.exitCapture();
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
}

void testBindingListResetDefaultRow() {
    PauseMenu menu;
    selectBindingList(menu);
    for (int i = 0; i < PauseMenu::kBindingSlotCount; ++i) {
        menu.handle(downAction());  // -> Reset to Default row
    }
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
    // Confirm reports kResetDefault and stays on the row.
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kResetDefault);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
}

void testBindingListCancelReturnsToSubmenu() {
    PauseMenu menu;
    selectBindingList(menu);
    // Back/Escape returns to the Controller Settings submenu (it never
    // closes the pause menu itself).
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kControllerSettings);
    CHECK_EQ(menu.selection(), PauseMenu::kPhysicalItem);
}

void testCloseFromControllerSettingsResetsFocus() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> submenu
    menu.close();
    CHECK(!menu.isOpen());
    menu.open();
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);  // fresh open focuses Resume
}

}  // namespace

int main() {
    testInitialAndClosedNoop();
    testOpenFocusesResume();
    testNavigationWalksAllItemsAndWraps();
    testLeftRightIgnoredInMenu();
    testResumeClosesAndReports();
    testCancelClosesAndResumes();
    testQuitOpensConfirmWithYesDefault();
    testConfirmYesQuits();
    testConfirmNoReturnsToMenuOnQuit();
    testConfirmCancelReturnsToMenuOnQuit();
    testConfirmNavigationToggles();
    testLabels();
    testCloseResetsFocus();
    testVideoOptionsOpensFocusedOnScanlines();
    testVideoOptionsNavigationWraps();
    testVideoOptionsToggleOnConfirm();
    testVideoOptionsToggleOnLeftRight();
    testVideoOptionsTogglesAllThreeRows();
    testVideoOptionsSeededToggles();
    testVideoOptionsReturnToMenu();
    testVideoOptionsCancelThenMenuCancelCloses();
    testCloseFromVideoOptionsResetsFocus();
    testControllerSettingsOpensFocusedOnPhysical();
    testControllerSettingsNavigationWraps();
    testControllerSettingsLeftRightIgnored();
    testControllerSettingsReturnItemToMenu();
    testControllerSettingsCancelToMenuThenClose();
    testBindingListOpensFromSubmenu();
    testBindingListNavigationWrapsOverThirteenRows();
    testBindingListConfirmEntersCapture();
    testBindingCaptureCancelExitsToList();
    testBindingListResetDefaultRow();
    testBindingListCancelReturnsToSubmenu();
    testCloseFromControllerSettingsResetsFocus();
    return rommtest::finish("test_pause_menu");
}

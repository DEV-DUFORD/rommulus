// test_pause_menu.cpp — host unit tests for the SDL-free pause overlay state
// machine (native/player/include/native/player/pause_menu.h). Covers open/
// close, D-pad navigation with wrapping, Resume, the "Quit game?" confirm
// dialog (Yes/No/cancel), the Video Options submenu (enter/focus, toggle
// rows via confirm or left/right, Return to menu), the Controller Settings
// submenu (enter/focus, navigation, the Return item and Back to menu, the
// editable physical binding list: core-specific slots + reset/clear, capture-mode
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

// Opens the menu and navigates to Quit.
void selectQuit(PauseMenu& menu) {
    menu.open();
    menu.handle(downAction());
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
    // Down walks every item and wraps.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kVideoOptionsItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kControllerSettingsItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kKeyboardSettingsItem);
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
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kKeyboardSettingsItem)) ==
          "Keyboard Control Settings");
    CHECK(std::string(PauseMenu::itemLabel(PauseMenu::kQuitItem)) == "Quit");
    CHECK(PauseMenu::itemEnabled(PauseMenu::kResumeItem));
    CHECK(PauseMenu::itemEnabled(PauseMenu::kVideoOptionsItem));  // live submenu
    CHECK(PauseMenu::itemEnabled(PauseMenu::kControllerSettingsItem));  // live submenu now
    CHECK(PauseMenu::itemEnabled(PauseMenu::kKeyboardSettingsItem));
    CHECK(PauseMenu::itemEnabled(PauseMenu::kQuitItem));
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmYes)) == "Yes");
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmNo)) == "No");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kScanlinesItem)) == "Scanlines");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kIntegerScalingItem)) ==
          "Integer Scaling");
    CHECK(std::string(PauseMenu::videoOptionLabel(PauseMenu::kSharpFilterItem)) == "Sharp Filter");
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
// Controller Settings opens port assignment before physical mappings.

void testControllerSettingsOpensFocusedOnFirstPort() {
    PauseMenu menu;
    selectControllerSettings(menu);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kControllerPorts);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), 0);
}

void testControllerSettingsCancelToMenuThenClose() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> controller ports
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

void testControllerPortAssignmentFlow() {
    PauseMenu menu;
    menu.setControllerPortCount(4);
    selectControllerSettings(menu);
    menu.handle(confirmAction());
    CHECK(menu.state() == PauseMenuState::kControllerPorts);
    CHECK_EQ(menu.controllerPortCount(), 4);
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kCycleController);
    CHECK_EQ(menu.controllerPortDirection(), 1);
    CHECK_EQ(menu.handle(leftAction()), PauseMenuEffect::kCycleController);
    CHECK_EQ(menu.controllerPortDirection(), -1);
    menu.handle(downAction());
    CHECK_EQ(menu.selection(), 1);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
    CHECK_EQ(menu.editingPort(), 1);
}

// Opens the menu and enters the active controller configuration.
void selectBindingList(PauseMenu& menu) {
    selectControllerSettings(menu);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kControllerPorts);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
}

void testBindingListOpensDirectly() {
    PauseMenu menu;
    selectBindingList(menu);
    CHECK(menu.isOpen());
    CHECK_EQ(menu.selection(), 0);  // first slot (A) focused
}

void testBindingListNavigationWrapsOverFourteenRows() {
    PauseMenu menu;
    selectBindingList(menu);
    // Down walks the binding rows to Reset to Default.
    for (int i = 0; i < PauseMenu::kBindingSlotCount; ++i) {
        CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    }
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
    // Clear Mappings follows Reset; then navigation wraps to the first slot.
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kClearMappingsItem);
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), 0);
    CHECK_EQ(menu.handle(upAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kClearMappingsItem);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);  // never leaves
}

void testBindingListViewportTracksSelection() {
    PauseMenu menu;
    selectBindingList(menu);
    CHECK_EQ(menu.bindingViewportStart(8), 0);
    for (int i = 0; i < 8; ++i) menu.handle(downAction());
    CHECK_EQ(menu.selection(), 8);
    CHECK_EQ(menu.bindingViewportStart(8), 1);
    for (int i = 8; i < PauseMenu::kBindingSlotCount; ++i) menu.handle(downAction());
    CHECK_EQ(menu.selection(), PauseMenu::kResetDefaultItem);
    CHECK_EQ(menu.bindingViewportStart(8), PauseMenu::kBindingSlotCount - 8);
}

void testN64BindingListUsesFourteenControls() {
    PauseMenu menu;
    menu.setBindingSlotCount(14);
    selectBindingList(menu);
    for (int i = 0; i < 10; ++i) menu.handle(downAction());
    CHECK_EQ(menu.selection(), 10);
    CHECK_EQ(romm::player::coreBindingSlotAt("mupen64plus_next", menu.selection()),
             romm::player::kSlotLeftTrigger);
    for (int i = 10; i < 14; ++i) menu.handle(downAction());
    CHECK_EQ(menu.selection(), menu.resetDefaultItem());
    CHECK_EQ(menu.bindingViewportStart(8), 6);
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

void testBindingListNavigatesSecondaryColumn() {
    PauseMenu menu;
    selectBindingList(menu);
    CHECK_EQ(menu.bindingColumn(), 0);
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.bindingColumn(), 1);
    CHECK_EQ(menu.handle(leftAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.bindingColumn(), 0);
    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kBeginCapture);
    CHECK_EQ(menu.bindingColumn(), 1);
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

void testBindingListClearMappingsAction() {
    PauseMenu menu;
    selectBindingList(menu);
    for (int i = 0; i <= PauseMenu::kBindingSlotCount; ++i) {
        menu.handle(downAction());
    }
    CHECK_EQ(menu.selection(), PauseMenu::kClearMappingsItem);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kClearMappings);
    CHECK(menu.state() == PauseMenuState::kPhysicalBindings);
}

void testBindingListCancelReturnsToMenu() {
    PauseMenu menu;
    selectBindingList(menu);
    // Back/Escape returns to controller ports focused on Edit Mappings.
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kControllerPorts);
    CHECK_EQ(menu.selection(), menu.editingPort());
}

void testCloseFromControllerSettingsResetsFocus() {
    PauseMenu menu;
    selectControllerSettings(menu);
    menu.handle(confirmAction());  // -> active configuration
    menu.close();
    CHECK(!menu.isOpen());
    menu.open();
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);  // fresh open focuses Resume
}

void testKeyboardSettingsFlow() {
    PauseMenu menu;
    menu.setKeyboardRowCount(18);
    menu.open();
    menu.handle(downAction());
    menu.handle(downAction());
    menu.handle(downAction());
    CHECK_EQ(menu.selection(), PauseMenu::kKeyboardSettingsItem);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kKeyboardBindings);
    CHECK_EQ(menu.selection(), 0);
    CHECK_EQ(menu.keyboardRowCount(), 18);

    CHECK_EQ(menu.handle(rightAction()), PauseMenuEffect::kNone);
    CHECK_EQ(menu.bindingColumn(), 1);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kBeginKeyboardCapture);
    CHECK(menu.state() == PauseMenuState::kKeyboardCapture);
    CHECK(menu.isCapturingKeyboard());
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kKeyboardCapture);
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kKeyboardBindings);

    for (int i = 0; i < 18; ++i) menu.handle(downAction());
    CHECK_EQ(menu.selection(), menu.resetDefaultItem());
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kResetKeyboardDefault);
    menu.handle(downAction());
    CHECK_EQ(menu.selection(), menu.clearMappingsItem());
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kClearKeyboardMappings);
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kKeyboardSettingsItem);
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
    testControllerSettingsOpensFocusedOnFirstPort();
    testControllerSettingsCancelToMenuThenClose();
    testControllerPortAssignmentFlow();
    testBindingListOpensDirectly();
    testBindingListNavigationWrapsOverFourteenRows();
    testBindingListViewportTracksSelection();
    testN64BindingListUsesFourteenControls();
    testBindingListConfirmEntersCapture();
    testBindingListNavigatesSecondaryColumn();
    testBindingCaptureCancelExitsToList();
    testBindingListResetDefaultRow();
    testBindingListClearMappingsAction();
    testBindingListCancelReturnsToMenu();
    testCloseFromControllerSettingsResetsFocus();
    testKeyboardSettingsFlow();
    return rommtest::finish("test_pause_menu");
}

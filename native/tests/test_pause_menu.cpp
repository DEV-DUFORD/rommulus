// test_pause_menu.cpp — host unit tests for the SDL-free pause overlay state
// machine (native/player/include/native/player/pause_menu.h). Covers open/
// close, D-pad navigation with disabled-item skipping and wrapping, Resume,
// the "Quit game?" confirm dialog (Yes/No/cancel), and effect reporting.

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

void testNavigationSkipsDisabledAndWraps() {
    PauseMenu menu;
    menu.open();
    // Resume(0) is enabled; Video Options(1) and Controller Settings(2) are
    // disabled placeholders, so Down jumps straight to Quit(3).
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
    menu.open();
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);
    PauseMenuActions left{};
    left.left = true;
    PauseMenuActions right{};
    right.right = true;
    CHECK_EQ(menu.handle(left), PauseMenuEffect::kNone);
    CHECK_EQ(menu.handle(right), PauseMenuEffect::kNone);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);  // unchanged
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
    menu.open();
    menu.handle(downAction());  // Quit selected
    // Back/Escape closes the menu and resumes (quickBackTransition MENU->CLOSED).
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kResume);
    CHECK(!menu.isOpen());
}

void testQuitOpensConfirmWithYesDefault() {
    PauseMenu menu;
    menu.open();
    CHECK_EQ(menu.handle(downAction()), PauseMenuEffect::kNone);  // -> Quit
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kQuitConfirm);
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmYes);
}

void testConfirmYesQuits() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());  // -> Quit
    menu.handle(confirmAction());  // -> confirm dialog (Yes selected)
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kQuit);
    CHECK(!menu.isOpen());
    CHECK(menu.state() == PauseMenuState::kClosed);
}

void testConfirmNoReturnsToMenuOnQuit() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());  // -> Quit
    menu.handle(confirmAction());  // -> confirm dialog
    menu.handle(downAction());  // toggle to No
    CHECK_EQ(menu.selection(), PauseMenu::kConfirmNo);
    CHECK_EQ(menu.handle(confirmAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);  // back on Quit
}

void testConfirmCancelReturnsToMenuOnQuit() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());  // -> Quit
    menu.handle(confirmAction());  // -> confirm dialog
    CHECK_EQ(menu.handle(cancelAction()), PauseMenuEffect::kNone);
    CHECK(menu.state() == PauseMenuState::kOpen);
    CHECK_EQ(menu.selection(), PauseMenu::kQuitItem);
}

void testConfirmNavigationToggles() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());  // -> Quit
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
    CHECK(!PauseMenu::itemEnabled(PauseMenu::kVideoOptionsItem));
    CHECK(!PauseMenu::itemEnabled(PauseMenu::kControllerSettingsItem));
    CHECK(PauseMenu::itemEnabled(PauseMenu::kQuitItem));
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmYes)) == "Yes");
    CHECK(std::string(PauseMenu::confirmOptionLabel(PauseMenu::kConfirmNo)) == "No");
}

void testCloseResetsFocus() {
    PauseMenu menu;
    menu.open();
    menu.handle(downAction());  // -> Quit
    menu.close();
    CHECK(!menu.isOpen());
    menu.open();
    CHECK_EQ(menu.selection(), PauseMenu::kResumeItem);  // fresh open focuses Resume
}

}  // namespace

int main() {
    testInitialAndClosedNoop();
    testOpenFocusesResume();
    testNavigationSkipsDisabledAndWraps();
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
    return rommtest::finish("test_pause_menu");
}

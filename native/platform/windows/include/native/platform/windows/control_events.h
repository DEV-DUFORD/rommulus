// control_events.h — console-control-event classification for the player's
// process-control contract, extracted as a pure, fakeable helper (Phase 2,
// plans/WINDOWS_IMPL.md section 5.1).
//
// This header is the "fakeable seam" of windows_process_control.cpp: the
// decision "does this console control event request termination?" lives here
// as pure standard C++ over a numeric event type, so native/tests can pin
// the contract on EVERY host (POSIX included) without Windows headers or OS
// calls. The event-type constants are defined here rather than including
// <windows.h>; windows_process_control.cpp static_asserts each one against
// the matching windows.h value, so drift is a compile error, not a silent
// misclassification.
#pragma once

#include <cstdint>

namespace romm::win32 {

// Console control event types (values fixed by the Win32 contract; see
// CTRL_*_EVENT in <windows.h>).
enum ConsoleControlEvent : std::uint32_t {
    kCtrlCEvent = 0,       // CTRL_C_EVENT: Ctrl+C at the console.
    kCtrlBreakEvent = 1,   // CTRL_BREAK_EVENT: Ctrl+Break / generated break.
    kCtrlCloseEvent = 2,   // CTRL_CLOSE_EVENT: console window closed.
    kCtrlLogoffEvent = 5,  // CTRL_LOGOFF_EVENT: user session is logging off.
    kCtrlShutdownEvent = 6 // CTRL_SHUTDOWN_EVENT: system is shutting down.
};

// True exactly for the event types that request a clean player termination —
// the SIGTERM/SIGINT equivalents (CTRL_C/CTRL_BREAK) plus the console-close
// and system-shutdown notifications. The handler sets the termination flag
// for these and returns TRUE (handled: the main loop polls the flag once per
// frame and exits cleanly, still writing a result file).
//
// CTRL_LOGOFF_EVENT is deliberately NOT a termination event: the handler
// returns FALSE for it so the system performs its default action. Unknown
// values classify as "not ours" (fail toward the default action, never
// toward swallowing an event we do not understand).
constexpr bool isTerminationControlEvent(std::uint32_t eventType) {
    switch (eventType) {
        case kCtrlCEvent:
        case kCtrlBreakEvent:
        case kCtrlCloseEvent:
        case kCtrlShutdownEvent:
            return true;
        default:
            return false;
    }
}

}  // namespace romm::win32

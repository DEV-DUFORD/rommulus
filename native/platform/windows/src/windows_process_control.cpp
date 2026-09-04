// windows_process_control.cpp — Win32 implementation of the player's process
// control contract (native/player/include/native/player/process_control.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md section 5.1): the console-control +
// TerminateProcess counterpart of posix_process_control.cpp, with the same
// observable contract:
//   - installTerminationHandlers() registers ONE SetConsoleCtrlHandler that
//     covers CTRL_C/CTRL_BREAK/CLOSE/SHUTDOWN. The handler does nothing but
//     flip an atomic flag (async-signal-safe by construction — control
//     handlers run on a system-created thread, and the store is the only
//     thing it touches); terminationRequested() then reports true and the
//     main loop exits cleanly once per frame, still writing a result file.
//     CTRL_LOGOFF_EVENT is not handled (the handler returns FALSE so the
//     system takes its default action). For CLOSE/SHUTDOWN the system allows
//     a grace period before force-killing; the per-frame poll exits well
//     inside it.
//   - armTeardownWatchdog(timeoutSeconds) / disarmTeardownWatchdog() guard
//     teardown with an INDEPENDENT watchdog thread that calls
//     TerminateProcess(GetCurrentProcess(), 0) after the requested timeout —
//     the _exit(0) equivalent: no unwinding, exit code 0. It fires even when
//     the main thread deadlocks inside core teardown, because
//     TerminateProcess kills every thread of the exact process without their
//     cooperation. The result-before-arm order is enforced by main.cpp (the
//     result file is committed before armTeardownWatchdog() is called), so
//     firing can never lose a result. Disarm wakes and JOINS the watchdog
//     thread, so at most one live watchdog exists at any time; repeated
//     arm/disarm sequences are safe, and disarm before the timeout cancels
//     cleanly. A non-positive timeout matches POSIX alarm(0): it disarms.
//   - reexec() is the UNIX-only Steam Deck legacy-player fallback: it
//     reports ENOSYS on Windows (see below) instead of faking a replacement
//     with CreateProcessW.
#include "native/player/process_control.h"

#include <native/platform/windows/control_events.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <atomic>
#include <cerrno>
#include <cstdint>
#include <cstdio>
#include <mutex>
#include <string>

// Bind the pure seam's constants to the real windows.h values: any drift is
// a compile error, not a silent misclassification.
static_assert(romm::win32::kCtrlCEvent == static_cast<std::uint32_t>(CTRL_C_EVENT),
              "control_events.h drifted from windows.h CTRL_C_EVENT");
static_assert(romm::win32::kCtrlBreakEvent == static_cast<std::uint32_t>(CTRL_BREAK_EVENT),
              "control_events.h drifted from windows.h CTRL_BREAK_EVENT");
static_assert(romm::win32::kCtrlCloseEvent == static_cast<std::uint32_t>(CTRL_CLOSE_EVENT),
              "control_events.h drifted from windows.h CTRL_CLOSE_EVENT");
static_assert(romm::win32::kCtrlLogoffEvent == static_cast<std::uint32_t>(CTRL_LOGOFF_EVENT),
              "control_events.h drifted from windows.h CTRL_LOGOFF_EVENT");
static_assert(romm::win32::kCtrlShutdownEvent == static_cast<std::uint32_t>(CTRL_SHUTDOWN_EVENT),
              "control_events.h drifted from windows.h CTRL_SHUTDOWN_EVENT");

namespace romm::player {
namespace {

// The console-control handler flips this flag; the main loop checks it once
// per frame. The handler does nothing else (async-signal-safe by
// construction, exactly as the POSIX signal handler).
std::atomic<bool> g_termination_requested{false};

// installTerminationHandlers() is "safe to call exactly once at startup"
// (contract): make a second call an explicit no-op instead of stacking a
// duplicate handler.
std::atomic<bool> g_handlers_installed{false};

BOOL WINAPI consoleControlHandler(DWORD eventType) {
    if (romm::win32::isTerminationControlEvent(static_cast<std::uint32_t>(eventType))) {
        g_termination_requested.store(true, std::memory_order_relaxed);
        return TRUE;  // handled: the main loop exits cleanly this frame
    }
    return FALSE;  // not ours (e.g. CTRL_LOGOFF_EVENT): default system action
}

// --- Teardown watchdog -----------------------------------------------------
// State shared with the watchdog thread. The mutex is taken ONLY by
// arm/disarm (the user thread); the watchdog thread never locks it — it is
// joined while the lock is held, so a lock in the thread body would deadlock.
// The event and thread handles are deliberately never closed: they live in
// process-lifetime storage the thread reads (stable addresses), and closing
// them would race a still-running thread. TerminateProcess bypasses static
// destruction entirely, and on a normal exit the leaked handles cost nothing
// (same deliberate-leak discipline as windows_session_lock.cpp).
struct WatchdogState {
    std::mutex mutex;
    HANDLE event = nullptr;  // auto-reset, created once, never closed
    HANDLE thread = nullptr; // current watchdog thread, or null when disarmed
};

WatchdogState& watchdog() {
    static WatchdogState state;
    return state;
}

// The arming timeout, read by the thread at start. Written under the mutex
// BEFORE CreateThread and only after any previous thread was joined, so a
// live thread can never observe another arm's value.
std::atomic<int> g_watchdog_timeout_seconds{0};

DWORD WINAPI watchdogThreadMain(LPVOID) {
    const int timeoutSeconds = g_watchdog_timeout_seconds.load(std::memory_order_acquire);
    // 1000 * timeoutSeconds overflows DWORD above ~24.8 days; clamp just
    // below the INFINITE sentinel (0xFFFFFFFF means "wait forever") — a
    // watchdog that never fires would defeat its purpose, so the wait stays
    // bounded for every positive input.
    std::uint64_t milliseconds = static_cast<std::uint64_t>(timeoutSeconds) * 1000u;
    if (milliseconds > 0xFFFFFFFEull) milliseconds = 0xFFFFFFFEull;
    const DWORD result = WaitForSingleObject(watchdog().event, static_cast<DWORD>(milliseconds));
    if (result == WAIT_OBJECT_0) {
        return 0;  // disarmTeardownWatchdog() woke us before the timeout
    }
    // WAIT_TIMEOUT — or a spurious WAIT_FAILED: fire. The watchdog exists to
    // GUARANTEE termination, so an untrustworthy wait fails toward firing;
    // main.cpp committed the result file before arming, so nothing is lost.
    ::TerminateProcess(::GetCurrentProcess(), 0);  // _exit(0) equivalent
    return 0;  // unreachable
}

// Wakes and joins the current watchdog thread (if any). Caller holds
// state.mutex. Joining before re-arming guarantees at most one live watchdog
// at a time — a stale, unjoined thread could otherwise fire after a later
// disarm. Safe to call when no thread is running.
void stopWatchdogThreadLocked(WatchdogState& state) {
    if (state.thread == nullptr) return;
    SetEvent(state.event);  // wake the sleeper so it exits promptly
    WaitForSingleObject(state.thread, INFINITE);
    CloseHandle(state.thread);
    state.thread = nullptr;
}

}  // namespace

void installTerminationHandlers() {
    bool expected = false;
    if (!g_handlers_installed.compare_exchange_strong(expected, true)) return;
    if (!SetConsoleCtrlHandler(consoleControlHandler, TRUE)) {
        // Fail open with a warning (mirrors the POSIX sigaction-failure
        // posture): the desktop supervisor can still terminate this process
        // externally; we just lose in-process clean-shutdown signaling.
        std::fprintf(stderr, "warning: SetConsoleCtrlHandler failed (%lu); "
                             "in-process termination signaling unavailable\n",
                     static_cast<unsigned long>(GetLastError()));
    }
}

bool terminationRequested() { return g_termination_requested.load(std::memory_order_relaxed); }

void armTeardownWatchdog(int timeoutSeconds) {
    WatchdogState& state = watchdog();
    std::lock_guard<std::mutex> lock(state.mutex);
    if (timeoutSeconds <= 0) {
        // POSIX alarm(0) semantics: a non-positive timeout cancels, it does
        // not arm an immediate kill.
        stopWatchdogThreadLocked(state);
        return;
    }
    stopWatchdogThreadLocked(state);  // repeated arm is safe: one live thread max
    if (state.event == nullptr) {
        state.event = CreateEventW(nullptr, FALSE /*auto-reset*/, FALSE, nullptr);
        if (state.event == nullptr) {
            std::fprintf(stderr, "warning: could not create watchdog event (%lu); "
                                 "teardown is unbounded\n",
                         static_cast<unsigned long>(GetLastError()));
            return;
        }
    }
    ResetEvent(state.event);
    g_watchdog_timeout_seconds.store(timeoutSeconds, std::memory_order_release);
    state.thread = CreateThread(nullptr, 0, watchdogThreadMain, nullptr, 0, nullptr);
    if (state.thread == nullptr) {
        std::fprintf(stderr, "warning: could not start watchdog thread (%lu); "
                             "teardown is unbounded\n",
                     static_cast<unsigned long>(GetLastError()));
        return;
    }
}

void disarmTeardownWatchdog() {
    WatchdogState& state = watchdog();
    std::lock_guard<std::mutex> lock(state.mutex);
    stopWatchdogThreadLocked(state);
}

bool reexec(const std::string& program, int argc, char* argv[]) {
    (void)program;
    (void)argc;
    (void)argv;
    // The Steam Deck legacy-player fallback is a UNIX-only path and cannot
    // succeed here: rommulus_player_deck is not built on WIN32 (the player
    // CMakeLists gates it with if(NOT WIN32)), so the call site's existence
    // check fails before reexec is ever reached. A CreateProcessW "re-exec"
    // would also be wrong in kind, not just in degree: it spawns a SECOND
    // process that the parent must then exit behind — requiring argv/env
    // reconstruction and forfeiting execv's single-image replacement
    // semantics. Report unsupported with ENOSYS so the caller's existing
    // strerror(errno) error path works unchanged.
    errno = ENOSYS;
    return false;
}

}  // namespace romm::player

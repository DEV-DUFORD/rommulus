// posix_process_control.cpp — POSIX implementation of the player's process
// control contract (native/player/include/native/player/process_control.h).
//
// Phase 2 step 1 (plans/WINDOWS_IMPL.md section 5.1): moved VERBATIM from
// native/player/src/main.cpp — SIGTERM/SIGINT flip an atomic flag (the
// handler does nothing else, async-signal-safe by construction), and the
// teardown watchdog is a process-level SIGALRM whose handler calls _exit(0)
// directly so it still fires when teardown deadlocks on a runtime lock.
// The result-before-teardown ordering is enforced by main.cpp (the result
// file is committed before armTeardownWatchdog() is called). A Win32
// implementation will use console-control handling plus an independent
// TerminateProcess watchdog behind the same contract.
#include "native/player/process_control.h"

#include <unistd.h>

#include <atomic>
#include <csignal>
#include <cstring>

namespace romm::player {
namespace {

// SIGTERM/SIGINT flip this flag; the main loop checks it once per frame.
// The handler does nothing else (async-signal-safe by construction).
std::atomic<bool> g_signal_flag{false};

void signalHandler(int) { g_signal_flag.store(true, std::memory_order_relaxed); }

// Teardown can block inside an uncooperative core or its dependencies. The
// result is committed before teardown starts, so this process-level timeout
// can terminate safely without leaving the desktop supervisor waiting.
void teardownTimeoutHandler(int) { ::_exit(0); }

}  // namespace

void installTerminationHandlers() {
    struct sigaction sa {};
    sa.sa_handler = signalHandler;
    sigemptyset(&sa.sa_mask);
    sa.sa_flags = 0;
    ::sigaction(SIGTERM, &sa, nullptr);
    ::sigaction(SIGINT, &sa, nullptr);
}

bool terminationRequested() { return g_signal_flag.load(std::memory_order_relaxed); }

void armTeardownWatchdog(int timeoutSeconds) {
    struct sigaction teardownTimeout {};
    teardownTimeout.sa_handler = teardownTimeoutHandler;
    sigemptyset(&teardownTimeout.sa_mask);
    ::sigaction(SIGALRM, &teardownTimeout, nullptr);
    ::alarm(timeoutSeconds);
}

void disarmTeardownWatchdog() { ::alarm(0); }

bool reexec(const std::string& program, int argc, char* argv[]) {
    // execv replaces the process image on success; a return means failure.
    // Nothing between here and the return touches errno, so the caller can
    // still format strerror(errno).
    ::execv(program.c_str(), argv);
    (void)argc;
    return false;
}

}  // namespace romm::player

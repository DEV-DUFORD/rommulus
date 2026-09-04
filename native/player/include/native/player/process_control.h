// process_control.h — termination signaling, the teardown watchdog, and
// process re-execution (Phase 2 step 1, plans/WINDOWS_IMPL.md section 5.1).
//
// POSIX behavior preserved verbatim: SIGTERM/SIGINT flip an atomic flag the
// main loop polls once per frame (the handler does nothing else —
// async-signal-safe by construction); teardown is guarded by a process-
// level alarm that _exit(0)s after five seconds so an uncooperative core
// cannot keep the desktop supervisor waiting. Win32 will provide console-
// control handling plus an independent TerminateProcess watchdog in a
// later step with the same observable contract. No SDL, no Android, no JNI.
#pragma once

#include <string>

namespace romm::player {

// Installs the SIGTERM/SIGINT handlers that set the termination flag.
// Called after SDL_Init (POSIX); safe to call exactly once at startup.
void installTerminationHandlers();

// True once SIGTERM/SIGINT has been received; the main loop checks it once
// per frame and exits cleanly, still writing a result file.
bool terminationRequested();

// Arms the process-level teardown watchdog: after `timeoutSeconds` the
// handler calls _exit(0) directly — deliberately bypassing unwinding so it
// still fires when teardown deadlocks on a runtime lock inside an
// uncooperative core. The RESULT FILE MUST BE COMMITTED BEFORE THIS IS
// ARMED (main.cpp writes it first); the watchdog exists precisely because
// everything after this point may hang. disarmTeardownWatchdog() cancels a
// pending timeout once teardown has completed.
void armTeardownWatchdog(int timeoutSeconds);

void disarmTeardownWatchdog();

// Replaces the running process image with `program`, passing through argv
// unchanged (POSIX execv; used by the Steam Deck legacy-player fallback).
// Returns false only on failure, in which case errno is preserved so the
// caller can format strerror(errno). Never returns on success.
bool reexec(const std::string& program, int argc, char* argv[]);

}  // namespace romm::player

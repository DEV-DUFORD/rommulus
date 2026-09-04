// session_lock.h — per-session exclusive lock held for the process lifetime
// (LINUX_X64.md section 12.4; Phase 2 step 1, plans/WINDOWS_IMPL.md
// sections 5.1/5.4).
//
// Two players must never run the same session concurrently: the desktop
// supervisor relies on this to reject a duplicate launch. The lock is an
// OS-level advisory lock on <stateRoot>/<sessionId>.lock (POSIX: open +
// flock; Win32 will use CreateFileW + LockFileEx in a later step), taken
// non-blocking and HELD UNTIL PROCESS EXIT — the kernel releases it when
// the process's file handle closes at termination, so a crash never leaves
// a stale lock behind. The .lock FILE itself is likewise left in stateRoot
// on purpose: unlinking it would race with another process trying to
// create/lock the same name (it could steal our session id or deadlock
// behind a stale inode). Do NOT unlink it.
#pragma once

#include <string>

namespace romm::player {

// Tries to take the exclusive non-blocking lock for `sessionId` under
// `stateRoot`. Returns true when this process must NOT run the session:
// either another live player already holds the lock, or the composed lock
// path cannot be proven to stay inside the canonical state root (fail
// closed — see the POSIX implementation for the containment checks). On a
// successful acquisition the lock is retained for the process lifetime;
// there is deliberately no release function.
//
// The sessionId must already have passed format validation
// ([A-Za-z0-9_-]{1,64}) before this is called — the lock path is composed
// from it. An empty sessionId returns false (nothing to lock).
bool sessionActive(const std::string& stateRoot, const std::string& sessionId);

}  // namespace romm::player

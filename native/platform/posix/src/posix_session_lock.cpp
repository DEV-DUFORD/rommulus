// posix_session_lock.cpp — POSIX implementation of the player's session
// lock contract (native/player/include/native/player/session_lock.h).
//
// Phase 2 step 1 (plans/WINDOWS_IMPL.md section 5.1): moved VERBATIM from
// the sessionActive lambda in native/player/src/main.cpp — same
// open(O_CREAT|O_RDWR|O_NOFOLLOW, 0600) + flock(LOCK_EX|LOCK_NB) sequence,
// same fail-closed containment checks against the canonical state root,
// same stderr warnings, and the same lock lifetime: the fd is held in a
// process-lifetime static and intentionally never closed (the kernel
// releases the flock when the fd closes at termination). A Win32
// CreateFileW + LockFileEx implementation will follow the same contract.
#include "native/player/session_lock.h"

#include <fcntl.h>
#include <sys/file.h>
#include <unistd.h>

#include <cerrno>
#include <cstdio>
#include <cstring>
#include <string>

namespace romm::player {
namespace {

// The fd backing <stateRoot>/<sessionId>.lock, held open for the process
// lifetime so the flock survives until exit (the kernel releases the lock
// when the fd is closed at termination). Intentionally never closed.
// The .lock FILE itself is likewise left in stateRoot on purpose: the lock
// is kernel-held via this open fd, so unlinking the file would race with
// another process trying to create/flock the same name (it could steal our
// session id or deadlock behind a stale inode). Do NOT unlink it.
int g_sessionLockFd = -1;

}  // namespace

bool sessionActive(const std::string& stateRoot, const std::string& sessionId) {
    if (sessionId.empty()) return false;

    // Canonicalize stateRoot ONCE (resolves symlinks/relative components) so
    // every containment check compares against the same real absolute path.
    // If the directory does not exist yet, fall back to the raw value with
    // any trailing slash stripped — open() below then fails harmlessly on
    // its own.
    std::string canonicalStateRoot = stateRoot;
    char resolvedRoot[4096];
    if (::realpath(stateRoot.c_str(), resolvedRoot) != nullptr) {
        canonicalStateRoot.assign(resolvedRoot);
    } else if (!canonicalStateRoot.empty() && canonicalStateRoot.back() == '/') {
        canonicalStateRoot.pop_back();
    }

    // Session lock: try to take an exclusive non-blocking flock on
    // <stateRoot>/<sessionId>.lock. If another process already holds it,
    // report the session as active (validation then rejects the request).
    // Defense in depth: O_NOFOLLOW prevents symlink-based escape, and the
    // composed lock path is verified to stay inside stateRoot BEFORE it is
    // opened; a session whose lock path escapes is rejected (reported as
    // active so the validator refuses to launch).
    const std::string lockPath = canonicalStateRoot + "/" + sessionId + ".lock";
    // Containment check BEFORE opening (defense in depth on top of the
    // sessionId format validation done separately in validateRequest):
    // the composed path must stay inside the canonical state root.
    const std::string rootPrefix = canonicalStateRoot + "/";
    char resolvedLock[4096];
    if (::realpath(lockPath.c_str(), resolvedLock) != nullptr) {
        // Path exists: it must resolve back inside the state root (this
        // catches pre-planted symlinks and any ".." that lands outside).
        if (std::string(resolvedLock).rfind(rootPrefix, 0) != 0) {
            std::fprintf(stderr, "warning: session lock for %s escapes the state root; rejecting\n",
                         sessionId.c_str());
            return true;
        }
    } else if (errno == ENOENT) {
        // Not present yet: it must be a single path component directly
        // under the root (no '/', no '.'/'..').
        const std::string name = sessionId + ".lock";
        if (name.find('/') != std::string::npos || name == "." || name == "..") {
            std::fprintf(stderr, "warning: session lock name for %s escapes the state root; rejecting\n",
                         sessionId.c_str());
            return true;
        }
    } else {
        // ELOOP/EACCES/... — cannot verify containment; do not open a
        // path we cannot prove is inside the state root.
        std::fprintf(stderr, "warning: cannot resolve session lock for %s (%s); rejecting\n",
                     sessionId.c_str(), std::strerror(errno));
        return true;
    }
    const int fd = ::open(lockPath.c_str(), O_CREAT | O_RDWR | O_NOFOLLOW, 0600);
    if (fd < 0) {
        std::fprintf(stderr, "warning: could not create session lock %s: %s\n",
                     lockPath.c_str(), std::strerror(errno));
        return false;
    }
    if (::flock(fd, LOCK_EX | LOCK_NB) != 0) {
        if (errno == EWOULDBLOCK || errno == EAGAIN) {
            ::close(fd);
            return true;  // a live player already owns this session
        }
        std::fprintf(stderr, "warning: flock failed on %s: %s\n", lockPath.c_str(),
                     std::strerror(errno));
        ::close(fd);
        return false;
    }
    g_sessionLockFd = fd;  // held until process exit (deliberate leak)
    return false;
}

}  // namespace romm::player

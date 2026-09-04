// path_security.h — OS-dependent filesystem primitives backing request
// validation (LINUX_X64.md section 12.4; Phase 2 step 1,
// plans/WINDOWS_IMPL.md section 5.1).
//
// The validation POLICY in validation.cpp is platform-neutral: it decides
// which paths must stay inside which trusted root, rejects symlinks and
// escapes, checks the core allowlist, and so on. Only the filesystem
// operations below are OS-specific and are implemented by a platform-
// selected source (native/platform/posix/src/posix_path_security.cpp on
// POSIX hosts; a Win32 handle/final-path implementation in a later step).
// No SDL, no Android, no JNI.
#pragma once

#include <optional>
#include <string>

namespace romm::player {

// Canonicalizes `path` to an absolute path with symlinks, `.`, and `..`
// resolved. Relative paths resolve against the current working directory.
// For paths that do not exist yet (e.g. a candidate save being created),
// the deepest existing ancestor is canonicalized and the remaining
// components are re-appended, so the result is still fully canonical.
// Returns std::nullopt (and sets *error) on failure.
std::optional<std::string> canonicalPath(const std::string& path,
                                         std::string* error = nullptr);

// True when `path` exists and is a symlink (lstat on POSIX, so the link
// itself is inspected, not its target).
bool isSymlink(const std::string& path);

// Security classification of a launch request file (POSIX: lstat + UID
// ownership + mode bits; Win32 will classify reparse points/ACLs instead).
// Checked in this exact order by validateRequestFile(): existence/readability,
// symlink rejection, regular-file requirement, current-user ownership, and
// the world-writable bit.
enum class RequestFileStatus {
    Ok,
    MissingOrUnreadable,
    Symlink,
    NotRegularFile,
    NotOwnedByUser,
    WorldWritable,
};

RequestFileStatus requestFileSecurity(const std::string& path);

// Size of `path` in bytes, or nullopt when the file does not exist or
// cannot be stat'ed. Used by the restore-on-launch decision (a missing or
// empty save means "first launch — start with fresh SRAM").
std::optional<long long> fileSize(const std::string& path);

}  // namespace romm::player

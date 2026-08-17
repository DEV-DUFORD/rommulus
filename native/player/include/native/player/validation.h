// validation.h — request file validation against trusted roots
// (LINUX_X64.md section 12.4).
//
// Before loading a core, the player must:
//   1. reject unknown protocol versions;
//   2. verify request file ownership and non-world-writable mode;
//   3. canonicalize every path;
//   4. require core path under the installed trusted core root;
//   5. require content under approved cache/data roots;
//   6. require system/save/candidate/result paths under approved
//      data/state roots;
//   7. reject symlinks and path escapes;
//   8. verify core ID/build revision against installed metadata;
//   9. verify content hash when requested;
//  10. reject an already-active session lock.
//
// Platform-neutral (POSIX file operations; the player targets Linux and
// the host tests run on macOS). No SDL, no Android, no JNI.
#pragma once

#include <functional>
#include <map>
#include <optional>
#include <string>

#include "native/player/protocol.h"

namespace romm::player {

// Trusted roots and policy the player validates requests against. The
// desktop supervisor (Wave 2) fills this in from its own configuration;
// tests supply sample roots under a temp directory.
struct TrustedRoots {
    // Installed trusted core root; corePath must canonicalize inside it.
    std::string coreRoot;
    // Approved content cache root; contentPath must canonicalize inside it.
    std::string cacheRoot;
    // Approved data root; systemDir and savePath must canonicalize inside
    // it.
    std::string dataRoot;
    // Approved state root; candidateSavePath and resultPath must
    // canonicalize inside it.
    std::string stateRoot;
    // Installed core metadata: coreId -> pinned build revision. A request
    // is valid only if its (coreId, coreBuildRevision) pair is present.
    std::map<std::string, std::string> allowedCores;
    // Expected content hash. When the request's contentHash is non-empty
    // it must equal this value exactly.
    std::optional<std::string> expectedContentHash;
    // Session lock check: returns true when the session is already active
    // (e.g. its lock file is held by a live player). When set and it
    // returns true, validation fails.
    std::function<bool(const std::string& sessionId)> sessionActive;
};

// Full player validation policy. Roots today; further policy (path
// length caps, etc.) can be added here without changing call sites.
struct PlayerConfig {
    TrustedRoots roots;
};

struct ValidationOutcome {
    bool ok = false;
    std::string error;  // human-readable reason; empty when ok
};

// Full section 12.4 validation of a request file:
//   - the file must exist, be a regular file (not a symlink), be owned by
//     the current user, and not be world-writable (POSIX stat);
//   - its contents must parse as a strict v1 request;
//   - validateRequest() must accept the parsed struct.
ValidationOutcome validateRequestFile(const std::string& requestPath,
                                      const PlayerConfig& config);

// Validates an already-parsed request: canonicalizes every path (resolving
// symlinks, `..`, and `.`), requires each path to stay inside its trusted
// root, rejects paths that are (or resolve to) symlinks, checks the core
// allowlist, the content hash, and the session lock.
ValidationOutcome validateRequest(const PlayerRequest& request,
                                  const PlayerConfig& config);

// Canonicalizes `path` to an absolute path with symlinks, `.`, and `..`
// resolved. Relative paths resolve against the current working directory.
// For paths that do not exist yet (e.g. a candidate save being created),
// the deepest existing ancestor is canonicalized and the remaining
// components are re-appended, so the result is still fully canonical.
// Returns std::nullopt (and sets *error) on failure.
std::optional<std::string> canonicalPath(const std::string& path,
                                         std::string* error = nullptr);

}  // namespace romm::player

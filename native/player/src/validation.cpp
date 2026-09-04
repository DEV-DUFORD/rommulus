// validation.cpp — request file validation against trusted roots
// (LINUX_X64.md section 12.4).
//
// Platform-neutral policy: a request path is accepted only if its canonical
// form stays inside the trusted root for that field, and any path that
// exists as a symlink is rejected outright. The OS-dependent primitives
// (canonicalization, symlink detection, request-file ownership/mode checks)
// are implemented by a platform-selected source behind path_security.h
// (POSIX today; Win32 in a later Phase 2 step — plans/WINDOWS_IMPL.md
// section 5.1); this file carries no OS headers of its own.
#include "native/player/validation.h"

#include <fstream>
#include <filesystem>
#include <optional>
#include <sstream>

namespace romm::player {
namespace {

ValidationOutcome fail(const std::string& message) {
    return ValidationOutcome{false, message};
}

// True when `canonicalPath` is `canonicalRoot` or lies beneath it. Both
// arguments must already be canonical (absolute, symlinks resolved, no
// trailing slash except the root "/").
bool isWithinRoot(const std::string& canonicalPath,
                  const std::string& canonicalRoot) {
    if (canonicalPath == canonicalRoot) return true;
    if (canonicalRoot == "/") return canonicalPath.size() > 1;
    return canonicalPath.rfind(canonicalRoot + "/", 0) == 0;
}

}  // namespace

// sessionId must be a safe identifier before any lock-path is built from it.
// Alphanumeric + dash/underscore only, non-empty, bounded length.
bool isValidSessionId(const std::string& id) {
    if (id.empty() || id.size() > 64) return false;
    for (char c : id) {
        if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
              (c >= '0' && c <= '9') || c == '_' || c == '-'))
            return false;
    }
    return true;
}

ValidationOutcome validateRequest(const PlayerRequest& request,
                                  const PlayerConfig& config) {
    // sessionId validation is FIRST — the lock path is built from it, so a
    // malformed sessionId must never reach sessionActive or any path logic.
    if (!isValidSessionId(request.sessionId))
        return fail("invalid sessionId: must be 1-64 chars of [A-Za-z0-9_-]");

    if (request.protocolVersion != kProtocolVersion)
        return fail("unsupported protocolVersion: " +
                    std::to_string(request.protocolVersion));

    // Canonicalize one request path and require it to stay inside `root`.
    auto checkPath = [&](const char* label, const std::string& raw,
                         const std::string& root) -> ValidationOutcome {
        if (raw.empty()) return fail(std::string(label) + ": empty path");
        if (isSymlink(raw))
            return fail(std::string(label) + ": symlink rejected");
        std::string err;
        auto canonical = canonicalPath(raw, &err);
        if (!canonical)
            return fail(std::string(label) + ": " + err);
        if (isSymlink(*canonical))
            return fail(std::string(label) + ": resolves to a symlink");
        auto canonicalRoot = canonicalPath(root, &err);
        if (!canonicalRoot)
            return fail(std::string(label) +
                        ": cannot canonicalize trusted root: " + err);
        if (!isWithinRoot(*canonical, *canonicalRoot))
            return fail(std::string(label) +
                        ": escapes trusted root " + *canonicalRoot);
        return ValidationOutcome{true, ""};
    };

    auto pathCheck = checkPath("corePath", request.corePath,
                               config.roots.coreRoot);
    if (!pathCheck.ok) return pathCheck;
    // An empty contentPath is legal: no-content cores (e.g. test_core)
    // load with retro_load_game(nullptr), and the engine already handles
    // that. Whether a given core accepts no game is the core's decision,
    // so validation admits an empty contentPath unconditionally — the
    // "under cacheRoot" containment check only applies to real paths.
    if (!request.contentPath.empty()) {
        pathCheck = checkPath("contentPath", request.contentPath,
                              config.roots.cacheRoot);
        if (!pathCheck.ok) return pathCheck;
    }
    pathCheck = checkPath("systemDir", request.systemDir,
                          config.roots.dataRoot);
    if (!pathCheck.ok) return pathCheck;
    pathCheck = checkPath("savePath", request.savePath,
                          config.roots.dataRoot);
    if (!pathCheck.ok) return pathCheck;
    pathCheck = checkPath("candidateSavePath", request.candidateSavePath,
                          config.roots.stateRoot);
    if (!pathCheck.ok) return pathCheck;
    pathCheck = checkPath("resultPath", request.resultPath,
                          config.roots.stateRoot);
    if (!pathCheck.ok) return pathCheck;

    auto core = config.roots.allowedCores.find(request.coreId);
    if (core == config.roots.allowedCores.end())
        return fail("coreId not in installed metadata: " + request.coreId);
    if (core->second != request.coreBuildRevision)
        return fail("coreBuildRevision mismatch for coreId: " +
                    request.coreId);

    if (!request.contentHash.empty()) {
        if (!config.roots.expectedContentHash)
            return fail("contentHash provided but no expected hash configured");
        if (*config.roots.expectedContentHash != request.contentHash)
            return fail("contentHash mismatch");
    }

    if (config.roots.sessionActive &&
        config.roots.sessionActive(request.sessionId))
        return fail("session already active: " + request.sessionId);

    return ValidationOutcome{true, ""};
}

ValidationOutcome validateRequestFile(const std::string& requestPath,
                                      const PlayerConfig& config) {
    // The OS-specific classification (lstat + UID ownership + mode bits on
    // POSIX; reparse-point/ACL policy on Win32) lives behind path_security.h;
    // the check order and error messages are the section 12.4 contract.
    switch (requestFileSecurity(requestPath)) {
        case RequestFileStatus::MissingOrUnreadable:
            return fail("request file missing or unreadable: " + requestPath);
        case RequestFileStatus::Symlink:
            return fail("request file must not be a symlink");
        case RequestFileStatus::NotRegularFile:
            return fail("request file must be a regular file");
        case RequestFileStatus::NotOwnedByUser:
            return fail("request file is not owned by the current user");
        case RequestFileStatus::WorldWritable:
            return fail("request file is world-writable");
        case RequestFileStatus::Ok:
            break;
    }

    std::ifstream in(std::filesystem::u8path(requestPath), std::ios::binary);
    if (!in) return fail("cannot open request file: " + requestPath);
    std::ostringstream buffer;
    buffer << in.rdbuf();

    std::string err;
    auto request = parseRequest(buffer.str(), &err);
    if (!request) return fail("invalid request JSON: " + err);
    return validateRequest(*request, config);
}

}  // namespace romm::player

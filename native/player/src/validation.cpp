// validation.cpp — request file validation against trusted roots
// (LINUX_X64.md section 12.4).
//
// POSIX file operations (the player targets Linux; host tests run on
// macOS). Path canonicalization resolves symlinks, `.`, and `..` to an
// absolute canonical path; a request path is accepted only if its
// canonical form stays inside the trusted root for that field, and any
// path that exists as a symlink is rejected outright.
#include "native/player/validation.h"

#include <sys/param.h>
#include <sys/stat.h>
#include <unistd.h>

#include <fstream>
#include <optional>
#include <sstream>
#include <vector>

namespace romm::player {
namespace {

template <typename T>
std::optional<T> reject(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
    return std::nullopt;
}

ValidationOutcome fail(const std::string& message) {
    return ValidationOutcome{false, message};
}

std::vector<std::string> splitPath(const std::string& path) {
    std::vector<std::string> parts;
    std::string current;
    for (char c : path) {
        if (c == '/') {
            if (!current.empty()) {
                parts.push_back(current);
                current.clear();
            }
        } else {
            current.push_back(c);
        }
    }
    if (!current.empty()) parts.push_back(current);
    return parts;
}

std::string joinPath(const std::vector<std::string>& parts) {
    std::string out;
    for (const auto& part : parts) {
        out.push_back('/');
        out += part;
    }
    return out;
}

// True when `path` exists and is a symlink (lstat, so the link itself is
// inspected, not its target).
bool isSymlink(const std::string& path) {
    struct stat st {};
    if (lstat(path.c_str(), &st) != 0) return false;
    return S_ISLNK(st.st_mode) != 0;
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

std::optional<std::string> canonicalPath(const std::string& path,
                                         std::string* error) {
    if (path.empty()) return reject<std::string>(error, "empty path");

    std::string absolute;
    if (path[0] == '/') {
        absolute = path;
    } else {
        char cwd[PATH_MAX];
        if (getcwd(cwd, sizeof(cwd)) == nullptr)
            return reject<std::string>(error, "cannot resolve working directory");
        absolute = std::string(cwd) + "/" + path;
    }

    // Lexical normalization of "." and "..". A leading ".." cannot escape
    // the filesystem root: it is dropped once the component stack is empty.
    std::vector<std::string> parts;
    for (const auto& component : splitPath(absolute)) {
        if (component == ".") continue;
        if (component == "..") {
            if (!parts.empty()) parts.pop_back();
            continue;
        }
        parts.push_back(component);
    }

    // Find the deepest existing prefix. A child cannot exist without its
    // parent, so the first stat failure ends the walk.
    std::string existing = "/";
    size_t existingDepth = 0;
    for (size_t i = 1; i <= parts.size(); ++i) {
        std::string prefix =
            joinPath(std::vector<std::string>(parts.begin(), parts.begin() + i));
        struct stat st {};
        if (stat(prefix.c_str(), &st) != 0) break;
        existing = prefix;
        existingDepth = i;
    }

    // Canonicalize the existing prefix (this resolves every symlink in it);
    // re-append the not-yet-existing tail, whose components cannot be
    // symlinks because they do not exist.
    char resolved[PATH_MAX];
    if (realpath(existing.c_str(), resolved) == nullptr)
        return reject<std::string>(error,
                                   "cannot canonicalize: " + existing);
    std::string canonical = resolved;
    for (size_t i = existingDepth; i < parts.size(); ++i) {
        if (canonical != "/") canonical.push_back('/');
        canonical += parts[i];
    }
    return canonical;
}

ValidationOutcome validateRequest(const PlayerRequest& request,
                                  const PlayerConfig& config) {
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
    pathCheck = checkPath("contentPath", request.contentPath,
                          config.roots.cacheRoot);
    if (!pathCheck.ok) return pathCheck;
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
    struct stat st {};
    if (lstat(requestPath.c_str(), &st) != 0)
        return fail("request file missing or unreadable: " + requestPath);
    if (S_ISLNK(st.st_mode) != 0)
        return fail("request file must not be a symlink");
    if (S_ISREG(st.st_mode) == 0)
        return fail("request file must be a regular file");
    if (st.st_uid != geteuid() && st.st_uid != getuid())
        return fail("request file is not owned by the current user");
    if ((st.st_mode & S_IWOTH) != 0)
        return fail("request file is world-writable");

    std::ifstream in(requestPath, std::ios::binary);
    if (!in) return fail("cannot open request file: " + requestPath);
    std::ostringstream buffer;
    buffer << in.rdbuf();

    std::string err;
    auto request = parseRequest(buffer.str(), &err);
    if (!request) return fail("invalid request JSON: " + err);
    return validateRequest(*request, config);
}

}  // namespace romm::player

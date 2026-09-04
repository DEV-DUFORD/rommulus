// posix_path_security.cpp — POSIX implementation of the player's path
// security contract (native/player/include/native/player/path_security.h).
//
// Phase 2 step 1 (plans/WINDOWS_IMPL.md section 5.1): canonicalPath() and
// isSymlink() moved here VERBATIM from native/player/src/validation.cpp
// (same realpath/stat/getcwd primitives, same error strings), and the
// request-file ownership/mode checks plus the restore-on-launch size query
// were extracted from validation.cpp/main.cpp into requestFileSecurity()/
// fileSize() with identical lstat/stat semantics. The validation policy
// itself stays platform-neutral in validation.cpp.
#include "native/player/path_security.h"

#include <sys/param.h>
#include <sys/stat.h>
#include <unistd.h>

#include <optional>
#include <vector>

namespace romm::player {
namespace {

template <typename T>
std::optional<T> reject(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
    return std::nullopt;
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

// True when `path` exists and is a symlink (lstat, so the link itself is
// inspected, not its target).
bool isSymlink(const std::string& path) {
    struct stat st {};
    if (lstat(path.c_str(), &st) != 0) return false;
    return S_ISLNK(st.st_mode) != 0;
}

RequestFileStatus requestFileSecurity(const std::string& path) {
    struct stat st {};
    if (lstat(path.c_str(), &st) != 0)
        return RequestFileStatus::MissingOrUnreadable;
    if (S_ISLNK(st.st_mode) != 0)
        return RequestFileStatus::Symlink;
    if (S_ISREG(st.st_mode) == 0)
        return RequestFileStatus::NotRegularFile;
    if (st.st_uid != geteuid() && st.st_uid != getuid())
        return RequestFileStatus::NotOwnedByUser;
    if ((st.st_mode & S_IWOTH) != 0)
        return RequestFileStatus::WorldWritable;
    return RequestFileStatus::Ok;
}

std::optional<long long> fileSize(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return std::nullopt;
    return static_cast<long long>(st.st_size);
}

}  // namespace romm::player

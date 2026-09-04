// posix_platform_paths.cpp — POSIX implementation of the player's platform
// path contract (native/player/include/native/player/platform_paths.h).
//
// Phase 2 step 1 (plans/WINDOWS_IMPL.md section 5.1): moved VERBATIM from
// native/player/src/main.cpp — homeDirectory() (HOME env, then getpwuid),
// the XDG base-directory default roots, and the /proc/self/exe executable
// lookup used for packaged assets and the Steam Deck player re-exec. A
// Win32 implementation will provide GetModuleFileNameW plus
// LocalAppData-derived fallbacks behind the same contract.
#include "native/player/platform_paths.h"

#include <cstdlib>
#include <filesystem>
#include <pwd.h>
#include <unistd.h>

namespace romm::player {
namespace {

std::string envVar(const char* name) {
    const char* value = std::getenv(name);
    return value != nullptr ? value : "";
}

// XDG base-directory lookup with the standard per-variable default under
// $HOME.
std::string xdgHome(const char* name, const std::string& home, const char* relativeDefault) {
    const std::string value = envVar(name);
    if (!value.empty()) return value;
    return home + relativeDefault;
}

}  // namespace

std::string homeDirectory() {
    const std::string home = envVar("HOME");
    if (!home.empty()) return home;
    const struct passwd* entry = getpwuid(getuid());
    if (entry != nullptr && entry->pw_dir != nullptr) return entry->pw_dir;
    return ".";
}

DefaultTrustedRoots defaultTrustedRoots() {
    // Every root falls back to an XDG-based default under ~/.local/share,
    // ~/.cache, and ~/.local/state so a manually launched player still has
    // a sane (if narrow) trust policy.
    const std::string home = homeDirectory();
    const std::string dataHome = xdgHome("XDG_DATA_HOME", home, "/.local/share");
    const std::string cacheHome = xdgHome("XDG_CACHE_HOME", home, "/.cache");
    const std::string stateHome = xdgHome("XDG_STATE_HOME", home, "/.local/state");

    DefaultTrustedRoots roots;
    roots.coreRoot = dataHome + "/rommulus/cores";
    roots.cacheRoot = cacheHome + "/rommulus";
    roots.dataRoot = dataHome + "/rommulus";
    roots.stateRoot = stateHome + "/rommulus";
    return roots;
}

std::optional<std::filesystem::path> executablePath() {
    std::error_code pathError;
    const std::filesystem::path executable =
        std::filesystem::read_symlink("/proc/self/exe", pathError);
    if (pathError) return std::nullopt;
    return executable;
}

std::string utf8EnvironmentVariable(const char* name) {
    // POSIX process environments are byte strings; the ROMM_PLAYER_* contract
    // is UTF-8, so getenv() IS the strict-UTF-8 reader here (the Win32
    // implementation needs GetEnvironmentVariableW instead — see
    // platform_paths.h).
    if (name == nullptr) return std::string();
    const char* value = std::getenv(name);
    return value != nullptr ? value : "";
}

}  // namespace romm::player

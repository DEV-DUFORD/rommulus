// platform_paths.h — OS-resolved paths the player needs at startup: the
// user's home directory, the default trusted roots, and the player's own
// executable location (Phase 2 step 1, plans/WINDOWS_IMPL.md section 5.1).
//
// The ROMM_PLAYER_* environment overrides and the allowed-cores/content-
// hash parsing in main.cpp are platform-neutral; only these lookups are
// OS-specific (POSIX: $HOME/passwd, XDG base directories, /proc/self/exe;
// Win32 will use GetModuleFileNameW and LocalAppData-derived fallbacks in
// a later step). No SDL, no Android, no JNI.
#pragma once

#include <filesystem>
#include <optional>
#include <string>

namespace romm::player {

// The user's home directory: $HOME when set, otherwise the passwd entry
// for the current uid, otherwise "." (POSIX behavior preserved verbatim).
std::string homeDirectory();

// The default trusted roots used when the ROMM_PLAYER_* environment
// contract does not override them. On POSIX these are the XDG base
// directories with the standard per-variable defaults under $HOME:
//   coreRoot  $XDG_DATA_HOME/rommulus/cores   (default ~/.local/share)
//   cacheRoot $XDG_CACHE_HOME/rommulus        (default ~/.cache)
//   dataRoot  $XDG_DATA_HOME/rommulus         (default ~/.local/share)
//   stateRoot $XDG_STATE_HOME/rommulus        (default ~/.local/state)
struct DefaultTrustedRoots {
    std::string coreRoot;
    std::string cacheRoot;
    std::string dataRoot;
    std::string stateRoot;
};

DefaultTrustedRoots defaultTrustedRoots();

// The canonical path of the running player executable (POSIX: the
// /proc/self/exe symlink), or nullopt when it cannot be resolved. Used to
// locate packaged assets beside the binary (share/rommulus) and the Steam
// Deck legacy player sibling.
std::optional<std::filesystem::path> executablePath();

// Reads an environment variable as strict UTF-8, or "" when unset/empty.
// POSIX: std::getenv (the process environment is already bytes; the
// ROMM_PLAYER_* contract is UTF-8). Win32: GetEnvironmentVariableW +
// UTF-16→UTF-8 — the ANSI getenv() decodes through the active code page and
// would corrupt non-ASCII trusted roots (e.g. a "тест état" state root on a
// CP-1252 runner). main.cpp's ROMM_PLAYER_*_ROOT / _ALLOWED_CORES parsing
// goes through this so every platform reads the same UTF-8 contract.
std::string utf8EnvironmentVariable(const char* name);

}  // namespace romm::player

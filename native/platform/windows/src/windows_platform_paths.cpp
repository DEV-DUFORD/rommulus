// windows_platform_paths.cpp — Win32 implementation of the player's platform
// path contract (native/player/include/native/player/platform_paths.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md sections 3.4/5.1): the counterpart of
// posix_platform_paths.cpp.
//   - executablePath(): GetModuleFileNameW(nullptr, ...) with a dynamically
//     grown buffer — no MAX_PATH cap, so long-path installs resolve;
//   - homeDirectory(): %USERPROFILE% (the Windows home), falling back to "."
//     exactly as the POSIX implementation falls back when $HOME/passwd are
//     unavailable;
//   - defaultTrustedRoots(): the LocalAppData known folder (SHGetKnownFolder-
//     Path, FOLDERID_LocalAppData) with a safe environment fallback
//     (%LOCALAPPDATA%, then %USERPROFILE%\AppData\Local), laid out per the
//     plan's user-data table:
//         coreRoot  <localappdata>\RomMulus\data\cores
//         cacheRoot <localappdata>\RomMulus\cache
//         dataRoot  <localappdata>\RomMulus\data
//         stateRoot <localappdata>\RomMulus\state
//     The ROMM_PLAYER_* environment overrides are applied by the shared
//     main.cpp on top of these defaults, exactly as on POSIX — this source
//     only supplies the platform defaults.
// No ANSI APIs anywhere: every OS call is a *W function and every string
// crosses the boundary strictly (utf16.h). Results use forward slashes so
// they compose with the neutral '/'-based validation policy.
#include "native/player/platform_paths.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// SHGetKnownFolderPath: <shell32.h> in the Windows SDK, <shlobj.h> in
// MinGW-w64 (this project's Windows toolchain — it ships no shell32.h).
// CoTaskMemFree (the only legal release of the returned path) comes from
// <combaseapi.h> via <windows.h> and is exported by ole32.
#ifdef __MINGW32__
#include <shlobj.h>
#else
#include <shell32.h>
#endif

#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

namespace romm::player {
namespace {

// FOLDERID_LocalAppData — authoritative value from knownfolders.h (Windows
// SDK; identical in MinGW-w64 14.0.0, this project's Windows toolchain):
//   DEFINE_KNOWN_FOLDER (FOLDERID_LocalAppData, 0xf1b32785, 0x6fba, 0x4fcf,
//       0x9d, 0x55, 0x7b, 0x8e, 0x7f, 0x15, 0x70, 0x91)
// i.e. {F1B32785-6FBA-4FCF-9D55-7B8E7F157091}. The previously hard-coded
// {1FBA2490-5FAD-46C8-950A-6E0D73742D30} is not a known-folder ID in any
// SDK header, so SHGetKnownFolderPath would fail (E_INVALIDARG) on every
// call and the resolver would always fall through to the environment
// fallback. The Kotlin counterpart (JnaWindowsKnownFolderResolver) carries
// the same GUID — keep the two in lockstep.
const GUID kFolderIdLocalAppData =
    {0xF1B32785, 0x6FBA, 0x4FCF, {0x9D, 0x55, 0x7B, 0x8E, 0x7F, 0x15, 0x70, 0x91}};

// Reads a wide environment variable with a dynamically grown buffer.
// Returns an empty string when the variable is unset or empty. (The ANSI
// getenv() is deliberately avoided: it decodes through the active code page.)
std::u16string envVarW(const wchar_t* name) {
    const DWORD needed = GetEnvironmentVariableW(name, nullptr, 0);
    if (needed == 0) return std::u16string();
    std::vector<wchar_t> buffer(needed);
    const DWORD written = GetEnvironmentVariableW(name, buffer.data(), needed);
    if (written == 0 || written > needed - 1) return std::u16string();
    return std::u16string(buffer.begin(), buffer.begin() + written);
}

// Converts a wide path to the canonical slash form used across the neutral
// policy. The input comes from OS APIs (known folders / environment), so no
// security rejections apply — only separator unification and trailing-slash
// removal.
std::string wideToCanonicalUtf8(const std::u16string& wide) {
    std::u16string unified;
    unified.reserve(wide.size());
    for (char16_t c : wide) unified.push_back(c == u'\\' ? u'/' : c);
    while (unified.size() > 1 && unified.back() == u'/') unified.pop_back();
    const auto utf8 = romm::win32::utf16ToUtf8(unified);
    return utf8.value_or(std::string());
}

// The LocalAppData root: the known folder when the shell can provide it,
// otherwise %LOCALAPPDATA%, otherwise %USERPROFILE%\AppData\Local. Empty
// string when none is available (callers fail closed on an empty base).
std::u16string localAppData() {
    PWSTR known = nullptr;
    if (SUCCEEDED(SHGetKnownFolderPath(kFolderIdLocalAppData, 0, nullptr, &known)) &&
        known != nullptr) {
        // wchar_t* is not char16_t*: copy across the boundary explicitly.
        std::u16string result;
        for (const wchar_t* p = known; *p != L'\0'; ++p) {
            result.push_back(static_cast<char16_t>(*p));
        }
        CoTaskMemFree(known);
        return result;
    }
    const std::u16string fromEnv = envVarW(L"LOCALAPPDATA");
    if (!fromEnv.empty()) return fromEnv;
    const std::u16string profile = envVarW(L"USERPROFILE");
    if (!profile.empty()) return profile + u"\\AppData\\Local";
    return std::u16string();
}

}  // namespace

std::string homeDirectory() {
    // %USERPROFILE% is the Windows home directory; "." mirrors the POSIX
    // last-resort fallback so a manually launched player still has a sane
    // (if narrow) trust policy.
    const std::u16string profile = envVarW(L"USERPROFILE");
    if (!profile.empty()) return wideToCanonicalUtf8(profile);
    return ".";
}

DefaultTrustedRoots defaultTrustedRoots() {
    DefaultTrustedRoots roots;
    const std::u16string baseWide = localAppData();
    if (baseWide.empty()) return roots;  // all empty: the shared main fails closed
    const std::string base = wideToCanonicalUtf8(baseWide);
    roots.coreRoot = base + "/RomMulus/data/cores";
    roots.cacheRoot = base + "/RomMulus/cache";
    roots.dataRoot = base + "/RomMulus/data";
    roots.stateRoot = base + "/RomMulus/state";
    return roots;
}

std::optional<std::filesystem::path> executablePath() {
    // GetModuleFileNameW(nullptr, ...) names the running image. The buffer
    // grows dynamically (long paths are first-class; MAX_PATH is not a cap).
    std::vector<wchar_t> buffer(1024);
    DWORD written = 0;
    for (;;) {
        written = GetModuleFileNameW(nullptr, buffer.data(),
                                     static_cast<DWORD>(buffer.size()));
        if (written == 0) return std::nullopt;
        if (written < buffer.size()) break;
        buffer.resize(buffer.size() * 2);
    }
    const auto utf8 = romm::win32::utf16ToUtf8(
        std::u16string(buffer.begin(), buffer.begin() + written));
    if (!utf8) return std::nullopt;
    return std::filesystem::path(*utf8);
}

std::string utf8EnvironmentVariable(const char* name) {
    // Strict UTF-8 by construction: the ANSI getenv() decodes through the
    // active code page (CP-1252 on an English windows-2022 runner), which
    // would silently replace non-ASCII trusted-root characters with '?'.
    // GetEnvironmentVariableW + the tree's strict UTF-16→UTF-8 boundary
    // conversion (utf16.h) keeps "тест état"-style roots intact. Variable
    // names are ASCII by contract, so the char→wchar name copy is lossless.
    if (name == nullptr || *name == '\0') return std::string();
    std::wstring wideName;
    for (const char* p = name; *p != '\0'; ++p) {
        wideName.push_back(static_cast<wchar_t>(*p));
    }
    const std::u16string value = envVarW(wideName.c_str());
    if (value.empty()) return std::string();
    const auto utf8 = romm::win32::utf16ToUtf8(value);
    return utf8.value_or(std::string());
}

}  // namespace romm::player

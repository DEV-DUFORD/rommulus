// windows_session_lock.cpp — Win32 implementation of the player's session
// lock contract (native/player/include/native/player/session_lock.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md sections 5.1/5.4): the LockFileEx
// counterpart of posix_session_lock.cpp, with the same fail-closed
// containment discipline and the same return semantics:
//   - true  = "this process must NOT run the session": another LIVE player
//     holds the byte-range lock (ERROR_LOCK_VIOLATION), OR the composed lock
//     path cannot be proven to stay inside the canonical state root (planted
//     reparse point, escape, unresolvable root) — fail closed, exactly as
//     the POSIX implementation reports an unverifiable lock path;
//   - false = "proceed": either the lock was acquired and is now RETAINED FOR
//     THE PROCESS LIFETIME (the handle and its OVERLAPPED live in a static
//     and are deliberately never closed — the kernel releases the byte-range
//     lock when the process's handle closes at termination, so a crash never
//     leaves a stale lock), or the lock file simply could not be created
//     (mirroring POSIX open() failure: warn, do not claim the session).
// The .lock FILE itself is likewise left in stateRoot on purpose: unlinking
// it would race with another process trying to create/lock the same name.
// Do NOT unlink it.
//
// Safe sharing/reparse behavior of the CreateFileW calls:
//   - the lock probe opens with FILE_FLAG_OPEN_REPARSE_POINT (inspect, never
//     follow) so a pre-planted symlink/junction named "<id>.lock" is seen as
//     what it is and rejected, not silently followed outside the root;
//   - the locking open uses CREATE_ALWAYS with FILE_SHARE_READ|WRITE|DELETE
//     so a second player CAN open the same file (and then lose the
//     non-blocking LockFileEx race — that loss IS the "session active"
//     signal); and
//   - after the lock is acquired, the FINAL PATH of the locked handle itself
//     is re-verified inside the canonical root: this closes the probe->open
//     TOCTOU window (a symlink swapped in between the two opens would be
//     detected through the handle that actually holds the lock).
#include "native/player/session_lock.h"

#include <native/platform/windows/final_path.h>
#include <native/platform/windows/path_rules.h>
#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <cstdio>
#include <string>
#include <vector>

namespace romm::player {
namespace {

// The handle backing <stateRoot>/<sessionId>.lock plus the OVERLAPPED used
// for its LockFileEx call, both held for the process lifetime so the byte-
// range lock survives until exit (the kernel releases it when the handle
// closes at termination). Intentionally never closed; there is deliberately
// no release function. The contract is one session per process, exactly as
// on POSIX (main.cpp calls sessionActive once at startup).
struct SessionLockState {
    HANDLE hFile = INVALID_HANDLE_VALUE;
    OVERLAPPED overlapped {};
};
SessionLockState& lockState() {
    static SessionLockState state;
    return state;
}

std::string winErrorText(DWORD code) {
    wchar_t* message = nullptr;
    const DWORD len = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
            FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, code, 0, reinterpret_cast<wchar_t*>(&message), 0, nullptr);
    if (len == 0 || message == nullptr) {
        return "Win32 error " + std::to_string(static_cast<unsigned long>(code));
    }
    const auto wide = romm::win32::utf16ToUtf8(std::u16string(message, message + len));
    LocalFree(message);
    if (!wide) {
        return "Win32 error " + std::to_string(static_cast<unsigned long>(code));
    }
    const size_t end = wide->find_last_not_of(" \t\r\n");
    return end == std::string::npos ? std::string() : wide->substr(0, end + 1);
}

// GetCurrentDirectoryW's FIRST call returns the buffer size INCLUDING the
// terminating NUL; its SECOND call returns the length EXCLUDING it. The path
// must therefore be taken from the second call's return value — reading the
// buffer's end would embed the NUL terminator in the root, and a second-call
// length that does not fit the buffer (truncation) or a zero (error) must
// fail closed (the caller then rejects the session).
std::optional<std::u16string> currentDirectory() {
    const DWORD needed = GetCurrentDirectoryW(0, nullptr);
    if (needed == 0) return std::nullopt;
    std::vector<wchar_t> buffer(needed);
    const DWORD written = GetCurrentDirectoryW(needed, buffer.data());
    if (written == 0 || written > needed - 1) return std::nullopt;
    return std::u16string(buffer.begin(), buffer.begin() + written);
}

// The final path of an open handle in canonical slash form (see
// windows_path_security.cpp for the same helper's rationale): the shared
// buffer-growth contract (final_path.h) drives the API — a truncated call
// only grows the buffer, it never reads past it — then the pure
// unification.
std::optional<std::u16string> finalPathOf(HANDLE handle) {
    const auto raw = romm::win32::fetchFinalPath(
        [handle](wchar_t* buffer, std::uint32_t capacity) -> std::uint32_t {
            return GetFinalPathNameByHandleW(handle, buffer, capacity, VOLUME_NAME_DOS);
        });
    if (!raw) return std::nullopt;
    // fetchFinalPath returns exactly the stored characters, NUL EXCLUDED
    // (written - 1 would silently drop the path's last character,
    // corrupting the containment comparison).
    std::u16string unified;
    unified.reserve(raw->size());
    size_t i = 0;
    if (raw->size() >= 8 && raw->compare(0, 8, u"\\\\?\\UNC\\") == 0) {
        i = 8;
        unified += u"//";
    } else if (raw->size() >= 4 && raw->compare(0, 4, u"\\\\?\\") == 0) {
        i = 4;
    }
    for (; i < raw->size(); ++i) unified.push_back((*raw)[i] == u'\\' ? u'/' : (*raw)[i]);
    return unified;
}

// Canonicalizes `stateRoot` to the final path of the real directory it
// names, so every containment check compares against one resolved absolute
// path. nullopt when the root does not exist or cannot be resolved (the
// caller then falls back to lexical normalization only, mirroring POSIX).
std::optional<std::u16string> canonicalStateRoot(const std::u16string& stateRoot) {
    // char16_t* is not LPCWSTR: convert at the OS boundary (see utf16.h).
    const std::wstring osPath = romm::win32::toWideString(romm::win32::toOsForm(stateRoot));
    const HANDLE handle = CreateFileW(osPath.c_str(), FILE_READ_ATTRIBUTES,
                                      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                      nullptr, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS,
                                      nullptr);
    if (handle == INVALID_HANDLE_VALUE) return std::nullopt;
    auto finalPath = finalPathOf(handle);
    CloseHandle(handle);
    return finalPath;
}

}  // namespace

bool sessionActive(const std::string& stateRoot, const std::string& sessionId) {
    if (sessionId.empty()) return false;

    // --- Validate the trusted state root. ---------------------------------
    // Strict UTF-8 -> UTF-16 at the boundary; invalid input cannot be proven
    // to stay inside anything, so it is rejected as active (fail closed).
    const auto wideRoot = romm::win32::utf8ToUtf16(stateRoot);
    if (!wideRoot) {
        std::fprintf(stderr, "warning: session state root has invalid UTF-8; rejecting\n");
        return true;
    }

    // Make absolute against the working directory when needed (POSIX's
    // realpath did this implicitly).
    std::u16string absoluteRoot = *wideRoot;
    if (!romm::win32::isAbsoluteWin32(absoluteRoot)) {
        const auto cwd = currentDirectory();
        if (!cwd) {
            std::fprintf(stderr, "warning: cannot resolve working directory for session lock; rejecting\n");
            return true;
        }
        absoluteRoot = *cwd + u"\\" + *wideRoot;
    }

    // Lexical normalization (also rejects device paths/ADS/reserved names in
    // the root itself — a state root that is not an ordinary directory path
    // cannot be trusted).
    auto lexicallyNormalized = romm::win32::normalizeWin32Path(absoluteRoot);
    if (!lexicallyNormalized) {
        std::fprintf(stderr, "warning: session state root %s is not a valid directory path; rejecting\n",
                     stateRoot.c_str());
        return true;
    }

    // Canonicalize ONCE through the real directory's final path (resolves
    // symlinks/junctions in the root). When the directory does not exist yet
    // fall back to the lexically normalized value — CreateFileW below then
    // fails harmlessly on its own, exactly like the POSIX implementation.
    const auto canonicalRoot = canonicalStateRoot(*lexicallyNormalized);
    const std::u16string& root = canonicalRoot ? *canonicalRoot : *lexicallyNormalized;

    // --- Defense in depth on the sessionId itself. -------------------------
    // validateRequest() already enforces [A-Za-z0-9_-]{1,64}; re-check here
    // so a future call site cannot build a lock path with separators or ADS
    // colons from an unvalidated id.
    for (char c : sessionId) {
        const bool safe = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
                          (c >= '0' && c <= '9') || c == '_' || c == '-';
        if (!safe) {
            std::fprintf(stderr, "warning: session lock name for %s escapes the state root; rejecting\n",
                         sessionId.c_str());
            return true;
        }
    }

    // Compose the lock path and require it to stay inside the canonical root.
    const std::u16string wideSessionId = *romm::win32::utf8ToUtf16(sessionId);  // always valid here
    const std::u16string lockPath = root + u"/" + wideSessionId + u".lock";
    if (!romm::win32::isWithinRootCaseInsensitive(lockPath, root)) {
        std::fprintf(stderr, "warning: session lock for %s escapes the state root; rejecting\n",
                     sessionId.c_str());
        return true;
    }

    // The Win32 wchar_t form of the lock path (char16_t* is not LPCWSTR).
    const std::wstring lockOsForm = romm::win32::toWideString(romm::win32::toOsForm(lockPath));

    // --- Probe: never follow a pre-planted reparse point. ------------------
    // Open with FILE_FLAG_OPEN_REPARSE_POINT (the lstat equivalent): if the
    // name exists as a symlink/junction, reject it instead of locking its
    // target — which may lie outside the state root.
    {
        const HANDLE probe = CreateFileW(
            lockOsForm.c_str(), FILE_READ_ATTRIBUTES,
            FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
            FILE_FLAG_BACKUP_SEMANTICS | FILE_FLAG_OPEN_REPARSE_POINT, nullptr);
        if (probe != INVALID_HANDLE_VALUE) {
            BY_HANDLE_FILE_INFORMATION info {};
            // GetFileInformationByHandle (no W suffix in MinGW-w64; the
            // BY_HANDLE_FILE_INFORMATION layout is identical for the SDK's
            // A/W pair, which carries no strings).
            const bool gotInfo = GetFileInformationByHandle(probe, &info) != 0;
            if (gotInfo && (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
                CloseHandle(probe);
                std::fprintf(stderr, "warning: session lock for %s is a reparse point; rejecting\n",
                             sessionId.c_str());
                return true;
            }
            // A normal file exists: its FINAL path must resolve back inside
            // the canonical root (catches any case/volume spelling drift).
            auto finalLock = finalPathOf(probe);
            CloseHandle(probe);
            if (!finalLock || !romm::win32::isWithinRootCaseInsensitive(*finalLock, root)) {
                std::fprintf(stderr, "warning: session lock for %s escapes the state root; rejecting\n",
                             sessionId.c_str());
                return true;
            }
        } else {
            const DWORD code = GetLastError();
            // ERROR_FILE_NOT_FOUND (2): the lock file does not exist yet —
            // proceed to the create/lock step below. ERROR_PATH_NOT_FOUND
            // (3): the STATE ROOT itself does not exist, so the lock file
            // cannot exist either — the same "nothing to lock" case, NOT a
            // containment failure. The CREATE_ALWAYS open below then fails
            // harmlessly on its own (mirroring the POSIX open() failure
            // path: warn, do not claim the session).
            if (code != ERROR_FILE_NOT_FOUND && code != ERROR_PATH_NOT_FOUND) {
                // EACCES/... — cannot verify containment; do not open a
                // path we cannot prove is inside the state root.
                std::fprintf(stderr, "warning: cannot resolve session lock for %s (%s); rejecting\n",
                             sessionId.c_str(), winErrorText(code).c_str());
                return true;
            }
        }
    }

    // --- Acquire: create/open (following) + non-blocking exclusive lock. --
    const HANDLE file = CreateFileW(lockOsForm.c_str(), GENERIC_READ | GENERIC_WRITE,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                    nullptr, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, nullptr);
    if (file == INVALID_HANDLE_VALUE) {
        std::fprintf(stderr, "warning: could not create session lock %s: %s\n",
                     stateRoot.c_str(), winErrorText(GetLastError()).c_str());
        return false;  // mirrors POSIX open() failure: do NOT claim the session
    }

    SessionLockState& state = lockState();
    // LockFileEx (fileapi.h): MinGW-w64 declares the 6-parameter form with an
    // explicit dwReserved (the SDK's 5-parameter form omits it). The range
    // 0..0xFFFFFFFF-1 is the standard whole-file idiom (the .lock file is
    // empty, so this covers all of it).
    const BOOL locked = LockFileEx(file, LOCKFILE_EXCLUSIVE_LOCK | LOCKFILE_FAIL_IMMEDIATELY,
                                   0 /*dwReserved*/, 0xFFFFFFFF /*low*/, 0 /*high*/,
                                   &state.overlapped) != 0;
    if (!locked) {
        const DWORD code = GetLastError();
        CloseHandle(file);
        if (code == ERROR_LOCK_VIOLATION) {
            return true;  // a live player already owns this session
        }
        std::fprintf(stderr, "warning: LockFileEx failed on session lock for %s: %s\n",
                     sessionId.c_str(), winErrorText(code).c_str());
        return false;
    }

    // Close the probe->open TOCTOU window: re-verify the FINAL PATH of the
    // handle that actually holds the lock. If a reparse point was swapped in
    // between the probe and this open, the lock sits on its target — which
    // may be outside the root — so release it and fail closed.
    auto finalLock = finalPathOf(file);
    if (!finalLock || !romm::win32::isWithinRootCaseInsensitive(*finalLock, root)) {
        LockFileEx(file, 0 /*unlock*/, 0 /*dwReserved*/, 0xFFFFFFFF, 0, &state.overlapped);
        CloseHandle(file);
        std::fprintf(stderr, "warning: session lock for %s escapes the state root after open; rejecting\n",
                     sessionId.c_str());
        return true;
    }

    // Retain handle + OVERLAPPED for the process lifetime (deliberate leak):
    // the kernel releases the byte-range lock when the handle closes at
    // termination, so a crash never leaves a stale lock behind. The .lock
    // file is left in stateRoot on purpose — do NOT unlink it.
    state.hFile = file;
    return false;
}

}  // namespace romm::player

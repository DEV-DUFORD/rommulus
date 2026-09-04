// windows_path_security.cpp — Win32 implementation of the player's path
// security contract (native/player/include/native/player/path_security.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md sections 5.1/5.3): the handle/final-path
// counterpart of posix_path_security.cpp. Where POSIX canonicalizes with
// realpath(3) and classifies with lstat/st_mode, this source:
//   - converts every UTF-8 path strictly to UTF-16 at the boundary (utf16.h)
//     and fails closed on invalid input before any OS call;
//   - normalizes the path lexically through the pure rules in path_rules.h
//     (rejecting device paths, ADS, reserved names, trailing dots/spaces,
//     and volume-root escapes — the fail-closed set from section 5.3);
//   - resolves existing paths by HANDLE: CreateFileW +
//     GetFinalPathNameByHandleW(VOLUME_NAME_DOS), so symlinks/junctions in
//     the existing prefix are resolved exactly like realpath, and the
//     "\\?\C:\..." / "\\?\UNC\server\share\..." forms the API returns are
//     normalized back to the canonical "C:/..." / "//server/share/..."
//     slash form the neutral validation policy compares;
//   - classifies request files by file attributes and SECURITY DESCRIPTOR:
//     reparse point -> Symlink (ANY tag — symlinks, junctions, mount points
//     are all escape vectors for launch-control files), non-regular ->
//     NotRegularFile, owner SID != current user -> NotOwnedByUser (the NTFS
//     replacement for the POSIX UID check), and a DACL that grants write to
//     Everyone (or a NULL DACL) -> WorldWritable (the NTFS replacement for
//     the S_IWOTH check). Missing security metadata fails closed as
//     MissingOrUnreadable: when the filesystem cannot supply the metadata
//     the launch-control decision needs, the request is refused.
// The shared RequestFileStatus values and the validation.cpp error messages
// are preserved verbatim; only the OS-specific evidence behind them changes.
#include "native/player/path_security.h"

#include <native/platform/windows/final_path.h>
#include <native/platform/windows/path_rules.h>
#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// Handle-based security queries (GetSecurityInfo), ACE walking (GetAce),
// SID comparison (EqualSid), and SID-string parsing (ConvertStringSidToSidW)
// live in different headers per toolchain: the Windows SDK splits them across
// <seapi.h>/<securitybase.h>/<sddl.h>, while MinGW-w64 (this project's
// Windows toolchain, plans/WINDOWS_IMPL.md section 3.2) declares them in
// <aclapi.h>/<securitybaseapi.h>/<sddl.h>. There is no <securitybase.h> in
// MinGW-w64, so the include is branched on the compiler, not on a header
// that only one toolchain ships.
#ifdef __MINGW32__
#include <aclapi.h>
#include <sddl.h>
#include <securitybaseapi.h>
#else
#include <sddl.h>
#include <seapi.h>
#include <securitybase.h>
#endif

#include <string>
#include <vector>

namespace romm::player {
namespace {

// The current working directory (OS form, no trailing separator), or
// nullopt. Mirrors the POSIX getcwd step of canonicalPath().
// GetCurrentDirectoryW's FIRST call returns the buffer size INCLUDING the
// terminating NUL; its SECOND call returns the length EXCLUDING it. The path
// must therefore be taken from the second call's return value — reading the
// buffer's end would embed the NUL terminator in the path, and a second-call
// length that does not fit the buffer (truncation) or a zero (error) must
// fail closed.
std::optional<std::u16string> currentDirectory() {
    const DWORD needed = GetCurrentDirectoryW(0, nullptr);
    if (needed == 0) return std::nullopt;
    std::vector<wchar_t> buffer(needed);
    const DWORD written = GetCurrentDirectoryW(needed, buffer.data());
    if (written == 0 || written > needed - 1) return std::nullopt;
    return std::u16string(buffer.begin(), buffer.begin() + written);
}

// Resolves the final path of an OPEN handle: the shared buffer-growth
// contract (final_path.h) drives GetFinalPathNameByHandleW (long paths are
// first-class — no MAX_PATH cap; a truncated call only GROWS the buffer, it
// never reads past it), then the pure unification that strips the "\\?\" /
// "\\?\UNC\" prefix and converts to the canonical slash form. nullopt on
// failure.
std::optional<std::u16string> finalPathOf(HANDLE handle) {
    const auto raw = romm::win32::fetchFinalPath(
        [handle](wchar_t* buffer, std::uint32_t capacity) -> std::uint32_t {
            return GetFinalPathNameByHandleW(handle, buffer, capacity, VOLUME_NAME_DOS);
        });
    if (!raw) return std::nullopt;
    // fetchFinalPath returns exactly the stored characters, NUL EXCLUDED
    // (written - 1 would silently drop the path's last character).
    // OS-returned form: "\\?\C:\..." or "\\?\UNC\server\share\...".
    std::u16string unified;
    unified.reserve(raw->size());
    size_t i = 0;
    if (raw->size() >= 8 && raw->compare(0, 8, u"\\\\?\\UNC\\") == 0) {
        i = 8;  // "\\?\UNC\server\share" -> "//server/share"
        unified += u"//";
    } else if (raw->size() >= 4 && raw->compare(0, 4, u"\\\\?\\") == 0) {
        i = 4;  // "\\?\C:\..." -> "C:..."
    }
    for (; i < raw->size(); ++i) unified.push_back((*raw)[i] == u'\\' ? u'/' : (*raw)[i]);
    return unified;
}

// The OS-boundary form of a normalized path (backslashes, drive-root
// separator), converted to the Win32 wchar_t string ONCE — char16_t* is not
// LPCWSTR (see utf16.h), so no u16 c_str() ever reaches a Win32 API.
std::wstring osForm(const std::u16string& normalized) {
    return romm::win32::toWideString(romm::win32::toOsForm(normalized));
}

// Opens an existing path for metadata queries. openReparsePoint selects the
// lstat-equivalent (inspect the reparse point itself, do NOT follow it) vs.
// the stat-equivalent (follow it). FILE_FLAG_BACKUP_SEMANTICS makes
// directories openable; full sharing keeps the probe from disturbing other
// handles. nullopt when the path cannot be opened.
std::optional<HANDLE> openForQuery(const std::wstring& osPath, bool openReparsePoint) {
    const DWORD flags = FILE_FLAG_BACKUP_SEMANTICS |
                        (openReparsePoint ? FILE_FLAG_OPEN_REPARSE_POINT : 0);
    const HANDLE handle = CreateFileW(osPath.c_str(), GENERIC_READ,
                                      FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                      nullptr, OPEN_EXISTING, flags, nullptr);
    if (handle == INVALID_HANDLE_VALUE) return std::nullopt;
    return handle;
}

// True when the path exists in any form (follows reparse points). Used for
// the deepest-existing-ancestor walk of canonicalPath().
bool pathExists(const std::wstring& osPath) {
    return GetFileAttributesW(osPath.c_str()) != INVALID_FILE_ATTRIBUTES;
}

}  // namespace

std::optional<std::string> canonicalPath(const std::string& path, std::string* error) {
    if (path.empty()) {
        if (error != nullptr) *error = "empty path";
        return std::nullopt;
    }

    // Strict UTF-8 -> UTF-16 at the boundary: invalid input fails here,
    // before any OS call.
    const auto wide = romm::win32::utf8ToUtf16(path);
    if (!wide) {
        if (error != nullptr) *error = "invalid UTF-8 in path";
        return std::nullopt;
    }

    // Make absolute against the working directory, mirroring POSIX.
    std::u16string absolute = *wide;
    if (!romm::win32::isAbsoluteWin32(absolute)) {
        const auto cwd = currentDirectory();
        if (!cwd) {
            if (error != nullptr) *error = "cannot resolve working directory";
            return std::nullopt;
        }
        absolute = *cwd + u"\\" + *wide;
    }

    // Lexical normalization plus the fail-closed security rejections
    // (device paths, ADS, reserved names, trailing dots/spaces, escapes).
    auto parts = romm::win32::normalizeWin32PathParts(absolute);
    if (!parts) {
        if (error != nullptr) *error = "invalid path";
        return std::nullopt;
    }

    // Find the deepest existing prefix. A child cannot exist without its
    // parent, so the first missing prefix ends the walk. Probes use the OS
    // form (backslashes; a bare drive root needs its trailing separator).
    const auto osPrefix = [&](size_t depth) {
        romm::win32::Win32PathParts prefix = *parts;
        prefix.components.resize(depth);
        return osForm(romm::win32::joinWin32PathParts(prefix));
    };
    size_t existingDepth = 0;
    for (size_t depth = 1; depth <= parts->components.size(); ++depth) {
        if (!pathExists(osPrefix(depth))) break;
        existingDepth = depth;
    }
    if (existingDepth == 0 && !pathExists(osForm(parts->volume))) {
        // Even the volume root is gone (e.g. a missing drive letter).
        if (error != nullptr) *error = "cannot canonicalize: " + path;
        return std::nullopt;
    }

    // Canonicalize the existing prefix BY HANDLE: opening without
    // FILE_FLAG_OPEN_REPARSE_POINT follows every symlink/junction in it, and
    // GetFinalPathNameByHandleW returns the fully resolved final name — the
    // Win32 counterpart of realpath(existing). The not-yet-existing tail is
    // re-appended; its components cannot be symlinks because they do not
    // exist.
    const auto handleOpt = openForQuery(osPrefix(existingDepth), /*openReparsePoint=*/false);
    if (!handleOpt) {
        if (error != nullptr) *error = "cannot canonicalize: " + path;
        return std::nullopt;
    }
    const HANDLE handle = *handleOpt;
    auto finalPrefix = finalPathOf(handle);
    CloseHandle(handle);
    if (!finalPrefix) {
        if (error != nullptr) *error = "cannot canonicalize: " + path;
        return std::nullopt;
    }

    romm::win32::Win32PathParts result;
    // Re-parse the OS-resolved prefix into volume+components so the tail can
    // be appended in canonical form. An OS-returned final path is always a
    // well-formed absolute path, so this cannot fail in practice.
    auto parsed = romm::win32::normalizeWin32PathParts(*finalPrefix);
    if (!parsed) {
        if (error != nullptr) *error = "cannot canonicalize: " + path;
        return std::nullopt;
    }
    result = *parsed;
    for (size_t i = existingDepth; i < parts->components.size(); ++i) {
        result.components.push_back(parts->components[i]);
    }

    const auto utf8 = romm::win32::utf16ToUtf8(romm::win32::joinWin32PathParts(result));
    if (!utf8) {
        if (error != nullptr) *error = "cannot canonicalize: " + path;
        return std::nullopt;
    }
    return *utf8;
}

// True when `path` exists and is a reparse point — the Win32 counterpart of
// POSIX lstat+S_ISLNK. ANY reparse tag counts (symlink, junction, mount
// point, or anything else): for launch-control containment a reparse point
// is an escape vector regardless of its advertised kind, so it is classified
// as a symlink and the validation policy rejects it. Fail closed: a path we
// cannot open is simply not a symlink (its rejection, if any, happens in
// canonicalPath/requestFileSecurity).
bool isSymlink(const std::string& path) {
    const auto wide = romm::win32::utf8ToUtf16(path);
    if (!wide) return false;
    auto parts = romm::win32::normalizeWin32PathParts(*wide);
    if (!parts) return false;  // rejected forms are not symlinks; they fail elsewhere
    const auto handle = openForQuery(osForm(romm::win32::joinWin32PathParts(*parts)),
                                     /*openReparsePoint=*/true);
    if (!handle) return false;
    BY_HANDLE_FILE_INFORMATION info {};
    // GetFileInformationByHandle (no W suffix in MinGW-w64; the SDK's A/W
    // pair is meaningless here because BY_HANDLE_FILE_INFORMATION carries no
    // strings) reports the file's attributes through the handle.
    const bool reparse =
        GetFileInformationByHandle(*handle, &info) != 0 &&
        (info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0;
    CloseHandle(*handle);
    return reparse;
}

RequestFileStatus requestFileSecurity(const std::string& path) {
    const auto wide = romm::win32::utf8ToUtf16(path);
    if (!wide) return RequestFileStatus::MissingOrUnreadable;
    // Normalization doubles as the device/ADS/reserved/trailing-dot
    // rejection: a request file whose NAME is a device path or an ADS is not
    // readable as a regular file, full stop. Fail closed.
    auto parts = romm::win32::normalizeWin32PathParts(*wide);
    if (!parts) return RequestFileStatus::MissingOrUnreadable;

    // One handle for the whole classification (no exists-then-open TOCTOU):
    // GENERIC_READ + FILE_FLAG_OPEN_REPARSE_POINT inspects the file itself,
    // and the same handle serves the attribute, owner, and DACL queries.
    const auto handle = openForQuery(osForm(romm::win32::joinWin32PathParts(*parts)),
                                     /*openReparsePoint=*/true);
    if (!handle) return RequestFileStatus::MissingOrUnreadable;

    BY_HANDLE_FILE_INFORMATION info {};
    if (GetFileInformationByHandle(*handle, &info) == 0) {
        CloseHandle(*handle);
        return RequestFileStatus::MissingOrUnreadable;
    }
    if ((info.dwFileAttributes & FILE_ATTRIBUTE_REPARSE_POINT) != 0) {
        CloseHandle(*handle);
        return RequestFileStatus::Symlink;
    }
    if ((info.dwFileAttributes & (FILE_ATTRIBUTE_DIRECTORY | FILE_ATTRIBUTE_DEVICE)) != 0) {
        CloseHandle(*handle);
        return RequestFileStatus::NotRegularFile;
    }

    // --- Owner check (the NTFS replacement for st_uid == euid/uid). -------
    // The file's owner SID must equal the current user's SID. Any failure to
    // READ the security metadata fails closed as MissingOrUnreadable: a
    // filesystem that cannot prove ownership is not one we will launch from.
    //
    // GetSecurityInfo (seapi.h on the SDK, aclapi.h on MinGW-w64; advapi32)
    // is the real handle-based security query: it fills the requested fields
    // and returns a self-relative SECURITY_DESCRIPTOR allocated by the OS,
    // which the caller MUST release with LocalFree. (The earlier draft called
    // GetFileSecurityByHandle — no such function exists in the SDK or MinGW
    // headers; the two-pass "size then fill" dance it used is a
    // GetTokenInformation idiom, not a security-descriptor one.)
    PSID ownerSid = nullptr;
    PSECURITY_DESCRIPTOR ownerSd = nullptr;
    const DWORD ownerStatus =
        GetSecurityInfo(*handle, SE_FILE_OBJECT, OWNER_SECURITY_INFORMATION, &ownerSid,
                        nullptr, nullptr, nullptr, &ownerSd);
    if (ownerStatus != ERROR_SUCCESS || ownerSid == nullptr) {
        if (ownerSd != nullptr) LocalFree(ownerSd);
        CloseHandle(*handle);
        return RequestFileStatus::MissingOrUnreadable;
    }

    // The current user's SID comes from the process token (two-pass
    // GetTokenInformation: query length, then fill).
    PSID userSid = nullptr;
    std::vector<BYTE> tokenBuffer;
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) != 0) {
        DWORD tokenNeeded = 0;
        if (GetTokenInformation(token, TokenUser, nullptr, 0, &tokenNeeded) == 0 &&
            GetLastError() == ERROR_INSUFFICIENT_BUFFER && tokenNeeded > 0) {
            tokenBuffer.resize(tokenNeeded);
            if (GetTokenInformation(token, TokenUser, tokenBuffer.data(), tokenNeeded,
                                    &tokenNeeded) != 0) {
                userSid = reinterpret_cast<const TOKEN_USER*>(tokenBuffer.data())->User.Sid;
            }
        }
    }
    if (token != nullptr) CloseHandle(token);
    if (userSid == nullptr) {
        LocalFree(ownerSd);
        CloseHandle(*handle);
        return RequestFileStatus::MissingOrUnreadable;  // cannot verify: fail closed
    }
    // EqualSid (securitybase.h / securitybaseapi.h, advapi32) is the SDK's
    // SID comparator: it returns TRUE when the two SIDs are identical. (The
    // earlier draft called SidEquals — no such function exists in the SDK or
    // MinGW headers.)
    const bool ownedByUser = EqualSid(ownerSid, userSid) != 0;
    LocalFree(ownerSd);
    if (!ownedByUser) {
        CloseHandle(*handle);
        return RequestFileStatus::NotOwnedByUser;
    }

    // --- World-writable check (the NTFS replacement for S_IWOTH). ---------
    // A NULL DACL means "everyone has full control" — world-writable by
    // definition. Otherwise an ACCESS_ALLOWED ACE that grants ANY write right
    // to the Everyone SID (S-1-1-0) makes the file writable by users other
    // than its owner: refused, exactly as POSIX refuses S_IWOTH. (The
    // well-known "Users" group is deliberately NOT treated as world: on a
    // normal single-user desktop the owner is in it, which is the same
    // situation POSIX encodes as owner-write-only.)
    PACL dacl = nullptr;
    PSECURITY_DESCRIPTOR daclSd = nullptr;
    const DWORD daclStatus = GetSecurityInfo(*handle, SE_FILE_OBJECT,
                                             DACL_SECURITY_INFORMATION, nullptr, nullptr, &dacl,
                                             nullptr, &daclSd);
    if (daclStatus != ERROR_SUCCESS) {
        if (daclSd != nullptr) LocalFree(daclSd);
        CloseHandle(*handle);
        return RequestFileStatus::MissingOrUnreadable;
    }
    // A NULL DACL means "everyone has full control" — world-writable by
    // definition. (GetSecurityInfo sets *ppDacl to NULL when the object has
    // no DACL; the descriptor is still allocated and must be freed.)
    if (dacl == nullptr) {
        LocalFree(daclSd);
        CloseHandle(*handle);
        return RequestFileStatus::WorldWritable;
    }

    // The Everyone reference SID (S-1-1-0) comes from the OS parser:
    // ConvertStringSidToSidW (sddl.h, advapi32) returns a locally allocated
    // PSID that the caller frees with LocalFree. (MakeWellKnownSid is not
    // declared by MinGW-w64, so the string form is the portable entry point.)
    PSID everyoneSid = nullptr;
    if (ConvertStringSidToSidW(L"S-1-1-0", &everyoneSid) == 0 || everyoneSid == nullptr) {
        LocalFree(daclSd);
        CloseHandle(*handle);
        return RequestFileStatus::MissingOrUnreadable;  // cannot build the reference SID
    }

    // Conservative write-grant mask. An ACCESS_ALLOWED ACE makes the file
    // world-writable (the S_IWOTH replacement) when it gives the Everyone
    // SID any right that can MODIFY the file's contents or disposition: the
    // specific file write rights (FILE_WRITE_DATA, FILE_APPEND_DATA,
    // FILE_WRITE_EA, FILE_WRITE_ATTRIBUTES), DELETE, WRITE_DAC, WRITE_OWNER,
    // plus the GENERIC_WRITE alias in case an ACE stores it untranslated.
    // Read-only rights (FILE_READ_DATA/EA/ATTRIBUTES, FILE_EXECUTE,
    // SYNCHRONIZE, READ_CONTROL) do NOT match. GENERIC_ALL and FILE_ALL_
    // ACCESS are deliberately excluded: they are generic aliases that
    // overlap read bits (FILE_ALL_ACCESS = 0x1F01FF includes FILE_READ_DATA
    // and friends), so matching them would flag every read ACE as
    // world-writable — a false positive, not a conservative classification.
    const ACCESS_MASK kWriteMask = FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA |
                                   FILE_WRITE_ATTRIBUTES | DELETE | WRITE_DAC | WRITE_OWNER |
                                   GENERIC_WRITE;
    for (DWORD i = 0; i < dacl->AceCount; ++i) {
        // GetAce (securitybase.h / securitybaseapi.h, advapi32) is the
        // documented ACE walker. The FirstAce/NextAce macros exist only in
        // the SDK's acomplex.h (MinGW-w64 does not define them) and are
        // fragile one-liners, so they are deliberately not used.
        LPVOID ace = nullptr;
        if (GetAce(dacl, i, &ace) == FALSE) {
            LocalFree(everyoneSid);
            LocalFree(daclSd);
            CloseHandle(*handle);
            return RequestFileStatus::MissingOrUnreadable;  // unreadable DACL: fail closed
        }
        const auto* header = static_cast<const ACE_HEADER*>(ace);
        if (header->AceType == ACCESS_ALLOWED_ACE_TYPE) {
            const auto* allowed = static_cast<const ACCESS_ALLOWED_ACE*>(ace);
            // SidStart is the first DWORD of the variable-length SID stored
            // inline in ACCESS_ALLOWED_ACE, not an offset value.
            const PSID aceSid =
                reinterpret_cast<PSID>(const_cast<DWORD*>(&allowed->SidStart));
            if ((allowed->Mask & kWriteMask) != 0 && EqualSid(aceSid, everyoneSid) != 0) {
                LocalFree(everyoneSid);
                LocalFree(daclSd);
                CloseHandle(*handle);
                return RequestFileStatus::WorldWritable;
            }
        }
    }

    LocalFree(everyoneSid);
    LocalFree(daclSd);
    CloseHandle(*handle);
    return RequestFileStatus::Ok;
}

std::optional<long long> fileSize(const std::string& path) {
    const auto wide = romm::win32::utf8ToUtf16(path);
    if (!wide) return std::nullopt;
    auto parts = romm::win32::normalizeWin32PathParts(*wide);
    if (!parts) return std::nullopt;  // rejected forms: fail closed as "missing"

    // stat-equivalent (FOLLOWS reparse points, like POSIX stat): the restore-
    // on-launch size decision reads the file the user would actually read.
    const auto handle = openForQuery(osForm(romm::win32::joinWin32PathParts(*parts)),
                                     /*openReparsePoint=*/false);
    if (!handle) return std::nullopt;
    LARGE_INTEGER size {};
    const bool ok = GetFileSizeEx(*handle, &size) != 0;
    CloseHandle(*handle);
    if (!ok) return std::nullopt;
    return static_cast<long long>(size.QuadPart);
}

}  // namespace romm::player

// windows_atomic_file_store.cpp — Win32 implementation of the engine's
// atomic file store contract (native/engine/src/atomic_file_store.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md section 5.5): the durable-write primitive
// for Windows hosts, selected at CMake configure time by
// romm_select_platform_sources() exactly like the POSIX implementation.
// Same public API and same error semantics as posix_atomic_file_store.cpp:
//   - write to a UNIQUE temp file in the destination directory — CreateFileW
//     with CREATE_NEW claims each candidate name atomically, so there is no
//     exists-then-open race;
//   - WriteFile loop, FlushFileBuffers (the fsync equivalent), close;
//   - BEFORE the replace, the temp file is given the security descriptor the
//     destination will carry (section 5.5 step 5): an existing destination's
//     DACL is preserved verbatim (GetSecurityInfo -> SetFileSecurityW), and
//     an absent destination receives the safe current-user + SYSTEM
//     FILE_ALL_ACCESS DACL. The DACL is set on the private temp file, so the
//     destination path never exists in a weak-ACL state;
//   - atomic ReplaceFileW over an existing destination, or MoveFileExW with
//     MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH when none exists —
//     readers see either the old file or the new one, never a partial write;
//   - security-step failures fail closed (these writes carry sensitive
//     save/result/control data: a write whose security cannot be proven is a
//     failed write) with explicit temp cleanup on every failure path;
//   - a missing file is a NORMAL case in readFileExact (silent false); other
//     failures log through the engine's log sink with messages that mirror
//     the POSIX implementation one-for-one.
// All paths cross the Win32 boundary as UTF-8 -> UTF-16 through the strict
// converter in native/platform/windows/utf16.h; invalid input fails closed
// before any OS call.
#include "atomic_file_store.h"

#include <native/engine/LogSink.h>
#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// Handle-based security queries (GetSecurityInfo), DACL application
// (SetFileSecurityW), and the SDDL/SID string converters live in different
// headers per toolchain: the Windows SDK splits them across <seapi.h>/
// <securitybase.h>/<sddl.h>, while MinGW-w64 (this project's Windows
// toolchain, plans/WINDOWS_IMPL.md section 3.2) declares them in
// <aclapi.h>/<securitybaseapi.h>/<sddl.h>. There is no <securitybase.h> in
// MinGW-w64, so the include is branched on the compiler (same convention as
// windows_path_security.cpp).
#ifdef __MINGW32__
#include <aclapi.h>
#include <sddl.h>
#include <securitybaseapi.h>
#else
#include <sddl.h>
#include <seapi.h>
#include <securitybase.h>
#endif

#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <optional>
#include <string>
#include <vector>

#define LOG_TAG "romm_atomic_file_store"

namespace {

// Formats printf-style arguments for the platform-neutral engine log sink
// (mirrors the POSIX implementation).
std::string formatLog(const char* format, ...) {
    va_list args;
    va_start(args, format);
    const int len = std::vsnprintf(nullptr, 0, format, args);
    va_end(args);
    if (len < 0) return std::string();
    std::string message(static_cast<std::size_t>(len), '\0');
    va_start(args, format);
    std::vsnprintf(message.data(), static_cast<std::size_t>(len) + 1, format, args);
    va_end(args);
    return message;
}

// Formats a Win32 error code exactly once (FormatMessageW), converting the
// UTF-16 result strictly to UTF-8 for the log sink. Numeric fallback when
// the OS cannot supply text (unknown code, allocation failure).
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
    // FormatMessage leaves trailing CR/LF/whitespace; trim it so the text
    // composes cleanly into log lines.
    const size_t end = wide->find_last_not_of(" \t\r\n");
    return end == std::string::npos ? std::string() : wide->substr(0, end + 1);
}

// WriteFile/ReadFile take a DWORD byte count; chunk sizes above 4 GiB.
DWORD chunkSize(size_t remaining) {
    return remaining > static_cast<size_t>(UINT32_MAX) ? UINT32_MAX
                                                       : static_cast<DWORD>(remaining);
}

}  // namespace

#define LOGE(...) \
    romm::log::sink().log(romm::log::Severity::Error, LOG_TAG, formatLog(__VA_ARGS__))

namespace {

// The current user's SID as a string (S-1-5-21-...), or nullopt. Two-pass
// GetTokenInformation on the process token (query length, then fill — the
// same idiom windows_path_security.cpp uses for the owner check), then
// ConvertSidToStringSidW, whose result is a locally allocated string the
// caller releases with LocalFree. nullopt on any failure: the safe-DACL
// builder refuses to run without a verified user SID (fail closed).
std::optional<std::wstring> currentUserSidString() {
    HANDLE token = nullptr;
    if (OpenProcessToken(GetCurrentProcess(), TOKEN_QUERY, &token) == 0) {
        return std::nullopt;
    }
    std::optional<std::wstring> result;
    DWORD needed = 0;
    if (GetTokenInformation(token, TokenUser, nullptr, 0, &needed) == 0 &&
        GetLastError() == ERROR_INSUFFICIENT_BUFFER && needed > 0) {
        std::vector<BYTE> buffer(needed);
        DWORD actual = 0;
        if (GetTokenInformation(token, TokenUser, buffer.data(), needed, &actual) != 0) {
            const PSID sid = reinterpret_cast<const TOKEN_USER*>(buffer.data())->User.Sid;
            LPWSTR sidString = nullptr;
            if (ConvertSidToStringSidW(sid, &sidString) != 0 && sidString != nullptr) {
                result = std::wstring(sidString);
                LocalFree(sidString);
            }
        }
    }
    CloseHandle(token);
    return result;
}

// Applies the destination's security policy to the TEMP file before the
// atomic replace (section 5.5 step 5, "preserve or explicitly apply the
// destination ACL"). ReplaceFileW/MoveFileExW install the temp file's
// security descriptor on the destination, so the descriptor the destination
// will carry must be set on the temp — a private name claimed with
// CREATE_NEW — BEFORE the replace; the destination path therefore never
// exists in a weak-ACL state (no transient publicly writable final file).
//   - destination exists  -> its existing DACL is preserved verbatim
//     (GetSecurityInfo on the destination handle, SetFileSecurityW on the
//     temp): a hardened per-user DACL is no longer reset to the directory
//     default by every atomic replace underneath it;
//   - destination absent  -> the safe current-user + SYSTEM FILE_ALL_ACCESS
//     DACL (the section 4.2/5.3 hardened shape) is applied, so a first write
//     never exposes the destination with the directory-default ACL.
// Every failure returns false (fail closed): these writes carry sensitive
// save/result/control data, and a write whose security cannot be proven is a
// failed write.
bool applyDestinationSecurityPolicy(const std::wstring& target, const std::wstring& temp) {
    // Probe the destination for READ_CONTROL with full sharing (the probe
    // must not disturb concurrent readers/writers of the existing file).
    const HANDLE dest = CreateFileW(target.c_str(), READ_CONTROL,
                                    FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                    nullptr, OPEN_EXISTING, 0, nullptr);
    if (dest != INVALID_HANDLE_VALUE) {
        // --- Destination exists: preserve its existing DACL. ---
        PACL dacl = nullptr;
        PSECURITY_DESCRIPTOR sd = nullptr;
        const DWORD status = GetSecurityInfo(dest, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                             nullptr, nullptr, &dacl, nullptr, &sd);
        if (status == ERROR_SUCCESS && dacl != nullptr) {
            // The OS-allocated, self-relative descriptor is released with
            // LocalFree (the GetSecurityInfo contract) and is directly
            // consumable by SetFileSecurityW.
            const bool applied =
                SetFileSecurityW(temp.c_str(), DACL_SECURITY_INFORMATION, sd) != FALSE;
            if (!applied) {
                LOGE("atomicWriteFile: SetFileSecurity(temp) failed while preserving the "
                     "destination DACL: %s",
                     winErrorText(GetLastError()).c_str());
            }
            LocalFree(sd);
            CloseHandle(dest);
            return applied;
        }
        if (status != ERROR_SUCCESS) {
            if (sd != nullptr) LocalFree(sd);
            CloseHandle(dest);
            LOGE("atomicWriteFile: GetSecurityInfo(destination) failed: %s",
                 winErrorText(status).c_str());
            return false;  // security metadata unreadable: fail closed
        }
        // status == ERROR_SUCCESS && dacl == nullptr: a NULL DACL means
        // "everyone has full control". There is no hardening to preserve,
        // and re-applying it would install a world-writable DACL on the new
        // file — fall through to the safe DACL below.
        if (sd != nullptr) LocalFree(sd);
        CloseHandle(dest);
        LOGE("atomicWriteFile: destination has a NULL DACL; applying the safe DACL "
             "instead of preserving it");
    } else {
        const DWORD code = GetLastError();
        if (code != ERROR_FILE_NOT_FOUND) {
            // The destination exists (or the path resolves) but its security
            // metadata cannot be read — e.g. a DACL denying READ_CONTROL, or
            // a directory in place of the file. Fail closed: do not replace
            // a file whose ACL cannot be verified.
            LOGE("atomicWriteFile: cannot open destination for READ_CONTROL: %s",
                 winErrorText(code).c_str());
            return false;
        }
        // --- Destination absent: fall through to the safe DACL. ---
    }

    // Safe DACL: the current user and SYSTEM with FILE_ALL_ACCESS each, and
    // nothing else — no Everyone, no directory-default inheritance. Built
    // as SDDL and parsed by the OS; ConvertStringSecurityDescriptorTo-
    // SecurityDescriptorW returns a self-relative descriptor released with
    // LocalFree.
    const auto userSid = currentUserSidString();
    if (!userSid) {
        LOGE("atomicWriteFile: cannot resolve the current user SID; refusing to apply "
             "the safe DACL");
        return false;
    }
    const std::wstring sddl = L"D:(A;;FA;;;" + *userSid + L")(A;;FA;;;S-1-5-18)";
    PSECURITY_DESCRIPTOR safeSd = nullptr;
    if (ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl.c_str(), SDDL_REVISION_1,
                                                             &safeSd, nullptr) == FALSE) {
        LOGE("atomicWriteFile: ConvertStringSecurityDescriptorToSecurityDescriptorW "
             "failed: %s",
             winErrorText(GetLastError()).c_str());
        return false;
    }
    const bool applied =
        SetFileSecurityW(temp.c_str(), DACL_SECURITY_INFORMATION, safeSd) != FALSE;
    if (!applied) {
        LOGE("atomicWriteFile: SetFileSecurity(temp, safe DACL) failed: %s",
             winErrorText(GetLastError()).c_str());
    }
    LocalFree(safeSd);
    return applied;
}

}  // namespace

namespace romm {

bool atomicWriteFile(const std::string& path, const void* data, size_t size) {
    // Strict boundary conversion: an invalid UTF-8 path cannot name a file.
    const auto wideTarget = romm::win32::utf8ToUtf16(path);
    if (!wideTarget) {
        LOGE("atomicWriteFile: invalid UTF-8 in path (%s)", path.c_str());
        return false;
    }
    // Win32 boundary form (see utf16.h): char16_t* is not LPCWSTR.
    const std::wstring targetWide = romm::win32::toWideString(*wideTarget);

    // Unique temp file in the destination directory (section 5.5 step 1):
    // try <path>.tmp first, then uniquify with a process counter as
    // collisions occur. CREATE_NEW claims each candidate atomically — a
    // concurrent writer loses the claim and moves to the next name.
    const std::string base = path + ".tmp";
    static std::atomic<unsigned int> sequence{0};
    std::optional<std::u16string> wideTemp;
    std::wstring tempWide;
    std::string tempPath = base;
    HANDLE hFile = INVALID_HANDLE_VALUE;
    for (unsigned int attempt = 0; attempt < 1024; ++attempt) {
        if (attempt > 0) {
            tempPath = base + "." + std::to_string(sequence.fetch_add(1));
        }
        wideTemp = romm::win32::utf8ToUtf16(tempPath);
        if (!wideTemp) {
            LOGE("atomicWriteFile: invalid UTF-8 in temp path (%s)", tempPath.c_str());
            return false;
        }
        const std::wstring candidate = romm::win32::toWideString(*wideTemp);
        hFile = CreateFileW(candidate.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_NEW,
                            FILE_ATTRIBUTE_NORMAL, nullptr);
        if (hFile != INVALID_HANDLE_VALUE) {
            tempWide = candidate;
            break;
        }
        const DWORD code = GetLastError();
        if (code != ERROR_FILE_EXISTS && code != ERROR_ALREADY_EXISTS) {
            LOGE("atomicWriteFile: CreateFile(%s) failed: %s", tempPath.c_str(),
                 winErrorText(code).c_str());
            return false;
        }
    }
    if (hFile == INVALID_HANDLE_VALUE) {
        LOGE("atomicWriteFile: could not claim a unique temp name for %s", path.c_str());
        return false;
    }

    // Write loop: WriteFile may report fewer bytes than requested, so drive
    // the full transfer explicitly instead of assuming one call.
    const auto* bytes = static_cast<const uint8_t*>(data);
    size_t offset = 0;
    while (offset < size) {
        DWORD written = 0;
        if (!WriteFile(hFile, bytes + offset, chunkSize(size - offset), &written, nullptr)) {
            LOGE("atomicWriteFile: WriteFile(%s) failed: %s", tempPath.c_str(),
                 winErrorText(GetLastError()).c_str());
            CloseHandle(hFile);
            DeleteFileW(tempWide.c_str());
            return false;
        }
        if (written == 0) {
            LOGE("atomicWriteFile: WriteFile(%s) made no progress", tempPath.c_str());
            CloseHandle(hFile);
            DeleteFileW(tempWide.c_str());
            return false;
        }
        offset += written;
    }

    // Flush the temp file's contents to stable storage before the replace,
    // so a crash between these two steps never leaves a replaced-but-not-
    // durable file (the fsync-before-rename guarantee of the POSIX version).
    if (!FlushFileBuffers(hFile)) {
        LOGE("atomicWriteFile: FlushFileBuffers(%s) failed: %s", tempPath.c_str(),
             winErrorText(GetLastError()).c_str());
        CloseHandle(hFile);
        DeleteFileW(tempWide.c_str());
        return false;
    }

    if (!CloseHandle(hFile)) {
        LOGE("atomicWriteFile: CloseHandle(%s) failed: %s", tempPath.c_str(),
             winErrorText(GetLastError()).c_str());
        DeleteFileW(tempWide.c_str());
        return false;
    }

    // Security step (section 5.5 step 5): give the temp file the descriptor
    // the destination will carry BEFORE the replace — preserve the existing
    // destination's DACL, or apply the safe current-user + SYSTEM DACL when
    // none exists. Fail closed on any failure (sensitive writes): the temp
    // is removed and the existing destination, if any, is left untouched.
    if (!applyDestinationSecurityPolicy(targetWide, tempWide)) {
        DeleteFileW(tempWide.c_str());
        return false;
    }

    // Atomic replace (section 5.5 step 4): ReplaceFileW over an existing
    // destination with write-through semantics; when no destination exists
    // yet, MoveFileExW with REPLACE_EXISTING | WRITE_THROUGH performs the
    // same atomic move-into-place. Either way the destination appears fully
    // written or not at all — a failed replace leaves any existing file
    // untouched and removes the temp (mirrors the POSIX rename failure path).
    // ReplaceFileW (fileapi.h): 6 parameters — (replacement, replaced,
    // backup, flags, exclude, data). No backup file, no exclusions, no
    // data; write-through so the replace is durable before it returns.
    if (!ReplaceFileW(targetWide.c_str(), tempWide.c_str(), nullptr,
                      REPLACEFILE_WRITE_THROUGH, nullptr, nullptr)) {
        const DWORD replaceCode = GetLastError();
        if (replaceCode != ERROR_FILE_NOT_FOUND) {
            LOGE("atomicWriteFile: ReplaceFile(%s -> %s) failed: %s", tempPath.c_str(),
                 path.c_str(), winErrorText(replaceCode).c_str());
            DeleteFileW(tempWide.c_str());
            return false;
        }
        if (!MoveFileExW(tempWide.c_str(), targetWide.c_str(),
                         MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) {
            LOGE("atomicWriteFile: MoveFileEx(%s -> %s) failed: %s", tempPath.c_str(),
                 path.c_str(), winErrorText(GetLastError()).c_str());
            DeleteFileW(tempWide.c_str());
            return false;
        }
    }

    return true;
}

bool readFileExact(const std::string& path, void* data, size_t size) {
    HANDLE hFile = INVALID_HANDLE_VALUE;
    const auto widePath = romm::win32::utf8ToUtf16(path);
    if (widePath) {
        const std::wstring wide = romm::win32::toWideString(*widePath);
        hFile = CreateFileW(wide.c_str(), GENERIC_READ,
                            FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                            FILE_ATTRIBUTE_NORMAL, nullptr);
    }
    if (hFile == INVALID_HANDLE_VALUE) {
        return false;  // not an error — "no existing save" is a normal, common case
    }

    LARGE_INTEGER fileSize{};
    if (!GetFileSizeEx(hFile, &fileSize) || fileSize.QuadPart < 0 ||
        static_cast<size_t>(fileSize.QuadPart) != size) {
        LOGE("readFileExact: %s size mismatch (expected %zu, got %lld)", path.c_str(), size,
             static_cast<long long>(fileSize.QuadPart));
        CloseHandle(hFile);
        return false;
    }

    auto* out = static_cast<uint8_t*>(data);
    size_t readBytes = 0;
    bool ok = true;
    while (readBytes < size) {
        DWORD got = 0;
        if (!ReadFile(hFile, out + readBytes, chunkSize(size - readBytes), &got, nullptr)) {
            ok = false;
            break;
        }
        if (got == 0) {
            ok = false;  // no progress: stop rather than spin
            break;
        }
        readBytes += got;
    }
    CloseHandle(hFile);

    if (!ok || readBytes != size) {
        LOGE("readFileExact: short read (%zu of %zu) from %s", readBytes, size, path.c_str());
        return false;
    }

    return true;
}

bool readWholeFile(const std::string& path, std::vector<uint8_t>& out) {
    const auto widePath = romm::win32::utf8ToUtf16(path);
    if (!widePath) {
        LOGE("readWholeFile: invalid UTF-8 in path (%s)", path.c_str());
        return false;
    }

    const std::wstring wide = romm::win32::toWideString(*widePath);
    HANDLE hFile = CreateFileW(wide.c_str(), GENERIC_READ,
                               FILE_SHARE_READ | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING,
                               FILE_ATTRIBUTE_NORMAL, nullptr);
    if (hFile == INVALID_HANDLE_VALUE) {
        LOGE("readWholeFile: CreateFile(%s) failed: %s", path.c_str(),
             winErrorText(GetLastError()).c_str());
        return false;
    }

    LARGE_INTEGER fileSize{};
    if (!GetFileSizeEx(hFile, &fileSize) || fileSize.QuadPart < 0) {
        LOGE("readWholeFile: GetFileSizeEx(%s) failed: %s", path.c_str(),
             winErrorText(GetLastError()).c_str());
        CloseHandle(hFile);
        return false;
    }

    out.assign(static_cast<size_t>(fileSize.QuadPart), 0);
    size_t readBytes = 0;
    bool ok = true;
    while (readBytes < out.size()) {
        DWORD got = 0;
        if (!ReadFile(hFile, out.data() + readBytes, chunkSize(out.size() - readBytes), &got,
                      nullptr)) {
            ok = false;
            break;
        }
        if (got == 0) {
            ok = false;  // no progress: stop rather than spin
            break;
        }
        readBytes += got;
    }
    CloseHandle(hFile);

    if (!ok || readBytes != out.size()) {
        LOGE("readWholeFile: short read (%zu of %zu) from %s", readBytes, out.size(),
             path.c_str());
        out.clear();
        return false;
    }

    return true;
}

}  // namespace romm

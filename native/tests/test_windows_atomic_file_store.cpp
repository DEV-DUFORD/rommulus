// test_windows_atomic_file_store.cpp — Win32-native coverage of the
// security step in windows_atomic_file_store.cpp (Phase 2,
// plans/WINDOWS_IMPL.md section 5.5 step 5, "preserve or explicitly apply
// the destination ACL"):
//   - an existing destination's custom DACL is preserved verbatim across
//     ReplaceFileW writes (not reset to the directory default);
//   - an absent destination receives the safe current-user + SYSTEM
//     FILE_ALL_ACCESS DACL before it is exposed, and a subsequent replace
//     preserves it;
//   - security failures fail closed (a destination whose security metadata
//     cannot be read is NOT replaced) and every failure path removes the
//     temp file.
// Created only on WIN32 — the POSIX host suite keeps its existing selection
// untouched (test_atomic_file_store.cpp covers the shared write/read
// contract on every host). Privilege/filesystem-dependent sub-tests print
// "SKIP" and continue when the environment refuses them, like
// test_windows_path_security.
#include "atomic_file_store.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// GetSecurityInfo / SetFileSecurityW / SDDL + SID string converters:
// <aclapi.h>/<securitybaseapi.h>/<sddl.h> in MinGW-w64 (this project's
// Windows toolchain), <seapi.h>/<securitybase.h>/<sddl.h> in the SDK —
// branched on the compiler, as in windows_path_security.cpp.
#ifdef __MINGW32__
#include <aclapi.h>
#include <sddl.h>
#include <securitybaseapi.h>
#else
#include <sddl.h>
#include <seapi.h>
#include <securitybase.h>
#endif

#include "romm_test.h"

#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <optional>
#include <string>
#include <vector>

using romm::atomicWriteFile;
using romm::readWholeFile;

namespace {

// Strict wide -> UTF-8 for feeding OS-built paths back into the contract.
std::string toUtf8(const std::wstring& wide) {
    const auto utf8 = romm::win32::utf16ToUtf8(std::u16string(wide.begin(), wide.end()));
    return utf8 ? *utf8 : std::string();
}

bool writeFileBytes(const std::wstring& path, const void* data, size_t size) {
    const HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                 FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool ok = WriteFile(h, data, static_cast<DWORD>(size), &written, nullptr) != 0 &&
                    written == static_cast<DWORD>(size);
    FlushFileBuffers(h);
    CloseHandle(h);
    return ok;
}

// Applies a self-relative security descriptor parsed from SDDL to `path`
// (same helper shape as test_windows_path_security).
bool setSecurityFromSddl(const std::wstring& path, const wchar_t* sddl,
                         SECURITY_INFORMATION which) {
    PSECURITY_DESCRIPTOR sd = nullptr;
    if (ConvertStringSecurityDescriptorToSecurityDescriptorW(sddl, SDDL_REVISION_1, &sd,
                                                             nullptr) == FALSE) {
        return false;
    }
    const bool ok = SetFileSecurityW(path.c_str(), which, sd) != FALSE;
    LocalFree(sd);
    return ok;
}

// The current user's SID as a string (two-pass GetTokenInformation on the
// process token + ConvertSidToStringSidW, whose string is LocalFree'd), or
// nullopt.
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

// The file's DACL as an SDDL string: a READ_CONTROL handle + GetSecurityInfo
// (the OS-allocated self-relative descriptor is LocalFree'd) +
// ConvertSecurityDescriptorToStringSecurityDescriptorW (whose string is
// LocalFree'd too). nullopt when the DACL cannot be read.
std::optional<std::wstring> readDaclSddl(const std::wstring& path) {
    const HANDLE h = CreateFileW(path.c_str(), READ_CONTROL,
                                 FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return std::nullopt;
    PACL dacl = nullptr;
    PSECURITY_DESCRIPTOR sd = nullptr;
    const DWORD status = GetSecurityInfo(h, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                         nullptr, nullptr, &dacl, nullptr, &sd);
    CloseHandle(h);
    if (status != ERROR_SUCCESS || sd == nullptr) {
        if (sd != nullptr) LocalFree(sd);
        return std::nullopt;
    }
    std::optional<std::wstring> result;
    LPWSTR sddl = nullptr;
    if (ConvertSecurityDescriptorToStringSecurityDescriptorW(
            sd, SDDL_REVISION_1, DACL_SECURITY_INFORMATION, &sddl, nullptr) != 0 &&
        sddl != nullptr) {
        result = std::wstring(sddl);
        LocalFree(sddl);
    }
    LocalFree(sd);
    return result;
}

bool daclAllowsSid(const std::wstring& path, const std::wstring& sidString) {
    PSID expectedSid = nullptr;
    if (ConvertStringSidToSidW(sidString.c_str(), &expectedSid) == FALSE) return false;

    const HANDLE h = CreateFileW(path.c_str(), READ_CONTROL,
                                 FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE,
                                 nullptr, OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) {
        LocalFree(expectedSid);
        return false;
    }

    PACL dacl = nullptr;
    PSECURITY_DESCRIPTOR sd = nullptr;
    const DWORD status = GetSecurityInfo(h, SE_FILE_OBJECT, DACL_SECURITY_INFORMATION,
                                         nullptr, nullptr, &dacl, nullptr, &sd);
    CloseHandle(h);
    bool found = false;
    if (status == ERROR_SUCCESS && dacl != nullptr) {
        ACL_SIZE_INFORMATION info {};
        if (GetAclInformation(dacl, &info, sizeof(info), AclSizeInformation) != FALSE) {
            for (DWORD i = 0; i < info.AceCount && !found; ++i) {
                LPVOID ace = nullptr;
                if (GetAce(dacl, i, &ace) != FALSE &&
                    static_cast<ACE_HEADER*>(ace)->AceType == ACCESS_ALLOWED_ACE_TYPE) {
                    auto* allowed = static_cast<ACCESS_ALLOWED_ACE*>(ace);
                    found = EqualSid(&allowed->SidStart, expectedSid) != FALSE;
                }
            }
        }
    }
    if (sd != nullptr) LocalFree(sd);
    LocalFree(expectedSid);
    return found;
}

bool fileExists(const std::wstring& path) {
    return GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES;
}

// The file's size without opening it (a directory-level query, so it works
// even when the file's DACL denies the caller all access).
std::optional<long long> fileSizeWithoutAccess(const std::wstring& path) {
    WIN32_FILE_ATTRIBUTE_DATA info {};
    if (GetFileAttributesExW(path.c_str(), GetFileExInfoStandard, &info) == FALSE) {
        return std::nullopt;
    }
    return static_cast<long long>(info.nFileSizeLow);
}

std::wstring g_root;  // temp working root for this run

std::wstring makeSubdir(const std::wstring& name) {
    const std::wstring dir = g_root + L"\\" + name;
    if (CreateDirectoryW(dir.c_str(), nullptr) == FALSE &&
        GetLastError() != ERROR_ALREADY_EXISTS) {
        std::printf("fatal: cannot create %ls\n", dir.c_str());
        std::exit(2);
    }
    return dir;
}

// The directory-default DACL (what a plain CreateFileW file in `dir`
// inherits): the reference for the "not reset to the default" checks.
std::optional<std::wstring> directoryDefaultDacL(const std::wstring& dir) {
    const std::wstring probe = dir + L"\\dcl-probe.bin";
    if (!writeFileBytes(probe, "P", 1)) return std::nullopt;
    const auto sddl = readDaclSddl(probe);
    DeleteFileW(probe.c_str());
    return sddl;
}

// --- 1. Existing destination: the custom DACL survives ReplaceFileW. ------
void testPreservesExistingDacL() {
    const std::wstring dir = makeSubdir(L"preserve");
    const std::wstring dest = dir + L"\\save.bin";
    CHECK(writeFileBytes(dest, "OLD-CONTENT", 11));

    const auto userSid = currentUserSidString();
    if (!userSid) {
        std::printf("SKIP DACL-preservation sub-tests (cannot resolve the user SID)\n");
        return;
    }
    // A custom DACL: SYSTEM and the owner with FILE_ALL_ACCESS, and nothing
    // else — deliberately unlike the directory default (which additionally
    // carries Users/Administrators entries). The owner keeps full control so
    // the follow-up replace (which needs the DELETE right) can run in any
    // environment.
    const std::wstring custom = L"D:(A;;FA;;;S-1-5-18)(A;;FA;;;" + *userSid + L")";
    if (!setSecurityFromSddl(dest, custom.c_str(), DACL_SECURITY_INFORMATION)) {
        std::printf("SKIP DACL-preservation sub-tests (SetFileSecurity failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }

    // Pre-state: the custom DACL is in place before the write.
    const auto pre = readDaclSddl(dest);
    CHECK(pre.has_value());
    if (pre) {
        CHECK(daclAllowsSid(dest, L"S-1-5-18"));
        CHECK(daclAllowsSid(dest, *userSid));
        CHECK(!daclAllowsSid(dest, L"S-1-1-0"));  // no Everyone ACE
    }

    const std::vector<uint8_t> payload(512, 'R');
    CHECK(atomicWriteFile(toUtf8(dest), payload.data(), payload.size()));

    // The content was replaced (the ReplaceFileW path ran) and no temp
    // lingers.
    std::vector<uint8_t> back;
    CHECK(readWholeFile(toUtf8(dest), back));
    CHECK(back == payload);
    CHECK(!fileExists(dest + L".tmp"));

    // The custom DACL survived the replace: both ACEs still present, no
    // Everyone, and the serialized DACL differs from what a fresh file in
    // the same directory inherits (a reset-to-default would equal it).
    const auto post = readDaclSddl(dest);
    CHECK(post.has_value());
    if (post) {
        CHECK(daclAllowsSid(dest, L"S-1-5-18"));
        CHECK(daclAllowsSid(dest, *userSid));
        CHECK(!daclAllowsSid(dest, L"S-1-1-0"));
        CHECK(*post == *pre);
        const auto def = directoryDefaultDacL(dir);
        if (def) CHECK(*post != *def);
    }

    // A second replace preserves it again — preservation holds across
    // ReplaceFileW writes, not just the first one.
    const std::vector<uint8_t> payload2(256, 'S');
    CHECK(atomicWriteFile(toUtf8(dest), payload2.data(), payload2.size()));
    std::vector<uint8_t> back2;
    CHECK(readWholeFile(toUtf8(dest), back2));
    CHECK(back2 == payload2);
    const auto post2 = readDaclSddl(dest);
    CHECK(post2.has_value());
    if (post2) {
        CHECK(daclAllowsSid(dest, L"S-1-5-18"));
        CHECK(daclAllowsSid(dest, *userSid));
        CHECK(!daclAllowsSid(dest, L"S-1-1-0"));
        if (post) CHECK(*post2 == *post);
    }
}

// --- 2. Absent destination: the safe DACL is applied before exposure. -----
void testAbsentDestinationGetsSafeDacL() {
    const std::wstring dir = makeSubdir(L"fresh");
    const std::wstring dest = dir + L"\\new.bin";
    CHECK(!fileExists(dest));  // truly absent

    const auto userSid = currentUserSidString();
    if (!userSid) {
        std::printf("SKIP safe-DACL sub-tests (cannot resolve the user SID)\n");
        return;
    }

    const std::vector<uint8_t> payload(128, 'N');
    CHECK(atomicWriteFile(toUtf8(dest), payload.data(), payload.size()));
    CHECK(fileExists(dest));
    CHECK(!fileExists(dest + L".tmp"));

    std::vector<uint8_t> back;
    CHECK(readWholeFile(toUtf8(dest), back));
    CHECK(back == payload);

    // The exposed file carries the safe DACL: current user + SYSTEM, no
    // Everyone.
    const auto sddl = readDaclSddl(dest);
    CHECK(sddl.has_value());
    if (sddl) {
        CHECK(daclAllowsSid(dest, *userSid));
        CHECK(daclAllowsSid(dest, L"S-1-5-18"));
        CHECK(!daclAllowsSid(dest, L"S-1-1-0"));
    }

    // A subsequent write takes the REPLACE path (the destination now exists)
    // and must preserve the safe DACL rather than resetting it.
    const std::vector<uint8_t> payload2(64, 'M');
    CHECK(atomicWriteFile(toUtf8(dest), payload2.data(), payload2.size()));
    std::vector<uint8_t> back2;
    CHECK(readWholeFile(toUtf8(dest), back2));
    CHECK(back2 == payload2);
    const auto sddl2 = readDaclSddl(dest);
    CHECK(sddl2.has_value());
    if (sddl2) {
        CHECK(daclAllowsSid(dest, *userSid));
        CHECK(daclAllowsSid(dest, L"S-1-5-18"));
        CHECK(!daclAllowsSid(dest, L"S-1-1-0"));
        if (sddl) CHECK(*sddl2 == *sddl);
    }
}

// --- 3. Fail closed: a destination whose security cannot be read is not
//       replaced, and the temp is cleaned up. ------------------------------
void testFailClosedUnreadableDestination() {
    const std::wstring dir = makeSubdir(L"denied");
    const std::wstring dest = dir + L"\\secret.bin";
    const std::vector<char> original(11, 'X');
    CHECK(writeFileBytes(dest, original.data(), original.size()));

    // Lock the file down: re-point the owner at SYSTEM (allowed while we are
    // still the owner), then install a SYSTEM-only DACL (allowed while the
    // current DACL still grants us WRITE_DAC). After both, the caller has no
    // access to the file at all — READ_CONTROL included.
    if (!setSecurityFromSddl(dest, L"O:SY", OWNER_SECURITY_INFORMATION) ||
        !setSecurityFromSddl(dest, L"D:(A;;FA;;;S-1-5-18)", DACL_SECURITY_INFORMATION)) {
        std::printf("SKIP fail-closed sub-test (SetFileSecurity failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }

    const std::vector<uint8_t> payload(32, 'Y');
    CHECK(!atomicWriteFile(toUtf8(dest), payload.data(), payload.size()));

    // The temp was removed and the original file is still there, untouched
    // (size unchanged; the content is unreadable by design now, but the
    // failure happens BEFORE the replace, so nothing was written to it).
    CHECK(!fileExists(dest + L".tmp"));
    CHECK(fileExists(dest));
    const auto size = fileSizeWithoutAccess(dest);
    CHECK(size.has_value());
    if (size) CHECK(*size == static_cast<long long>(original.size()));

    // Best-effort cleanup: the caller still owns the DIRECTORY (DELETE_CHILD),
    // so the locked-down file goes away with it.
    std::error_code ec;
    std::filesystem::remove_all(std::filesystem::path(toUtf8(dir)), ec);
    if (ec) {
        std::printf("NOTE: could not clean up %ls (%s)\n", dir.c_str(), ec.message().c_str());
    }
}

// --- 4. Failure cleanup on the non-security failure paths. ----------------
void testFailureCleanup() {
    const std::wstring dir = makeSubdir(L"cleanup");

    // A write into a nonexistent directory fails; nothing is created.
    CHECK(!atomicWriteFile(toUtf8(dir + L"\\no_such_dir\\x.bin"), "AB", 2));
    CHECK(!fileExists(dir + L"\\no_such_dir"));

    // A write whose destination is a DIRECTORY fails (the probe cannot open
    // it for READ_CONTROL) and leaves no temp behind; the directory itself
    // is untouched.
    CHECK(!atomicWriteFile(toUtf8(dir), "AB", 2));
    CHECK(!fileExists(dir + L".tmp"));
    CHECK(fileExists(dir));
}

}  // namespace

int main() {
    wchar_t tempBase[MAX_PATH] {};
    const DWORD tempLen = GetTempPathW(MAX_PATH, tempBase);
    if (tempLen == 0 || tempLen >= MAX_PATH) {
        std::printf("SKIP all: cannot resolve the temp directory\n");
        return rommtest::finish("test_windows_atomic_file_store");
    }
    g_root = std::wstring(tempBase) + L"romm-atomic-file-store-" +
             std::to_wstring(static_cast<long long>(GetCurrentProcessId()));
    if (CreateDirectoryW(g_root.c_str(), nullptr) == FALSE &&
        GetLastError() != ERROR_ALREADY_EXISTS) {
        std::printf("SKIP all: cannot create %ls\n", g_root.c_str());
        return rommtest::finish("test_windows_atomic_file_store");
    }

    testPreservesExistingDacL();
    testAbsentDestinationGetsSafeDacL();
    testFailClosedUnreadableDestination();
    testFailureCleanup();

    // Best-effort cleanup of the whole temp tree.
    std::error_code ec;
    std::filesystem::remove_all(std::filesystem::path(toUtf8(g_root)), ec);

    return rommtest::finish("test_windows_atomic_file_store");
}

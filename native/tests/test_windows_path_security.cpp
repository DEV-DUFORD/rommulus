// test_windows_path_security.cpp — Win32-native coverage of
// windows_path_security.cpp (Phase 2, plans/WINDOWS_IMPL.md section 5.3):
// real-handle canonicalization against the live filesystem, reparse-point
// classification, and the NTFS owner/ACL replacement for the POSIX
// UID/world-write checks. Created only on WIN32 — the POSIX host suite keeps
// its existing selection untouched (test_player_validation.cpp covers the
// shared policy on POSIX).
//
// Privilege-dependent sub-tests (symlink creation, owner/DACL mutation)
// print "SKIP" and continue when the environment refuses them, so the test
// is meaningful both on a developer box (developer mode) and in CI.
#include "native/player/path_security.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
// SetFileSecurityW / ConvertStringSecurityDescriptorToSecurityDescriptorW /
// SDDL_REVISION_1: <securitybase.h> in the Windows SDK, <securitybaseapi.h>
// + <sddl.h> in MinGW-w64 (this project's Windows toolchain) — there is no
// <securitybase.h> in MinGW-w64.
#ifdef __MINGW32__
#include <sddl.h>
#include <securitybaseapi.h>
#else
#include <sddl.h>
#include <securitybase.h>
#endif

#include "romm_test.h"

#include <string>
#include <vector>

using romm::player::RequestFileStatus;
using romm::player::canonicalPath;
using romm::player::fileSize;
using romm::player::isSymlink;
using romm::player::requestFileSecurity;

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

// Case-insensitive prefix check for '/'-normalized ASCII path strings.
bool ciStartsWith(const std::string& haystack, const std::string& prefix) {
    if (haystack.size() < prefix.size()) return false;
    for (size_t i = 0; i < prefix.size(); ++i) {
        char a = haystack[i], b = prefix[i];
        if (a >= 'A' && a <= 'Z') a = static_cast<char>(a + ('a' - 'A'));
        if (b >= 'A' && b <= 'Z') b = static_cast<char>(b + ('a' - 'A'));
        if (a != b) return false;
    }
    return true;
}

bool endsWith(const std::string& haystack, const std::string& suffix) {
    if (haystack.size() < suffix.size()) return false;
    return haystack.compare(haystack.size() - suffix.size(), suffix.size(), suffix) == 0;
}

// Applies a self-relative security descriptor parsed from SDDL to `path`.
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

std::wstring g_root;  // temp working root for this run

void testCanonicalization() {
    CreateDirectoryW((g_root + L"\\a\\b").c_str(), nullptr);
    CHECK(writeFileBytes(g_root + L"\\a\\b\\rom.zip", "ROMDATA", 7));

    std::string err;
    const auto canonical = canonicalPath(toUtf8(g_root + L"\\a\\b\\rom.zip"), &err);
    CHECK(canonical.has_value());
    if (canonical) {
        // Absolute slash form, inside the temp root (case-insensitive: the
        // OS final path carries on-disk casing), ending in the file name.
        CHECK(ciStartsWith(*canonical, toUtf8(g_root)));
        CHECK(endsWith(*canonical, "/a/b/rom.zip"));
        // Idempotent: canonicalizing the canonical form is a fixed point.
        const auto again = canonicalPath(*canonical, &err);
        CHECK(again.has_value());
        if (again) CHECK(*again == *canonical);
    }

    // Not-yet-existing tail: re-appended after the deepest existing prefix.
    const auto deepDir = canonicalPath(toUtf8(g_root + L"\\a"), &err);
    CHECK(deepDir.has_value());
    const auto candidate = canonicalPath(toUtf8(g_root + L"\\a\\newdir\\candidate.srm"), &err);
    CHECK(candidate.has_value());
    if (candidate && deepDir) {
        CHECK(ciStartsWith(*candidate, *deepDir));
        CHECK(endsWith(*candidate, "/newdir/candidate.srm"));
    }

    // ".." and "." resolve before the handle walk.
    const auto dotted = canonicalPath(toUtf8(g_root + L"\\a\\..\\a\\b\\.\\rom.zip"), &err);
    CHECK(dotted.has_value());
    if (dotted && canonical) CHECK(*dotted == *canonical);

    // Relative input resolves against the working directory (mirrors POSIX).
    // This pins the currentDirectory() helper of windows_path_security.cpp:
    // the FIRST GetCurrentDirectoryW call returns the buffer size INCLUDING
    // the terminating NUL, the SECOND returns the length EXCLUDING it — so
    // the saved directory is exactly `cwdWritten` characters (checking
    // `== cwdNeeded` here would be false on every success and silently skip
    // this sub-test).
    DWORD cwdNeeded = GetCurrentDirectoryW(0, nullptr);
    std::vector<wchar_t> savedCwd(cwdNeeded ? cwdNeeded : 1);
    const DWORD cwdWritten =
        cwdNeeded != 0 ? GetCurrentDirectoryW(cwdNeeded, savedCwd.data()) : 0;
    if (cwdWritten != 0 && cwdWritten == cwdNeeded - 1) {
        if (SetCurrentDirectoryW(g_root.c_str()) != FALSE) {
            const auto relative = canonicalPath("a/b/rom.zip", &err);
            CHECK(relative.has_value());
            if (relative && canonical) CHECK(*relative == *canonical);
            SetCurrentDirectoryW(savedCwd.data());
        }
    }
}

void testRejections() {
    std::string err;
    CHECK(!canonicalPath(u8"\\\\.\\C:\\x", &err).has_value());            // device path
    CHECK(!canonicalPath(toUtf8(g_root + L"\\NUL"), &err).has_value());   // reserved name
    CHECK(!canonicalPath(toUtf8(g_root + L"\\a\\b\\rom.txt:stream"), &err)
                .has_value());                                            // ADS
    CHECK(!canonicalPath(toUtf8(g_root + L"\\a\\b\\rom."), &err).has_value());  // trailing dot
    CHECK(!canonicalPath("\xFF\xFE invalid utf-8", &err).has_value());    // strict boundary
}

void testIsSymlink() {
    const std::wstring target = g_root + L"\\sym-target.txt";
    const std::wstring link = g_root + L"\\sym-link.txt";
    CHECK(writeFileBytes(target, "T", 1));
    CHECK(!isSymlink(toUtf8(target)));

    if (CreateSymbolicLinkW(link.c_str(), target.c_str(), 0) == FALSE) {
        std::printf("SKIP symlink sub-tests (CreateSymbolicLinkW failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }
    CHECK(isSymlink(toUtf8(link)));
    CHECK(!isSymlink(toUtf8(target)));
    DeleteFileW(link.c_str());
}

void testRequestFileSecurity() {
    const std::wstring missing = g_root + L"\\no-such-file.json";
    CHECK(requestFileSecurity(toUtf8(missing)) == RequestFileStatus::MissingOrUnreadable);
    CHECK(requestFileSecurity(toUtf8(g_root + L"\\a")) == RequestFileStatus::NotRegularFile);

    const std::wstring regular = g_root + L"\\request-ok.json";
    CHECK(writeFileBytes(regular, "{}", 2));
    const auto okStatus = requestFileSecurity(toUtf8(regular));
    if (okStatus != RequestFileStatus::Ok) {
        std::printf("NOTE: default-ACL request file classified %d (expected Ok)\n",
                    static_cast<int>(okStatus));
    }
    CHECK(okStatus == RequestFileStatus::Ok);

    // World-writable equivalent: a DACL granting write to Everyone (WD).
    // GA = GENERIC_ALL, which the SDDL parser expands to FILE_ALL_ACCESS
    // (0x1F01FF) — it overlaps the specific write bits, so the classifier
    // must flag it.
    const std::wstring world = g_root + L"\\request-world.json";
    CHECK(writeFileBytes(world, "{}", 2));
    if (setSecurityFromSddl(world, L"D:(A;;GA;;;WD)", DACL_SECURITY_INFORMATION)) {
        CHECK(requestFileSecurity(toUtf8(world)) == RequestFileStatus::WorldWritable);
    } else {
        std::printf("SKIP world-writable sub-test (SetFileSecurity failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
    }

    // Conservative-mask pin: a DACL granting Everyone READ-ONLY (GR) plus
    // the owner full control (OW) is NOT world-writable — read grants must
    // not be classified as write grants. Pins the kWriteMask semantics in
    // windows_path_security.cpp (GENERIC_ALL/FILE_ALL_ACCESS deliberately
    // excluded: they overlap read bits and would false-positive here).
    const std::wstring readOnly = g_root + L"\\request-readonly.json";
    CHECK(writeFileBytes(readOnly, "{}", 2));
    if (setSecurityFromSddl(readOnly, L"D:(A;;FA;;;OW)(A;;GR;;;WD)", DACL_SECURITY_INFORMATION)) {
        CHECK(requestFileSecurity(toUtf8(readOnly)) == RequestFileStatus::Ok);
    } else {
        std::printf("SKIP read-only sub-test (SetFileSecurity failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
    }

    // Not-owned equivalent: owner re-pointed at SYSTEM. The previous step's
    // Everyone-full-control DACL gives us the WRITE_DAC right the ownership
    // change requires; on a file we created the owner is already us.
    const std::wstring foreign = g_root + L"\\request-foreign.json";
    CHECK(writeFileBytes(foreign, "{}", 2));
    if (setSecurityFromSddl(foreign, L"D:(A;;GA;;;WD)", DACL_SECURITY_INFORMATION) &&
        setSecurityFromSddl(foreign, L"O:SY", OWNER_SECURITY_INFORMATION)) {
        CHECK(requestFileSecurity(toUtf8(foreign)) == RequestFileStatus::NotOwnedByUser);
    } else {
        std::printf("SKIP owner sub-test (SetFileSecurity failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
    }

    // A device path is not a readable regular file, whatever the OS would do.
    CHECK(requestFileSecurity(u8"\\\\.\\C:\\x") == RequestFileStatus::MissingOrUnreadable);
}

void testFileSize() {
    const std::wstring sized = g_root + L"\\sized.bin";
    const std::vector<char> payload(1234, 'x');
    CHECK(writeFileBytes(sized, payload.data(), payload.size()));
    const auto size = fileSize(toUtf8(sized));
    CHECK(size.has_value());
    if (size) CHECK(*size == 1234);
    CHECK(!fileSize(toUtf8(g_root + L"\\missing.bin")).has_value());
}

// Hard links are NOT reparse points: a second name for the same file must
// classify exactly like the first (same owner, same DACL — hard links share
// the file object and its security descriptor), must not be reported as a
// symlink, and must report the same size. A planted hard link is therefore
// not an escape vector in the way a junction/symlink is.
void testHardLinks() {
    const std::wstring original = g_root + L"\\hardlink-orig.bin";
    const std::wstring link = g_root + L"\\hardlink-link.bin";
    const std::vector<char> payload(512, 'h');
    CHECK(writeFileBytes(original, payload.data(), payload.size()));

    if (CreateHardLinkW(link.c_str(), original.c_str(), nullptr) == FALSE) {
        std::printf("SKIP hard-link sub-tests (CreateHardLinkW failed: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }
    CHECK(!isSymlink(toUtf8(link)));
    CHECK(!isSymlink(toUtf8(original)));

    const auto linkStatus = requestFileSecurity(toUtf8(link));
    const auto origStatus = requestFileSecurity(toUtf8(original));
    CHECK(linkStatus == RequestFileStatus::Ok);
    CHECK(origStatus == RequestFileStatus::Ok);

    const auto linkSize = fileSize(toUtf8(link));
    const auto origSize = fileSize(toUtf8(original));
    CHECK(linkSize.has_value() && origSize.has_value());
    if (linkSize && origSize) CHECK(*linkSize == *origSize);

    DeleteFileW(link.c_str());
    DeleteFileW(original.c_str());
}

}  // namespace

int main() {
    wchar_t tempBase[MAX_PATH] {};
    const DWORD tempLen = GetTempPathW(MAX_PATH, tempBase);
    if (tempLen == 0 || tempLen >= MAX_PATH) {
        std::printf("SKIP all: cannot resolve the temp directory\n");
        return rommtest::finish("test_windows_path_security");
    }
    g_root = std::wstring(tempBase) + L"romm-path-security-" +
             std::to_wstring(static_cast<long long>(GetCurrentProcessId()));
    if (CreateDirectoryW(g_root.c_str(), nullptr) == FALSE &&
        GetLastError() != ERROR_ALREADY_EXISTS) {
        std::printf("SKIP all: cannot create %ls\n", g_root.c_str());
        return rommtest::finish("test_windows_path_security");
    }

    testCanonicalization();
    testRejections();
    testIsSymlink();
    testRequestFileSecurity();
    testFileSize();
    testHardLinks();
    return rommtest::finish("test_windows_path_security");
}

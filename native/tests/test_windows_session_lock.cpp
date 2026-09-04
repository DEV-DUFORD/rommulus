// test_windows_session_lock.cpp — Win32-native coverage of
// windows_session_lock.cpp (Phase 2, plans/WINDOWS_IMPL.md sections 5.1/5.4):
// real two-process LockFileEx behavior using the test executable itself as
// the "second player" (spawned via CreateProcessW in child mode), plus the
// fail-closed containment rejections and the Unicode-root path through the
// real CreateFileW/LockFileEx code. Created only on WIN32.
//
// Child mode:  test_windows_session_lock --romm-session-lock-child <id>
//   with the state root in the ROMM_TEST_LOCK_ROOT environment variable (a
//   wide env var, so Unicode roots survive — argv would be decoded through
//   the ANSI code page by a plain main()). The child acquires (or fails to
//   acquire) the lock, writes ready.txt ("ok"/"busy"), waits for release.txt,
//   and exits — the kernel releases the byte-range lock when the child's
//   handle closes at termination.
#include "native/player/session_lock.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "romm_test.h"

#include <string>
#include <vector>

using romm::player::sessionActive;

namespace {

std::string toUtf8(const std::wstring& wide) {
    const auto utf8 = romm::win32::utf16ToUtf8(std::u16string(wide.begin(), wide.end()));
    return utf8 ? *utf8 : std::string();
}

// The state root for this run (set in main before any sub-test).
std::wstring g_stateRoot;

bool writeFileText(const std::wstring& path, const char* text) {
    const HANDLE h = CreateFileW(path.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                                 FILE_ATTRIBUTE_NORMAL, nullptr);
    if (h == INVALID_HANDLE_VALUE) return false;
    DWORD written = 0;
    const bool ok = WriteFile(h, text, static_cast<DWORD>(std::string(text).size()), &written,
                              nullptr) != 0;
    CloseHandle(h);
    return ok;
}

bool fileExists(const std::wstring& path) {
    return GetFileAttributesW(path.c_str()) != INVALID_FILE_ATTRIBUTES;
}

std::string readFileText(const std::wstring& path) {
    const HANDLE h = CreateFileW(path.c_str(), GENERIC_READ,
                                 FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr,
                                 OPEN_EXISTING, 0, nullptr);
    if (h == INVALID_HANDLE_VALUE) return "";
    char buffer[64] {};
    DWORD read = 0;
    std::string out;
    if (ReadFile(h, buffer, sizeof(buffer), &read, nullptr) != 0) {
        out.assign(buffer, read);
    }
    CloseHandle(h);
    return out;
}

// Polls `path` up to ~timeoutMs; true when it appears.
bool waitForFile(const std::wstring& path, DWORD timeoutMs) {
    const DWORD start = GetTickCount();
    while (GetTickCount() - start < timeoutMs) {
        if (fileExists(path)) return true;
        Sleep(50);
    }
    return fileExists(path);
}

// The state root, passed wide through the environment (see child-mode note).
std::wstring childStateRoot() {
    const DWORD needed = GetEnvironmentVariableW(L"ROMM_TEST_LOCK_ROOT", nullptr, 0);
    if (needed == 0) return std::wstring();
    std::vector<wchar_t> buffer(needed);
    const DWORD written = GetEnvironmentVariableW(L"ROMM_TEST_LOCK_ROOT", buffer.data(), needed);
    if (written == 0 || written > needed - 1) return std::wstring();
    return std::wstring(buffer.begin(), buffer.begin() + written);
}

// The "second player": acquire the lock, signal readiness, hold until the
// parent signals release, then exit (kernel releases the lock at exit).
int childMode(const char* sessionId) {
    const std::wstring root = childStateRoot();
    if (root.empty()) return 2;
    const bool active = sessionActive(toUtf8(root), sessionId);
    writeFileText(root + L"\\ready.txt", active ? "busy" : "ok");
    waitForFile(root + L"\\release.txt", 15000);
    return active ? 1 : 0;
}

void testTwoProcessBehavior(const std::wstring& stateRoot) {
    const std::string rootUtf8 = toUtf8(stateRoot);

    // Spawn the child (this executable in child mode).
    wchar_t exePath[MAX_PATH] {};
    const DWORD exeLen = GetModuleFileNameW(nullptr, exePath, MAX_PATH);
    CHECK(exeLen != 0 && exeLen < MAX_PATH);
    if (exeLen == 0 || exeLen >= MAX_PATH) return;

    // The root travels wide through the environment (inherited by the child).
    CHECK(SetEnvironmentVariableW(L"ROMM_TEST_LOCK_ROOT", stateRoot.c_str()) != FALSE);
    std::wstring commandLine = L"\"" + std::wstring(exePath) +
                               L"\" --romm-session-lock-child sess";
    STARTUPINFOW si {};
    si.cb = sizeof(si);
    PROCESS_INFORMATION pi {};
    CHECK(CreateProcessW(nullptr, commandLine.data(), nullptr, nullptr, TRUE, 0, nullptr,
                         nullptr, &si, &pi) != FALSE);
    if (pi.hProcess == nullptr) return;

    // The child must have ACQUIRED the lock.
    const bool ready = waitForFile(stateRoot + L"\\ready.txt", 15000);
    CHECK(ready);
    if (ready) CHECK(readFileText(stateRoot + L"\\ready.txt") == "ok");

    // While the child holds it: this process must see the session as ACTIVE.
    CHECK(sessionActive(rootUtf8, "sess") == true);
    // A DIFFERENT session is unaffected...
    CHECK(sessionActive(rootUtf8, "other") == false);
    // ...and containment violations fail closed (reported as active).
    CHECK(sessionActive(rootUtf8, "../evil") == true);
    CHECK(sessionActive(rootUtf8, "a/b") == true);

    // Release: the child exits and the kernel drops the byte-range lock.
    writeFileText(stateRoot + L"\\release.txt", "go");
    const DWORD wait = WaitForSingleObject(pi.hProcess, 20000);
    CHECK(wait == WAIT_OBJECT_0);
    DWORD exitCode = 0;
    GetExitCodeProcess(pi.hProcess, &exitCode);
    CHECK(exitCode == 0);
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);

    // The .lock FILE is left in stateRoot on purpose (never unlinked)...
    CHECK(fileExists(stateRoot + L"\\sess.lock"));
    // ...but the LOCK itself is gone: a fresh acquisition now succeeds.
    CHECK(sessionActive(rootUtf8, "sess") == false);
}

void testUnicodeAndEdgeCases(const std::wstring& stateRoot) {
    // Unicode root (BMP + astral plane) through the real CreateFileW/
    // LockFileEx path: acquire, then re-acquire in-process must report the
    // session as already held by this process's retained handle.
    const std::wstring unicodeRoot = stateRoot + L"\\romm_テスト_🎮";
    CHECK(CreateDirectoryW(unicodeRoot.c_str(), nullptr) != FALSE ||
          GetLastError() == ERROR_ALREADY_EXISTS);
    const std::string unicodeUtf8 = toUtf8(unicodeRoot);
    CHECK(!unicodeUtf8.empty());
    CHECK(sessionActive(unicodeUtf8, "u1") == false);  // acquired
    CHECK(sessionActive(unicodeUtf8, "u1") == true);   // now held by us

    // Empty session id: nothing to lock (POSIX parity).
    CHECK(sessionActive(toUtf8(stateRoot), "") == false);
}

// A state root that does not exist yet: the probe open fails with
// ERROR_PATH_NOT_FOUND (3), not ERROR_FILE_NOT_FOUND (2). That is the
// "nothing to lock" case, NOT a containment failure — the CREATE_ALWAYS
// acquire open then fails harmlessly on its own and the session must NOT
// be claimed (mirrors the POSIX open() failure path: warn, proceed).
// Regression: the pre-fix code failed closed (returned true) here, which
// made a fresh install's first launch refuse to run.
void testMissingRoot() {
    const std::wstring missingRoot = g_stateRoot + L"\\no-such-state-root";
    CHECK(!fileExists(missingRoot));
    CHECK(sessionActive(toUtf8(missingRoot), "fresh") == false);
    // ...and the acquire attempt must not have created the root or any lock.
    CHECK(!fileExists(missingRoot));
    CHECK(!fileExists(missingRoot + L"\\fresh.lock"));
}

// A RELATIVE state root must resolve against the working directory through
// the session lock's own currentDirectory() helper. That helper's second
// GetCurrentDirectoryW call returns the length EXCLUDING the terminating
// NUL, so the absolute root must be built from that return value — a helper
// that reads the buffer's end embeds a NUL in the root (CreateFileW then
// truncates at the NUL and the lock lands in the wrong directory), and the
// lock file must end up in the REAL subdirectory.
void testRelativeRoot() {
    const std::wstring sub = g_stateRoot + L"\\rel-root";
    CHECK(CreateDirectoryW(sub.c_str(), nullptr) != FALSE ||
          GetLastError() == ERROR_ALREADY_EXISTS);

    // Save the working directory (same two-call idiom: the second call's
    // return excludes the NUL, so the saved path is exactly that many chars).
    std::wstring savedCwd;
    const DWORD cwdNeeded = GetCurrentDirectoryW(0, nullptr);
    if (cwdNeeded != 0) {
        savedCwd.resize(cwdNeeded);
        const DWORD cwdWritten = GetCurrentDirectoryW(cwdNeeded, savedCwd.data());
        if (cwdWritten != 0 && cwdWritten == cwdNeeded - 1) savedCwd.resize(cwdWritten);
    }
    if (savedCwd.empty() || SetCurrentDirectoryW(g_stateRoot.c_str()) == FALSE) {
        std::printf("SKIP relative-root sub-test (cannot set working directory)\n");
        return;
    }

    CHECK(sessionActive("rel-root", "rel") == false);  // acquired via the cwd helper
    CHECK(fileExists(sub + L"\\rel.lock"));            // in the REAL directory
    CHECK(sessionActive("rel-root", "rel") == true);   // now held by this process

    SetCurrentDirectoryW(savedCwd.c_str());
}

}  // namespace

int main(int argc, char** argv) {
    if (argc >= 3 && std::string(argv[1]) == "--romm-session-lock-child") {
        return childMode(argv[2]);
    }

    wchar_t tempBase[MAX_PATH] {};
    const DWORD tempLen = GetTempPathW(MAX_PATH, tempBase);
    if (tempLen == 0 || tempLen >= MAX_PATH) {
        std::printf("SKIP all: cannot resolve the temp directory\n");
        return rommtest::finish("test_windows_session_lock");
    }
    g_stateRoot = std::wstring(tempBase) + L"romm-session-lock-" +
                  std::to_wstring(static_cast<long long>(GetCurrentProcessId()));
    const std::wstring& stateRoot = g_stateRoot;
    if (CreateDirectoryW(stateRoot.c_str(), nullptr) == FALSE &&
        GetLastError() != ERROR_ALREADY_EXISTS) {
        std::printf("SKIP all: cannot create %ls\n", stateRoot.c_str());
        return rommtest::finish("test_windows_session_lock");
    }

    testTwoProcessBehavior(stateRoot);
    testUnicodeAndEdgeCases(stateRoot);
    testMissingRoot();
    testRelativeRoot();
    return rommtest::finish("test_windows_session_lock");
}

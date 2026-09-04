// test_windows_platform_paths.cpp — Win32-native coverage of
// windows_platform_paths.cpp (Phase 2, plans/WINDOWS_IMPL.md sections
// 3.4/5.1): GetModuleFileNameW executable resolution, the LocalAppData-based
// default trusted roots (known folder with environment fallback), and the
// Unicode round-trip integrity of the resolved paths. Created only on WIN32.
#include "native/player/platform_paths.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include "romm_test.h"

#include <cctype>
#include <string>
#include <vector>

using romm::player::DefaultTrustedRoots;
using romm::player::defaultTrustedRoots;
using romm::player::executablePath;
using romm::player::homeDirectory;

namespace {

std::string lower(std::string in) {
    for (char& c : in) c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    return in;
}

bool endsWith(const std::string& haystack, const std::string& suffix) {
    if (haystack.size() < suffix.size()) return false;
    return haystack.compare(haystack.size() - suffix.size(), suffix.size(), suffix) == 0;
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

std::wstring toW(const std::string& utf8) {
    const auto wide = romm::win32::utf8ToUtf16(utf8);
    return wide ? romm::win32::toWideString(*wide) : std::wstring();
}

// The %LOCALAPPDATA% environment value in canonical '/' form ("" when unset).
std::string localAppDataEnv() {
    const DWORD needed = GetEnvironmentVariableW(L"LOCALAPPDATA", nullptr, 0);
    if (needed == 0) return "";
    std::vector<wchar_t> buffer(needed);
    const DWORD written = GetEnvironmentVariableW(L"LOCALAPPDATA", buffer.data(), needed);
    if (written == 0 || written > needed - 1) return "";
    std::u16string unified;
    for (DWORD i = 0; i < written; ++i) {
        unified.push_back(buffer[i] == L'\\' ? u'/' : static_cast<char16_t>(buffer[i]));
    }
    while (unified.size() > 1 && unified.back() == u'/') unified.pop_back();
    const auto utf8 = romm::win32::utf16ToUtf8(unified);
    return utf8 ? *utf8 : "";
}

void testExecutablePath() {
    const auto exe = executablePath();
    CHECK(exe.has_value());
    if (!exe) return;
    // .u8string(): on Windows, path has NO implicit conversion to std::string.
    const std::string exeUtf8 = exe->u8string();
    // A real, existing .exe image on disk...
    CHECK(endsWith(lower(exeUtf8), ".exe"));
    const std::wstring wide = toW(exeUtf8);
    CHECK(!wide.empty());
    if (!wide.empty()) {
        CHECK(GetFileAttributesW(wide.c_str()) != INVALID_FILE_ATTRIBUTES);
    }
    // ...whose path round-trips the strict UTF-8/UTF-16 boundary intact.
    const auto wideStrict = romm::win32::utf8ToUtf16(exeUtf8);
    CHECK(wideStrict.has_value());
    if (wideStrict) {
        const auto back = romm::win32::utf16ToUtf8(*wideStrict);
        CHECK(back.has_value());
        if (back) CHECK(*back == exeUtf8);
    }
}

void testHomeDirectory() {
    const std::string home = homeDirectory();
    // %USERPROFILE% is set on every normal Windows session; "." is the
    // documented last-resort fallback.
    CHECK(!home.empty());
}

void testDefaultTrustedRoots() {
    const DefaultTrustedRoots roots = defaultTrustedRoots();
    CHECK(!roots.coreRoot.empty());
    CHECK(!roots.cacheRoot.empty());
    CHECK(!roots.dataRoot.empty());
    CHECK(!roots.stateRoot.empty());
    if (roots.coreRoot.empty()) return;

    // The plan's user-data layout (section 3.4), under LocalAppData.
    CHECK(endsWith(roots.coreRoot, "/RomMulus/data/cores"));
    CHECK(endsWith(roots.cacheRoot, "/RomMulus/cache"));
    CHECK(endsWith(roots.dataRoot, "/RomMulus/data"));
    CHECK(endsWith(roots.stateRoot, "/RomMulus/state"));

    // All four roots share the LocalAppData base. When the environment
    // exposes it (the normal case), compare against it directly; the known-
    // folder and the variable agree on a standard session.
    const std::string envBase = localAppDataEnv();
    if (!envBase.empty()) {
        CHECK(ciStartsWith(roots.coreRoot, envBase + "/RomMulus"));
        CHECK(ciStartsWith(roots.cacheRoot, envBase + "/RomMulus"));
        CHECK(ciStartsWith(roots.dataRoot, envBase + "/RomMulus"));
        CHECK(ciStartsWith(roots.stateRoot, envBase + "/RomMulus"));
    }
}

}  // namespace

int main() {
    testExecutablePath();
    testHomeDirectory();
    testDefaultTrustedRoots();
    return rommtest::finish("test_windows_platform_paths");
}

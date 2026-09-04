// test_windows_path_rules.cpp — adversarial path forms against the pure
// Win32 normalization/containment rules (native/platform/windows/
// include/native/platform/windows/path_rules.h), the fakeable seam of the
// Win32 path-security slice (Phase 2, plans/WINDOWS_IMPL.md section 5.3).
//
// Platform-neutral on purpose: the header is pure standard C++ over
// std::u16string, so this test runs on EVERY host (macOS/Linux CI today,
// Windows later) and pins the security rules without a Windows runner —
// exactly how test_utf16_conversion.cpp pins the encoding boundary.
#include <native/platform/windows/path_rules.h>
#include <native/platform/windows/utf16.h>

#include "romm_test.h"

#include <cstdio>
#include <string>

using romm::win32::isAbsoluteWin32;
using romm::win32::isWithinRootCaseInsensitive;
using romm::win32::normalizeWin32Path;
using romm::win32::toOsForm;

namespace {

// Normalizes a UTF-8 literal (converted strictly) and returns the canonical
// form, CHECKing that it succeeded.
std::u16string norm(const std::string& utf8) {
    const auto wide = romm::win32::utf8ToUtf16(utf8);
    CHECK(wide.has_value());
    if (!wide) return std::u16string();
    const auto result = normalizeWin32Path(*wide);
    if (!result) {
        std::fprintf(stderr, "  (unexpected rejection of: %s)\n", utf8.c_str());
    }
    CHECK(result.has_value());
    return result ? *result : std::u16string();
}

// CHECKs that the form is REJECTED and returns the error text.
std::string rejected(const std::string& utf8) {
    const auto wide = romm::win32::utf8ToUtf16(utf8);
    CHECK(wide.has_value());
    if (!wide) return "";
    std::string error;
    const auto result = normalizeWin32Path(*wide, &error);
    if (result) {
        std::fprintf(stderr, "  (expected rejection of: %s)\n", utf8.c_str());
    }
    CHECK(!result.has_value());
    return error;
}

void testDriveNormalization() {
    CHECK(norm("C:\\Games\\rom.zip") == u"C:/Games/rom.zip");
    CHECK(norm("C:/Games/rom.zip") == u"C:/Games/rom.zip");
    // Mixed separators.
    CHECK(norm("C:/Games\\sub/../rom.zip") == u"C:/Games/rom.zip");
    // "." and ".." resolution.
    CHECK(norm("C:\\a\\..\\b\\.\\c") == u"C:/b/c");
}

void testDriveRootAndCase() {
    CHECK(norm("C:\\") == u"C:");
    CHECK(norm("C:") == u"C:");
    CHECK(norm("c:\\games\\x") == u"C:/games/x");  // drive letter uppercased
}

void testLongPathPrefixes() {
    CHECK(norm(u8"\\\\?\\C:\\dir\\file") == u"C:/dir/file");
    CHECK(norm(u8"\\\\?\\UNC\\server\\share\\deep\\file") == u"//server/share/deep/file");
    // The prefix is stripped, so the security rules still apply underneath.
    CHECK(!rejected(u8"\\\\?\\C:\\NUL").empty());
}

void testUnc() {
    CHECK(norm(u8"\\\\server\\share\\a\\b") == u"//server/share/a/b");
    CHECK(norm("//server/share/a/b") == u"//server/share/a/b");
    // Bare share roots (with and without the long-path prefix).
    CHECK(norm(u8"\\\\server\\share") == u"//server/share");
    CHECK(norm(u8"\\\\?\\UNC\\s\\sh") == u"//s/sh");
    // ".." that stays below the share is fine...
    CHECK(norm(u8"\\\\server\\share\\a\\..\\x") == u"//server/share/x");
    // ...but a ".." directly under the share changes the SHARE — an escape.
    CHECK(rejected(u8"\\\\s\\sh\\..\\x") == "path escapes its volume root");
    CHECK(!rejected(u8"\\\\server").empty());  // server without a share is not a path
}

void testDevicePathsRejected() {
    CHECK(rejected(u8"\\\\.\\C:\\x") == "device path rejected");
    CHECK(rejected(u8"\\\\.\\pipe\\named-pipe") == "device path rejected");
    CHECK(rejected(u8"\\\\.\\PhysicalDrive0") == "device path rejected");
}

void testAlternateDataStreamsRejected() {
    CHECK(rejected("C:\\dir\\file.txt:stream") == "alternate data stream rejected");
    CHECK(rejected("C:\\dir\\my:weird") == "alternate data stream rejected");
    CHECK(rejected(u8"\\\\?\\C:\\f:s") == "alternate data stream rejected");
}

void testReservedNamesRejected() {
    // Bare reserved names, any case...
    CHECK(rejected("C:\\NUL") == "reserved device name in path: NUL");
    CHECK(rejected("c:\\nul") == "reserved device name in path: nul");
    // ...and WITH extensions (the conservative reading).
    CHECK(rejected("C:\\CON.txt") == "reserved device name in path: CON");
    CHECK(!rejected("C:\\COM1").empty());
    CHECK(!rejected("C:\\LPT9.bin").empty());
    // Near-misses are ordinary names.
    CHECK(norm("C:\\null") == u"C:/null");
    CHECK(norm("C:\\console.exe") == u"C:/console.exe");
}

void testTrailingDotsAndSpacesRejected() {
    CHECK(rejected("C:\\dir\\file.") == "trailing dot or space in path component");
    CHECK(rejected("C:\\dir\\file ") == "trailing dot or space in path component");
    // A LEADING dot is an ordinary name on NTFS — only trailing ones are
    // stripped by the OS, so only they are rejected.
    CHECK(norm("C:\\dir\\.hidden") == u"C:/dir/.hidden");
}

void testDotDotEscapeRejected() {
    CHECK(rejected("C:\\..\\Windows") == "path escapes its volume root");
    CHECK(rejected("C:\\a\\..\\..\\b") == "path escapes its volume root");
    // Legal ".." that stays under the root is fine.
    CHECK(norm("C:\\a\\b\\..\\c") == u"C:/a/c");
}

void testRelativeAndRootRelativeRejected() {
    CHECK(rejected("Games\\rom.zip") == "relative path; an absolute drive or UNC path is required");
    CHECK(rejected("\\foo\\bar") == "root-relative path is ambiguous without a drive letter");
    CHECK(rejected("") == "empty path");
}

void testContainmentCaseAndVolumeIdentity() {
    // Case-insensitive component containment.
    CHECK(isWithinRootCaseInsensitive(u"C:/games/rom.zip", u"C:/GAMES"));
    CHECK(isWithinRootCaseInsensitive(u"c:/Games/Rom.ZIP", u"C:/games"));
    CHECK(isWithinRootCaseInsensitive(u"C:/games", u"C:/games"));  // equality
    // Component-AWARE: string prefixes are not containment.
    CHECK(!isWithinRootCaseInsensitive(u"C:/gamer/rom.zip", u"C:/games"));
    CHECK(!isWithinRootCaseInsensitive(u"C:/games/sub", u"C:/games/sub2"));
    // Volume identity: different drive letters never contain each other...
    CHECK(!isWithinRootCaseInsensitive(u"D:/games/rom.zip", u"C:/games"));
    CHECK(!isWithinRootCaseInsensitive(u"d:/x", u"C:/"));
    // ...and UNC server AND share must both match (case-insensitively).
    CHECK(isWithinRootCaseInsensitive(u"//SERVER/share/a/b", u"//server/SHARE"));
    CHECK(!isWithinRootCaseInsensitive(u"//other/share/a", u"//server/share"));
    CHECK(!isWithinRootCaseInsensitive(u"//server/other/a", u"//server/share"));
    // A path above the root is not contained.
    CHECK(!isWithinRootCaseInsensitive(u"C:/games", u"C:/games/deeper"));
}

void testToOsForm() {
    CHECK(toOsForm(u"C:/a/b") == u"C:\\a\\b");
    CHECK(toOsForm(u"C:") == u"C:\\");  // bare drive root needs its separator
    CHECK(toOsForm(u"//server/share/a") == u"\\\\server\\share\\a");
}

void testIsAbsolute() {
    CHECK(isAbsoluteWin32(u"C:\\x"));
    CHECK(isAbsoluteWin32(u"c:/x"));
    CHECK(isAbsoluteWin32(u"\\\\server\\share"));
    CHECK(isAbsoluteWin32(u"\\\\?\\C:\\x"));
    CHECK(!isAbsoluteWin32(u"relative\\path"));
    CHECK(!isAbsoluteWin32(u"\\root-relative"));
}

void testLongPathsAndUnicode() {
    // Long paths (> MAX_PATH) are first-class: no length cap in the rules.
    std::string deep;
    for (int i = 0; i < 40; ++i) deep += "segment-" + std::to_string(i) + "\\";
    deep += "rom.zip";
    CHECK(deep.size() > 260);
    const auto wide = romm::win32::utf8ToUtf16("C:\\" + deep);
    CHECK(wide.has_value());
    if (wide) {
        const auto result = normalizeWin32Path(*wide);
        CHECK(result.has_value());
        if (result) {
            CHECK(result->size() > 260);
            CHECK(result->back() == u'p');  // ends in "...rom.zip"
        }
    }

    // Unicode components (BMP + astral plane) survive normalization intact.
    const std::string unicode = u8"C:\\RomMulus_テスト\\🎮\\rom.zip";
    const auto uwide = romm::win32::utf8ToUtf16(unicode);
    CHECK(uwide.has_value());
    if (uwide) {
        const auto unorm = normalizeWin32Path(*uwide);
        CHECK(unorm.has_value());
        if (unorm) {
            const auto back = romm::win32::utf16ToUtf8(*unorm);
            CHECK(back.has_value());
            if (back) CHECK(*back == u8"C:/RomMulus_テスト/🎮/rom.zip");
        }
    }
}

}  // namespace

int main() {
    testDriveNormalization();
    testDriveRootAndCase();
    testLongPathPrefixes();
    testUnc();
    testDevicePathsRejected();
    testAlternateDataStreamsRejected();
    testReservedNamesRejected();
    testTrailingDotsAndSpacesRejected();
    testDotDotEscapeRejected();
    testRelativeAndRootRelativeRejected();
    testContainmentCaseAndVolumeIdentity();
    testToOsForm();
    testIsAbsolute();
    testLongPathsAndUnicode();
    return rommtest::finish("test_windows_path_rules");
}

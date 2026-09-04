// path_rules.h — pure, OS-free Win32 path normalization and containment
// rules (Phase 2, plans/WINDOWS_IMPL.md sections 5.1/5.3).
//
// This header is the "fakeable seam" of the Win32 path-security slice: every
// rule that does not require a live filesystem lives here as pure standard
// C++ over std::u16string (the strict UTF-16 form produced by utf16.h), so
// native/tests can exercise the adversarial path forms on EVERY host — no
// Windows headers, no OS calls. The windows_*.cpp sources layer only the
// handle-based final-path resolution (GetFinalPathNameByHandleW) and the
// file-attribute/ACL classification on top of these rules.
//
// Canonical form produced here:
//   - forward slashes everywhere (Win32 APIs accept them, and the neutral
//     validation policy in validation.cpp compares with '/' separators);
//   - drive absolute: "C:/a/b"  — drive letter uppercased for stable volume
//     identity ("c:" and "C:" denote the same volume);
//   - drive root:     "C:"      (no trailing slash);
//   - UNC:            "//server/share/a/b";
//   - no ".", "..", or empty components; no trailing slash.
//
// Security rejections enforced by normalizeWin32PathParts() (fail closed —
// plans/WINDOWS_IMPL.md section 5.3): device namespace paths (\\.\*),
// alternate-data-stream colons, reserved device names (CON/PRN/AUX/NUL/
// COM1-9/LPT1-9, case-insensitive, with OR without an extension — the
// conservative reading: such names are indistinguishable from device access
// and are never needed by a trusted request/core/content/save/result path),
// trailing dots/spaces (NTFS silently strips them, so accepting them would
// compare one name against the different name the OS keeps), and ".." that
// would escape the volume root.
#pragma once

#include <cstddef>
#include <optional>
#include <string>
#include <vector>

namespace romm::win32 {

// The parsed/normalized shape of a Win32 absolute path: a volume (drive
// letter "C:" or UNC "//server/share") plus the components beneath it.
struct Win32PathParts {
    std::u16string volume;
    std::vector<std::u16string> components;
};

inline std::u16string joinWin32PathParts(const Win32PathParts& parts) {
    std::u16string out = parts.volume;
    for (const auto& component : parts.components) {
        out.push_back(u'/');
        out += component;
    }
    return out;
}

namespace detail {

inline char16_t toLowerC16(char16_t c) {
    return (c >= u'A' && c <= u'Z') ? static_cast<char16_t>(c + (u'a' - u'A')) : c;
}

inline char16_t toUpperC16(char16_t c) {
    return (c >= u'a' && c <= u'z') ? static_cast<char16_t>(c - (u'a' - u'A')) : c;
}

inline bool ciEquals(const std::u16string& a, const std::u16string& b) {
    if (a.size() != b.size()) return false;
    for (size_t i = 0; i < a.size(); ++i) {
        if (toLowerC16(a[i]) != toLowerC16(b[i])) return false;
    }
    return true;
}

inline bool isDriveLetter(char16_t c) {
    return (c >= u'A' && c <= u'Z') || (c >= u'a' && c <= u'z');
}

// Splits an already-normalized path ("C:/a/b", "C:", "//s/sh/a") back into
// its volume and components. Returns nullopt when the string is not in
// normalized form.
inline std::optional<Win32PathParts> splitNormalized(const std::u16string& path) {
    Win32PathParts parts;
    size_t rest = 0;
    if (path.size() >= 2 && isDriveLetter(path[0]) && path[1] == u':') {
        // Drive volume: "C:" optionally followed by "/components".
        parts.volume = path.substr(0, 2);
        rest = 2;
        if (rest < path.size() && path[rest] != u'/') return std::nullopt;
    } else if (path.size() >= 2 && path[0] == u'/' && path[1] == u'/') {
        // UNC volume: "//server/share" (optionally followed by "/components").
        const size_t serverEnd = path.find(u'/', 2);
        if (serverEnd == std::u16string::npos || serverEnd == 2) return std::nullopt;
        const size_t shareEnd = path.find(u'/', serverEnd + 1);
        if (shareEnd == serverEnd + 1) return std::nullopt;  // empty share
        if (shareEnd == std::u16string::npos) {
            parts.volume = path;          // bare share root: no components
            rest = path.size();
        } else {
            parts.volume = path.substr(0, shareEnd);
            rest = shareEnd;
        }
    } else {
        return std::nullopt;
    }
    for (size_t i = rest; i < path.size();) {
        if (path[i] == u'/') {
            ++i;
            continue;
        }
        const size_t end = path.find(u'/', i);
        const size_t len = end == std::u16string::npos ? path.size() - i : end - i;
        if (len == 0) return std::nullopt;
        parts.components.push_back(path.substr(i, len));
        i = end == std::u16string::npos ? path.size() : end;
    }
    return parts;
}

// True when `component`'s base name (the part before the first dot) is a
// reserved Win32 device name, case-insensitively. The check deliberately
// ignores extensions: "NUL.txt" is rejected along with "NUL", because a
// trusted-control path must never be able to address a device by any
// spelling, and the OS's own treatment of these names varies by API prefix.
inline bool isReservedDeviceName(const std::u16string& component) {
    const size_t dot = component.find(u'.');
    const std::u16string base =
        (dot == std::u16string::npos || dot == 0) ? component : component.substr(0, dot);
    static const char* kReserved[] = {"CON",   "PRN",   "AUX",   "NUL",
                                      "COM1",  "COM2",  "COM3",  "COM4",
                                      "COM5",  "COM6",  "COM7",  "COM8",
                                      "COM9",  "LPT1",  "LPT2",  "LPT3",
                                      "LPT4",  "LPT5",  "LPT6",  "LPT7",
                                      "LPT8",  "LPT9"};
    for (const char* reserved : kReserved) {
        std::u16string wide;
        for (const char* p = reserved; *p != '\0'; ++p)
            wide.push_back(static_cast<char16_t>(*p));
        if (ciEquals(base, wide)) return true;
    }
    return false;
}

}  // namespace detail

// Normalizes a Win32 path (mixed '/' and '\' separators allowed) into the
// canonical slash form described in the header comment. Relative paths are
// an error: callers must resolve them against the current directory first
// (the OS sources do this with GetCurrentDirectoryW). Returns std::nullopt
// and sets *error (UTF-8) on any rejection — see the header comment for the
// full list of fail-closed rejections.
inline std::optional<Win32PathParts> normalizeWin32PathParts(
    const std::u16string& in, std::string* error = nullptr) {
    auto reject = [](std::string* error, const std::string& message) -> std::optional<Win32PathParts> {
        if (error != nullptr) *error = message;
        return std::nullopt;
    };

    if (in.empty()) return reject(error, "empty path");

    // Unify separators: the Win32 APIs treat '/' and '\' interchangeably, so
    // mixed-separator input is normalized before any rule runs.
    std::u16string s;
    s.reserve(in.size());
    for (char16_t c : in) s.push_back(c == u'\\' ? u'/' : c);

    Win32PathParts parts;
    size_t rest = 0;

    // Device namespace: "\\.\C:", "\\.\pipe\name", "\\.\PhysicalDrive0" ...
    // After unification this is "//./". Never a file path — reject.
    if (s.size() >= 4 && s[0] == u'/' && s[1] == u'/' && s[2] == u'.' && s[3] == u'/') {
        return reject(error, "device path rejected");
    }

    // Where the UNC server name starts, when this is a UNC path at all:
    // 2 for plain "\\server\share", 8 for "\\?\UNC\server\share".
    size_t uncServerStart = std::u16string::npos;

    // Explicit long-path prefix: "\\?\C:\..." or "\\?\UNC\server\share\...".
    // The prefix carries no information beyond "this is absolute and may
    // exceed MAX_PATH", so it is stripped and the remainder re-parsed as a
    // plain drive/UNC path. ("\\?\" + anything else is invalid.)
    if (s.size() >= 4 && s[0] == u'/' && s[1] == u'/' && s[2] == u'?' && s[3] == u'/') {
        if (s.size() >= 8 && s[4] == u'U' && s[5] == u'N' && s[6] == u'C' && s[7] == u'/') {
            uncServerStart = 8;  // "\\?\UNC\server\share\..." -> UNC parse from here
        } else if (s.size() >= 6 && detail::isDriveLetter(s[4]) && s[5] == u':') {
            parts.volume = std::u16string{detail::toUpperC16(s[4]), u':'};
            rest = 6;
        } else {
            return reject(error, "invalid \\?\\ long-path form");
        }
    } else if (s.size() >= 2 && s[0] == u'/' && s[1] == u'/') {
        uncServerStart = 2;  // plain UNC: "\\server\share\..."
    } else if (s.size() >= 2 && detail::isDriveLetter(s[0]) && s[1] == u':') {
        parts.volume = std::u16string{detail::toUpperC16(s[0]), u':'};
        rest = 2;
    } else if (!s.empty() && s[0] == u'/') {
        return reject(error, "root-relative path is ambiguous without a drive letter");
    } else {
        return reject(error,
                      "relative path; an absolute drive or UNC path is required");
    }

    if (uncServerStart != std::u16string::npos) {
        // UNC: server and share are part of the volume; neither may be empty,
        // and ".." can never climb above the share (the component stack starts
        // below it — a ".." there would change the SHARE, which is an escape).
        const size_t serverEnd = s.find(u'/', uncServerStart);
        if (serverEnd == std::u16string::npos || serverEnd == uncServerStart) {
            return reject(error, "UNC path missing server");
        }
        const size_t shareEnd = s.find(u'/', serverEnd + 1);
        if (shareEnd == serverEnd + 1) {
            return reject(error, "UNC path missing share");
        }
        if (shareEnd == std::u16string::npos) {
            parts.volume = std::u16string(u"//") + s.substr(uncServerStart);  // bare share root
            rest = s.size();
        } else {
            parts.volume =
                std::u16string(u"//") + s.substr(uncServerStart, shareEnd - uncServerStart);
            rest = shareEnd;
        }
    } else if (rest < s.size() && s[rest] != u'/') {
        return reject(error, "invalid path after volume prefix");
    }

    // Component walk: lexical "." / ".." resolution plus the fail-closed
    // per-component security rules. Consecutive separators collapse (the OS
    // does the same); a trailing separator is dropped.
    for (size_t i = rest; i < s.size();) {
        if (s[i] == u'/') {
            ++i;
            continue;
        }
        const size_t end = s.find(u'/', i);
        const std::u16string component =
            s.substr(i, end == std::u16string::npos ? s.size() - i : end - i);
        if (component == u".") {
            // no-op
        } else if (component == u"..") {
            if (parts.components.empty()) {
                return reject(error, "path escapes its volume root");
            }
            parts.components.pop_back();
        } else {
            // ADS: a colon anywhere in a component names an alternate data
            // stream (the drive-letter colon was consumed with the volume).
            if (component.find(u':') != std::u16string::npos) {
                return reject(error, "alternate data stream rejected");
            }
            // Trailing dot/space: NTFS strips these on create/open, so the
            // name we would compare is not the name the OS keeps.
            const char16_t last = component.back();
            if (last == u'.' || last == u' ') {
                return reject(error, "trailing dot or space in path component");
            }
            if (detail::isReservedDeviceName(component)) {
                const size_t dot = component.find(u'.');
                const std::u16string base =
                    (dot == std::u16string::npos || dot == 0) ? component : component.substr(0, dot);
                std::string baseUtf8;
                for (char16_t c : base) baseUtf8.push_back(static_cast<char>(c));
                return reject(error, "reserved device name in path: " + baseUtf8);
            }
            parts.components.push_back(component);
        }
        i = end == std::u16string::npos ? s.size() : end;
    }

    if (parts.volume.empty()) return reject(error, "invalid path");
    return parts;
}

// Convenience wrapper: normalized canonical string form (see header).
inline std::optional<std::u16string> normalizeWin32Path(const std::u16string& in,
                                                        std::string* error = nullptr) {
    auto parts = normalizeWin32PathParts(in, error);
    if (!parts) return std::nullopt;
    return joinWin32PathParts(*parts);
}

// True when `normalizedPath` is `normalizedRoot` or lies beneath it, with
// BOTH the volume and every component compared case-insensitively. This is
// the containment primitive for a filesystem that does not pin case: raw
// string equality would REJECT a legitimate same-volume path whose spelling
// differs from the root's casing ("c:/GAMES/rom" under root "C:/games").
// Volume identity is its string-level form: the drive letter
// (case-insensitive) for drives, server AND share (case-insensitive) for
// UNC — "C:" never contains "D:", and "//SERVER/share" never contains
// "//server/other". Inputs must already be in normalized canonical form
// (from normalizeWin32Path*).
inline bool isWithinRootCaseInsensitive(const std::u16string& normalizedPath,
                                        const std::u16string& normalizedRoot) {
    auto pathParts = detail::splitNormalized(normalizedPath);
    auto rootParts = detail::splitNormalized(normalizedRoot);
    if (!pathParts || !rootParts) return false;
    if (!detail::ciEquals(pathParts->volume, rootParts->volume)) return false;
    if (pathParts->components.size() < rootParts->components.size()) return false;
    for (size_t i = 0; i < rootParts->components.size(); ++i) {
        if (!detail::ciEquals(pathParts->components[i], rootParts->components[i])) return false;
    }
    return true;
}

// The OS-boundary form of a normalized path for CreateFileW & co: backslash
// separators, and an explicit trailing separator on a bare drive root ("C:"
// alone means "current directory on C:", so the root must be "C:\"). UNC
// volumes become "\\server\share".
inline std::u16string toOsForm(const std::u16string& normalized) {
    auto parts = detail::splitNormalized(normalized);
    if (!parts) return normalized;  // defensive: pass through unchanged
    std::u16string out;
    if (parts->volume.size() >= 2 && parts->volume[0] == u'/' && parts->volume[1] == u'/') {
        // UNC volume "//server/share" -> "\\server\share".
        const size_t serverEnd = parts->volume.find(u'/', 2);
        out = std::u16string(u"\\\\") +
              parts->volume.substr(2, serverEnd - 2) + u"\\" +
              parts->volume.substr(serverEnd + 1);
    } else {
        out = parts->volume;
    }
    for (const auto& component : parts->components) {
        out.push_back(u'\\');
        out += component;
    }
    if (out.size() == 2 && out[1] == u':') out.push_back(u'\\');  // bare drive root
    return out;
}

// True when the RAW input (mixed separators, not yet normalized) is absolute
// in a form the Win32 APIs understand: "X:\...", "X:/...", "\\server\share",
// or an explicit "\\?\"/`\\.\` prefix. Relative and root-relative inputs are
// NOT absolute — callers must resolve them against the current directory
// first. (The normalization step then decides which of these forms is legal.)
inline bool isAbsoluteWin32(const std::u16string& in) {
    if (in.size() >= 4 && in[0] == u'\\' && in[1] == u'\\') return true;
    if (in.size() >= 2 && detail::isDriveLetter(in[0]) && in[1] == u':') return true;
    return false;
}

}  // namespace romm::win32

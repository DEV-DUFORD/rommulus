// utf16.h — strict UTF-8 <-> UTF-16 conversion for the Win32 boundary
// (Phase 2, plans/WINDOWS_IMPL.md section 5.5).
//
// Every RomMulus path and string is UTF-8 inside the process; Win32 APIs
// take UTF-16. This header is where the two encodings meet: each
// windows_*.cpp source converts at its boundary through these functions and
// fails closed on invalid input instead of handing a lossy or partial
// conversion to the OS.
//
// The logic is pure standard C++ — no Windows headers — so it compiles on
// every host, and native/tests exercises it on POSIX as well
// (test_utf16_conversion.cpp) rather than trusting it only in Windows CI.
#pragma once

#include <cstdint>
#include <optional>
#include <string>

namespace romm::win32 {

// Decodes `in` as UTF-8 into a sequence of UTF-16 code units.
//
 // Strict: returns std::nullopt on ANY invalid input — truncated sequences,
 // stray continuation bytes, invalid lead bytes, overlong encodings, encoded
 // surrogates (the FULL range U+D800..U+DFFF: both the high half
 // U+D800..U+DBFF and the low half U+DC00..U+DFFF), and code points above
 // U+10FFFF. There is no partial-result mode: if any byte is invalid the
 // whole conversion fails, so callers can never pass half-converted bytes to
 // a Win32 API.
inline std::optional<std::u16string> utf8ToUtf16(const std::string& in) {
    std::u16string out;
    out.reserve(in.size());
    const auto* bytes = reinterpret_cast<const unsigned char*>(in.data());
    size_t i = 0;
    while (i < in.size()) {
        const unsigned char lead = bytes[i];
        uint32_t cp = 0;
        int extra = 0;
        if (lead < 0x80) {
            cp = lead;
        } else if ((lead & 0xE0) == 0xC0) {
            cp = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            cp = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            cp = lead & 0x07;
            extra = 3;
        } else {
            return std::nullopt;  // stray continuation byte or invalid lead
        }
        for (int k = 0; k < extra; ++k) {
            if (i + 1 + k >= in.size()) return std::nullopt;  // truncated sequence
            const unsigned char cont = bytes[i + 1 + k];
            if ((cont & 0xC0) != 0x80) return std::nullopt;
            cp = (cp << 6) | (cont & 0x3F);
        }
        i += 1 + extra;
        // Reject overlong encodings: each form must encode its minimum value.
        if ((extra == 1 && cp < 0x80) || (extra == 2 && cp < 0x800) ||
            (extra == 3 && cp < 0x10000)) {
            return std::nullopt;
        }
        // Reject encoded surrogates and out-of-range code points. The whole
        // surrogate range is invalid UTF-8: a lone high half (U+D800..U+DBFF)
        // OR a lone low half (U+DC00..U+DFFF) must both fail — accepting the
        // low half would emit a lone low surrogate into the wide string.
        if (cp >= 0xD800 && cp <= 0xDFFF) return std::nullopt;
        if (cp > 0x10FFFF) return std::nullopt;
        if (cp < 0x10000) {
            out.push_back(static_cast<char16_t>(cp));
        } else {
            const uint32_t v = cp - 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (v >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (v & 0x3FF)));
        }
    }
    return out;
}

// Encodes `in` (a sequence of UTF-16 code units) as UTF-8.
//
// Strict: returns std::nullopt on a lone surrogate — a high surrogate not
// immediately followed by its low half, or a low surrogate with no preceding
// high half. There is no partial-result mode.
inline std::optional<std::string> utf16ToUtf8(const std::u16string& in) {
    std::string out;
    out.reserve(in.size() * 2);
    for (size_t i = 0; i < in.size(); ++i) {
        const uint32_t unit = static_cast<uint32_t>(in[i]);
        uint32_t cp = unit;
        if (unit >= 0xD800 && unit <= 0xDBFF) {
            // High surrogate: must be immediately followed by its low half.
            if (i + 1 >= in.size()) return std::nullopt;
            const uint32_t low = static_cast<uint32_t>(in[i + 1]);
            if (low < 0xDC00 || low > 0xDFFF) return std::nullopt;
            cp = 0x10000 + ((unit - 0xD800) << 10) + (low - 0xDC00);
            ++i;
        } else if (unit >= 0xDC00 && unit <= 0xDFFF) {
            return std::nullopt;  // low surrogate with no preceding high half
        }
        if (cp < 0x80) {
            out.push_back(static_cast<char>(cp));
        } else if (cp < 0x800) {
            out.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp < 0x10000) {
            out.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            out.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            out.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return out;
}

// The Win32 APIs take wchar_t strings (16-bit on Windows). Copies a UTF-16
// code unit sequence into that boundary form. This is a real copy, not a
// pointer reinterpret: char16_t* and wchar_t* are distinct types in C++
// even where both are 16-bit wide, so std::u16string::c_str() must never be
// passed to an LPCWSTR parameter.
inline std::wstring toWideString(const std::u16string& in) {
#if defined(_WIN32)
    // Win32 ABI boundary: every Windows target (MSVC and MinGW-w64 UCRT64)
    // uses a 16-bit wchar_t, the same width as char16_t, so this copy is a
    // value-preserving reinterpretation of each code unit. Pin that at the
    // exact point where UTF-16 meets the OS boundary form — if a future
    // toolchain ever produced a 32-bit wchar_t for a Windows target, this
    // must fail the build rather than silently widen every path.
    //
    // NOTE: MinGW-w64 *Windows targets* use 16-bit wchar_t even though the
    // host GCC may default to 32-bit wchar_t; do NOT add -fshort-wchar here
    // to force it — that flag is for cross-ABI data sharing, is not needed
    // for a native Windows target, and would corrupt the host-side pure
    // logic in this header. The assert above is the correct guard.
    static_assert(sizeof(wchar_t) == 2, "Win32 ABI requires a 16-bit wchar_t");
#endif
    return std::wstring(in.begin(), in.end());
}

}  // namespace romm::win32

// test_utf16_conversion.cpp — strict UTF-8 <-> UTF-16 boundary conversion
// (native/platform/windows/utf16.h): the pure logic behind every Win32 path
// crossing in the Windows platform sources (Phase 2, plans/WINDOWS_IMPL.md
// section 5.5). Platform-neutral: it runs on every host, so the strict
// invalid-input handling is exercised in CI even before a Windows runner
// builds the native slice it guards.
#include <native/platform/windows/utf16.h>

#include "romm_test.h"

#include <string>

using romm::win32::utf16ToUtf8;
using romm::win32::utf8ToUtf16;

namespace {

void testAsciiRoundTrip() {
    const std::string in = "C:\\cores\\test_core.dll";
    auto wide = utf8ToUtf16(in);
    CHECK(wide.has_value());
    CHECK_EQ(wide->size(), in.size());
    auto back = utf16ToUtf8(*wide);
    CHECK(back.has_value());
    CHECK(back == in);
}

void testMultibyteRoundTrip() {
    // "séve-€-core" — 2-byte and 3-byte UTF-8 sequences.
    const std::string in = "s\xc3\xa9ve-\xe2\x82\xac-core";
    auto wide = utf8ToUtf16(in);
    CHECK(wide.has_value());
    auto back = utf16ToUtf8(*wide);
    CHECK(back.has_value());
    CHECK(back == in);
}

void testNonBmpSurrogatePair() {
    const std::string in = "\xF0\x9F\x98\x80";  // U+1F600, 4-byte UTF-8
    auto wide = utf8ToUtf16(in);
    CHECK(wide.has_value());
    CHECK_EQ(wide->size(), static_cast<size_t>(2));
    CHECK_EQ(static_cast<unsigned>((*wide)[0]), 0xD83Du);  // high surrogate
    CHECK_EQ(static_cast<unsigned>((*wide)[1]), 0xDE00u);  // low surrogate
    auto back = utf16ToUtf8(*wide);
    CHECK(back.has_value());
    CHECK(back == in);
}

void testEmpty() {
    auto wide = utf8ToUtf16(std::string());
    CHECK(wide.has_value());
    CHECK(wide->empty());
    auto back = utf16ToUtf8(*wide);
    CHECK(back.has_value());
    CHECK(back->empty());
}

void testInvalidUtf8Rejected() {
    // Truncated sequences, stray continuation bytes, invalid lead bytes.
    const std::string truncated2 = "a\xC3";
    const std::string truncated4 = "a\xF0\x9F\x98";
    const std::string strayContinuation = "\x80";
    const std::string badLead = "ok\xFF";
    const std::string badSecondByte = "\xC3\x28";
    CHECK(!utf8ToUtf16(truncated2).has_value());
    CHECK(!utf8ToUtf16(truncated4).has_value());
    CHECK(!utf8ToUtf16(strayContinuation).has_value());
    CHECK(!utf8ToUtf16(badLead).has_value());
    CHECK(!utf8ToUtf16(badSecondByte).has_value());
}

void testOverlongAndSurrogateRejected() {
    // Overlong encodings and encoded surrogates are invalid UTF-8.
    const std::string overlongNul2 = "\xC0\x80";        // NUL as 2 bytes
    const std::string overlongNul3 = "\xE0\x80\x80";    // NUL as 3 bytes
    const std::string encodedSurrogate = "\xED\xA0\x80";  // U+D800
    CHECK(!utf8ToUtf16(overlongNul2).has_value());
    CHECK(!utf8ToUtf16(overlongNul3).has_value());
    CHECK(!utf8ToUtf16(encodedSurrogate).has_value());
}

void testFullSurrogateRangeRejected() {
    // The ENTIRE surrogate range U+D800..U+DFFF is invalid UTF-8: the high
    // half (U+D800..U+DBFF) AND the low half (U+DC00..U+DFFF). Each boundary
    // value is spelled as its 3-byte UTF-8 encoding. The decoder must reject
    // a lone low surrogate just as it rejects a lone high one — otherwise a
    // low-surrogate byte sequence would emit a lone low char16_t unit.
    const std::string highStart = "\xED\xA0\x80";  // U+D800
    const std::string highEnd   = "\xED\xAF\xBF";  // U+DBFF
    const std::string lowStart  = "\xED\xB0\x80";  // U+DC00
    const std::string lowMid    = "\xED\xBE\xA0";  // U+DEA0 (a UTF-16 low half)
    const std::string lowEnd    = "\xED\xBF\xBF";  // U+DFFF
    CHECK(!utf8ToUtf16(highStart).has_value());
    CHECK(!utf8ToUtf16(highEnd).has_value());
    CHECK(!utf8ToUtf16(lowStart).has_value());
    CHECK(!utf8ToUtf16(lowMid).has_value());
    CHECK(!utf8ToUtf16(lowEnd).has_value());
    // The code points immediately OUTSIDE the range are valid and round-trip.
    CHECK(utf8ToUtf16(std::string("\xED\x9F\xBF")).has_value());  // U+D7FF
    CHECK(utf8ToUtf16(std::string("\xEE\x80\x80")).has_value());  // U+E000
}

void testCodePointRange() {
    // U+10FFFF is the last valid code point; U+110000 is not.
    CHECK(utf8ToUtf16(std::string("\xF4\x8F\xBF\xBF")).has_value());  // U+10FFFF
    CHECK(!utf8ToUtf16(std::string("\xF4\x90\x80\x80")).has_value());  // U+110000
}

void testStrictNoPartialResult() {
    // A valid prefix followed by an invalid byte must fail the WHOLE
    // conversion — never a half-converted result.
    CHECK(!utf8ToUtf16(std::string("C:\\ok\xC3")).has_value());
}

void testLoneSurrogatesRejected() {
    // Surrogate code points cannot appear as universal-character-names in
    // string literals, so the units are spelled as hex escapes.
    const std::u16string loneHigh = u"\xD800";
    const std::u16string loneLow = u"\xDC00";
    const std::u16string highAtEnd = u"a\xD800";
    const std::u16string highThenHigh = u"\xD800\xD800";
    CHECK(!utf16ToUtf8(loneHigh).has_value());
    CHECK(!utf16ToUtf8(loneLow).has_value());
    CHECK(!utf16ToUtf8(highAtEnd).has_value());
    CHECK(!utf16ToUtf8(highThenHigh).has_value());
}

void testValidPairEncodes() {
    const std::u16string pair = u"\xD83D\xDE00";  // U+1F600 as a surrogate pair
    auto out = utf16ToUtf8(pair);
    CHECK(out.has_value());
    CHECK_EQ(out->size(), static_cast<size_t>(4));
    CHECK(out == std::string("\xF0\x9F\x98\x80"));
}

}  // namespace

int main() {
    testAsciiRoundTrip();
    testMultibyteRoundTrip();
    testNonBmpSurrogatePair();
    testEmpty();
    testInvalidUtf8Rejected();
    testOverlongAndSurrogateRejected();
    testFullSurrogateRangeRejected();
    testCodePointRange();
    testStrictNoPartialResult();
    testLoneSurrogatesRejected();
    testValidPairEncodes();
    return rommtest::finish("test_utf16_conversion");
}

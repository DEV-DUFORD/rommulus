// test_pixel_format.cpp — the neutral engine's row conversion
// (romm::video::convertRow) for all three source formats plus the
// default-arm fallback, checked against hand-computed RGBA_8888 values.
#include "pixel_format.h"

#include "romm_test.h"

#include <cstdint>

using romm::video::convertRow;

namespace {

// Packs RGBA the way the engine does: byte order R,G,B,A in memory, i.e.
// 0xAABBGGRR as a 32-bit value.
uint32_t rgba(uint8_t r, uint8_t g, uint8_t b) {
    return 0xFF000000u | (static_cast<uint32_t>(b) << 16) |
           (static_cast<uint32_t>(g) << 8) | static_cast<uint32_t>(r);
}

void testZeroRGB1555() {
    // 0RRRRRGGGGGBBBBB: pure channels at full 5-bit range.
    const uint16_t src[4] = {0x0000, 0x7C00, 0x03E0, 0x001F};
    uint32_t dst[4] = {0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD};
    convertRow(RETRO_PIXEL_FORMAT_0RGB1555, src, dst, 4);
    CHECK_EQ(dst[0], rgba(0, 0, 0));
    CHECK_EQ(dst[1], rgba(255, 0, 0));  // r5=31
    CHECK_EQ(dst[2], rgba(0, 255, 0));  // g5=31
    CHECK_EQ(dst[3], rgba(0, 0, 255));  // b5=31

    // Mid values: 0x3FEF = (15<<10)|(31<<5)|15 = r5=15, g5=31, b5=15
    // -> 15*255/31=123, 255, 123.
    const uint16_t mid[2] = {0x3FEF, 0x0208};
    uint32_t midDst[2] = {};
    convertRow(RETRO_PIXEL_FORMAT_0RGB1555, mid, midDst, 2);
    CHECK_EQ(midDst[0], rgba(123, 255, 123));
    // 0x0208 = g5=16, b5=8 -> 16*255/31=131, 8*255/31=65.
    CHECK_EQ(midDst[1], rgba(0, 131, 65));
}

void testRGB565() {
    // RRRRRGGGGGGBBBBB: pure channels at full range.
    const uint16_t src[4] = {0x0000, 0xF800, 0x07E0, 0x001F};
    uint32_t dst[4] = {0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD};
    convertRow(RETRO_PIXEL_FORMAT_RGB565, src, dst, 4);
    CHECK_EQ(dst[0], rgba(0, 0, 0));
    CHECK_EQ(dst[1], rgba(255, 0, 0));  // r5=31
    CHECK_EQ(dst[2], rgba(0, 255, 0));  // g6=63
    CHECK_EQ(dst[3], rgba(0, 0, 255));  // b5=31

    // Mid value: 0x0420 = g6=33 -> 33*255/63=133.
    const uint16_t mid[1] = {0x0420};
    uint32_t midDst[1] = {};
    convertRow(RETRO_PIXEL_FORMAT_RGB565, mid, midDst, 1);
    CHECK_EQ(midDst[0], rgba(0, 133, 0));
}

void testXRGB8888() {
    // 0xXXRRGGBB: for 0x11223344 the color channels are R=0x22, G=0x33,
    // B=0x44 (the top byte 0x11 is X); for 0xDEADBEEF they are
    // R=0xAD, G=0xBE, B=0xEF.
    const uint32_t src[4] = {0x00000000u, 0xFFFFFFFFu, 0x11223344u, 0xDEADBEEFu};
    uint32_t dst[4] = {0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD, 0xDEADDEAD};
    convertRow(RETRO_PIXEL_FORMAT_XRGB8888, src, dst, 4);
    CHECK_EQ(dst[0], rgba(0, 0, 0));
    CHECK_EQ(dst[1], rgba(255, 255, 255));
    CHECK_EQ(dst[2], rgba(0x22, 0x33, 0x44));
    CHECK_EQ(dst[3], rgba(0xAD, 0xBE, 0xEF));  // the X byte is dropped
}

void testDefaultArmFallsBackToXRGB8888() {
    // Any non-0RGB1555/non-RGB565 value takes the 32-bit path, matching the
    // default arm of the switch the former AndroidVideoSink used.
    const uint32_t src[1] = {0x11223344u};
    uint32_t dst[1] = {0xDEADDEAD};
    convertRow(RETRO_PIXEL_FORMAT_UNKNOWN, src, dst, 1);
    CHECK_EQ(dst[0], rgba(0x22, 0x33, 0x44));
}

void testZeroColumns() {
    // cols == 0 is a no-op that must not touch dst.
    uint32_t dst[2] = {0xDEADDEAD, 0xBEEFBEEF};
    const uint16_t src16[1] = {0xFFFF};
    const uint32_t src32[1] = {0x11223344u};
    convertRow(RETRO_PIXEL_FORMAT_0RGB1555, src16, dst, 0);
    CHECK_EQ(dst[0], 0xDEADDEAD);
    CHECK_EQ(dst[1], 0xBEEFBEEF);
    convertRow(RETRO_PIXEL_FORMAT_XRGB8888, src32, dst, 0);
    CHECK_EQ(dst[0], 0xDEADDEAD);
    CHECK_EQ(dst[1], 0xBEEFBEEF);
}

}  // namespace

int main() {
    testZeroRGB1555();
    testRGB565();
    testXRGB8888();
    testDefaultArmFallsBackToXRGB8888();
    testZeroColumns();
    return rommtest::finish("test_pixel_format");
}

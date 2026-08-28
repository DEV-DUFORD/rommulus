// test_overlay_pixels.cpp — pitch/row handling for the hardware-core pause
// overlay's pixel copy (romm::player::copyPackedRgbaRows). Exercises the
// exact conversion SdlHardwareContext::setOverlayFrame() applies to an
// off-window-rasterized SDL_Surface (which may pad each row wider than
// width * 4) before uploading a tightly packed buffer to a GL texture.
#include "native/player/overlay_pixels.h"

#include "romm_test.h"

#include <cstdint>
#include <cstring>
#include <vector>

using romm::player::copyPackedRgbaRows;

namespace {

// A tightly packed source (srcPitch == width * 4) copies through unchanged.
void testTightlyPackedSourceCopiesUnchanged() {
    const unsigned width = 2;
    const unsigned height = 2;
    const uint8_t src[2 * 2 * 4] = {
        1, 2, 3, 4,       5, 6, 7, 8,        // row 0
        9, 10, 11, 12,    13, 14, 15, 16,    // row 1
    };
    uint8_t dst[2 * 2 * 4] = {};
    copyPackedRgbaRows(dst, src, width, height, width * 4);
    CHECK(std::memcmp(dst, src, sizeof(src)) == 0);
}

// A padded source (e.g. an SDL_Surface row-aligned wider than the pixel
// data) must have its trailing padding bytes skipped per row, not copied
// into the tightly packed destination.
void testPaddedSourceSkipsRowPadding() {
    const unsigned width = 2;   // 2 pixels * 4 bytes = 8 bytes of real data
    const unsigned height = 2;
    const size_t srcPitch = 12;  // 4 bytes of padding per row
    std::vector<uint8_t> src(srcPitch * height, 0xEE);  // 0xEE = padding sentinel
    // Row 0 real pixel data.
    src[0] = 1; src[1] = 2; src[2] = 3; src[3] = 4;
    src[4] = 5; src[5] = 6; src[6] = 7; src[7] = 8;
    // Row 1 real pixel data (offset by srcPitch).
    src[srcPitch + 0] = 9;  src[srcPitch + 1] = 10; src[srcPitch + 2] = 11; src[srcPitch + 3] = 12;
    src[srcPitch + 4] = 13; src[srcPitch + 5] = 14; src[srcPitch + 6] = 15; src[srcPitch + 7] = 16;

    std::vector<uint8_t> dst(width * 4 * height, 0);
    copyPackedRgbaRows(dst.data(), src.data(), width, height, srcPitch);

    const uint8_t expected[2 * 2 * 4] = {
        1, 2, 3, 4,     5, 6, 7, 8,
        9, 10, 11, 12,  13, 14, 15, 16,
    };
    CHECK(std::memcmp(dst.data(), expected, sizeof(expected)) == 0);
    // No padding sentinel bytes (0xEE) leaked into the packed destination.
    for (uint8_t byte : dst) {
        CHECK(byte != 0xEE);
    }
}

// A single-row, single-pixel frame is the smallest valid overlay geometry
// (also exercises pitch == width * 4 exactly, the common case).
void testSinglePixelFrame() {
    const uint8_t src[4] = {200, 150, 100, 255};
    uint8_t dst[4] = {};
    copyPackedRgbaRows(dst, src, 1, 1, 4);
    CHECK(std::memcmp(dst, src, sizeof(src)) == 0);
}

// Invalid inputs (null pointers or zero geometry) must not crash or write
// through a null destination — mirrors the video sink's "duplicate the last
// frame" no-op convention for a degenerate frame.
void testInvalidInputsAreNoOps() {
    uint8_t dst[4] = {0xAA, 0xAA, 0xAA, 0xAA};
    const uint8_t src[4] = {1, 2, 3, 4};
    copyPackedRgbaRows(nullptr, src, 1, 1, 4);
    copyPackedRgbaRows(dst, nullptr, 1, 1, 4);
    copyPackedRgbaRows(dst, src, 0, 1, 4);
    copyPackedRgbaRows(dst, src, 1, 0, 4);
    const uint8_t untouched[4] = {0xAA, 0xAA, 0xAA, 0xAA};
    CHECK(std::memcmp(dst, untouched, sizeof(dst)) == 0);
}

}  // namespace

int main() {
    testTightlyPackedSourceCopiesUnchanged();
    testPaddedSourceSkipsRowPadding();
    testSinglePixelFrame();
    testInvalidInputsAreNoOps();
    return rommtest::finish("test_overlay_pixels");
}

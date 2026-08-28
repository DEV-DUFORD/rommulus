// pixel_format.cpp — see pixel_format.h.
#include "pixel_format.h"

namespace romm::video {

namespace {

// 0RRRRRGGGGGBBBBB (bit 15 unused)
void convertRow0RGB1555(const uint16_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        const uint16_t p = src[x];
        const uint8_t r = static_cast<uint8_t>(((p >> 10) & 0x1F) * 255 / 31);
        const uint8_t g = static_cast<uint8_t>(((p >> 5) & 0x1F) * 255 / 31);
        const uint8_t b = static_cast<uint8_t>((p & 0x1F) * 255 / 31);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

// RRRRRGGGGGGBBBBB
void convertRowRGB565(const uint16_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        const uint16_t p = src[x];
        const uint8_t r = static_cast<uint8_t>(((p >> 11) & 0x1F) * 255 / 31);
        const uint8_t g = static_cast<uint8_t>(((p >> 5) & 0x3F) * 255 / 63);
        const uint8_t b = static_cast<uint8_t>((p & 0x1F) * 255 / 31);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

// Native XRGB8888: 0xXXRRGGBB as a 32-bit int (byte order depends on
// endianness, but the bitfield extraction below is endianness-safe).
void convertRowXRGB8888(const uint32_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        const uint32_t p = src[x];
        const uint8_t r = static_cast<uint8_t>((p >> 16) & 0xFF);
        const uint8_t g = static_cast<uint8_t>((p >> 8) & 0xFF);
        const uint8_t b = static_cast<uint8_t>(p & 0xFF);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

}  // namespace

void convertRow(enum retro_pixel_format format, const void* src, void* dst,
                uint32_t cols) {
    switch (format) {
        case RETRO_PIXEL_FORMAT_0RGB1555:
            convertRow0RGB1555(static_cast<const uint16_t*>(src),
                               static_cast<uint32_t*>(dst), cols);
            break;
        case RETRO_PIXEL_FORMAT_RGB565:
            convertRowRGB565(static_cast<const uint16_t*>(src),
                             static_cast<uint32_t*>(dst), cols);
            break;
        case RETRO_PIXEL_FORMAT_XRGB8888:
        default:
            convertRowXRGB8888(static_cast<const uint32_t*>(src),
                               static_cast<uint32_t*>(dst), cols);
            break;
    }
}

}  // namespace romm::video

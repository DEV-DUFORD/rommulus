// overlay_pixels.h — pure pixel-copy helper for the hardware-core pause
// overlay (see sdl_hardware_context.h's setOverlayFrame()).
//
// The pause overlay is rasterized off-window into an SDL_Surface (main.cpp's
// HardwareOverlayRaster), whose row stride ("pitch") may pad each row wider
// than width * 4 bytes. SdlHardwareContext uploads the frame to a GL texture
// via glTexSubImage2D, which wants a tightly packed buffer. This is
// deliberately a free function independent of SDL/GL so the pitch/row
// handling can be unit-tested without a live GL context or display.
#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace romm::player {

// Copies `height` rows of `width` RGBA8888 pixels from `src` (row stride
// `srcPitch` bytes — may equal, or exceed, width * 4 for a padded source
// surface) into `dst`, tightly packed at width * 4 bytes per row. `dst` must
// point to at least static_cast<size_t>(width) * 4 * height bytes. A no-op
// if src/dst is null or width/height is zero (mirrors the "duplicate the
// last frame" convention used elsewhere in the video path).
inline void copyPackedRgbaRows(
    void* dst, const void* src, unsigned width, unsigned height, size_t srcPitch) {
    if (dst == nullptr || src == nullptr || width == 0 || height == 0) return;
    const size_t rowBytes = static_cast<size_t>(width) * 4;
    const uint8_t* s = static_cast<const uint8_t*>(src);
    uint8_t* d = static_cast<uint8_t*>(dst);
    for (unsigned row = 0; row < height; ++row) {
        std::memcpy(d + row * rowBytes, s + row * srcPitch, rowBytes);
    }
}

}  // namespace romm::player

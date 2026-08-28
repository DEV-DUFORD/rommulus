// pixel_format.h — software pixel-format conversion for the neutral engine.
//
// Phase 7 Wave 9b (LINUX_X64.md section 14, Phase 7 item 11): extracted from
// AndroidVideoSink.cpp so the conversion is unit-testable on the host and
// reusable by the future Linux player. The per-pixel math is byte-identical
// to the former AndroidVideoSink row converters — same output for the same
// input.
#pragma once

#include "libretro.h"

#include <cstdint>

namespace romm::video {

// Converts one row of `cols` source pixels in `format` to RGBA_8888
// (byte order R,G,B,A — the format ANativeWindow buffers use regardless of
// device endianness) at `dst`. `src` and `dst` never overlap.
//
// RETRO_PIXEL_FORMAT_0RGB1555 and RETRO_PIXEL_FORMAT_RGB565 are 16-bit
// sources; RETRO_PIXEL_FORMAT_XRGB8888 is a 32-bit source. Any other value
// falls through to the 32-bit XRGB8888 path, matching the default arm of
// the switch the former AndroidVideoSink::submitFrame used per row.
void convertRow(enum retro_pixel_format format, const void* src, void* dst,
                uint32_t cols);

}  // namespace romm::video

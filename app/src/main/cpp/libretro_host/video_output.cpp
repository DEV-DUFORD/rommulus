#include "video_output.h"

#include <algorithm>
#include <cstring>

namespace romm {

namespace {

// Converts one row of `cols` source pixels in `format` to RGBA_8888
// (byte order R,G,B,A — the format ANativeWindow buffers use regardless of
// device endianness) at `dst`. `dst` and `src` never overlap.
void convertRow0RGB1555(const uint16_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        const uint16_t p = src[x];
        // 0RRRRRGGGGGBBBBB (bit 15 unused)
        const uint8_t r = static_cast<uint8_t>(((p >> 10) & 0x1F) * 255 / 31);
        const uint8_t g = static_cast<uint8_t>(((p >> 5) & 0x1F) * 255 / 31);
        const uint8_t b = static_cast<uint8_t>((p & 0x1F) * 255 / 31);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

void convertRowRGB565(const uint16_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        const uint16_t p = src[x];
        // RRRRRGGGGGGBBBBB
        const uint8_t r = static_cast<uint8_t>(((p >> 11) & 0x1F) * 255 / 31);
        const uint8_t g = static_cast<uint8_t>(((p >> 5) & 0x3F) * 255 / 63);
        const uint8_t b = static_cast<uint8_t>((p & 0x1F) * 255 / 31);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

void convertRowXRGB8888(const uint32_t* src, uint32_t* dst, uint32_t cols) {
    for (uint32_t x = 0; x < cols; ++x) {
        // Native XRGB8888: 0xXXRRGGBB as a 32-bit int (byte order depends on
        // endianness, but the bitfield extraction below is endianness-safe).
        const uint32_t p = src[x];
        const uint8_t r = static_cast<uint8_t>((p >> 16) & 0xFF);
        const uint8_t g = static_cast<uint8_t>((p >> 8) & 0xFF);
        const uint8_t b = static_cast<uint8_t>(p & 0xFF);
        dst[x] = static_cast<uint32_t>(r) | (static_cast<uint32_t>(g) << 8) |
                 (static_cast<uint32_t>(b) << 16) | (0xFFu << 24);
    }
}

}  // namespace

VideoOutput::~VideoOutput() {
    detachWindow();
}

void VideoOutput::attachWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
    }
    window_ = window;
}

void VideoOutput::detachWindow() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
}

void VideoOutput::submitFrame(const void* data, unsigned width, unsigned height, size_t pitch,
                               enum retro_pixel_format format) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ == nullptr) return;
    if (data == nullptr) return;  // frame duplication: keep showing the last posted buffer
    if (width == 0 || height == 0) return;

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        // Transient failure (e.g. compositor busy). Drop this frame rather
        // than block the emulation thread for an unbounded time.
        return;
    }

    const auto* srcBytes = static_cast<const uint8_t*>(data);
    auto* dstBytes = static_cast<uint8_t*>(buffer.bits);
    const uint32_t rows = static_cast<uint32_t>(buffer.height);
    const uint32_t cols = static_cast<uint32_t>(buffer.width);
    const size_t dstStrideBytes = static_cast<size_t>(buffer.stride) * 4;
    if (rows == 0 || cols == 0) {
        ANativeWindow_unlockAndPost(window_);
        return;
    }

    if (rows == height && cols == width) {
        for (uint32_t y = 0; y < rows; ++y) {
            const uint8_t* srcRow = srcBytes + static_cast<size_t>(y) * pitch;
            auto* dstRow = reinterpret_cast<uint32_t*>(
                dstBytes + static_cast<size_t>(y) * dstStrideBytes);
            switch (format) {
                case RETRO_PIXEL_FORMAT_0RGB1555:
                    convertRow0RGB1555(reinterpret_cast<const uint16_t*>(srcRow), dstRow, cols);
                    break;
                case RETRO_PIXEL_FORMAT_RGB565:
                    convertRowRGB565(reinterpret_cast<const uint16_t*>(srcRow), dstRow, cols);
                    break;
                case RETRO_PIXEL_FORMAT_XRGB8888:
                default:
                    convertRowXRGB8888(reinterpret_cast<const uint32_t*>(srcRow), dstRow, cols);
                    break;
            }
        }
        ANativeWindow_unlockAndPost(window_);
        return;
    }

    if (scaledSourceWidth_ != width || scaledSourceHeight_ != height ||
        scaledDestinationWidth_ != cols || scaledDestinationHeight_ != rows) {
        scaledSourceWidth_ = width;
        scaledSourceHeight_ = height;
        scaledDestinationWidth_ = cols;
        scaledDestinationHeight_ = rows;
        scaledSourceXs_.resize(cols);
        scaledSourceYs_.resize(rows);
        convertedSourceRow_.resize(width);
        expandedDestinationRow_.resize(cols);

        for (uint32_t x = 0; x < cols; ++x) {
            scaledSourceXs_[x] = std::min(
                static_cast<uint32_t>((static_cast<uint64_t>(x) * width) / cols),
                static_cast<uint32_t>(width - 1));
        }
        for (uint32_t y = 0; y < rows; ++y) {
            scaledSourceYs_[y] = std::min(
                static_cast<uint32_t>((static_cast<uint64_t>(y) * height) / rows),
                static_cast<uint32_t>(height - 1));
        }
    }

    uint32_t previousSourceY = height;
    for (uint32_t y = 0; y < rows; ++y) {
        const uint32_t sourceY = scaledSourceYs_[y];
        if (sourceY != previousSourceY) {
            const uint8_t* srcRow = srcBytes + static_cast<size_t>(sourceY) * pitch;
            switch (format) {
                case RETRO_PIXEL_FORMAT_0RGB1555:
                    convertRow0RGB1555(
                        reinterpret_cast<const uint16_t*>(srcRow),
                        convertedSourceRow_.data(),
                        width);
                    break;
                case RETRO_PIXEL_FORMAT_RGB565:
                    convertRowRGB565(
                        reinterpret_cast<const uint16_t*>(srcRow),
                        convertedSourceRow_.data(),
                        width);
                    break;
                case RETRO_PIXEL_FORMAT_XRGB8888:
                default:
                    convertRowXRGB8888(
                        reinterpret_cast<const uint32_t*>(srcRow),
                        convertedSourceRow_.data(),
                        width);
                    break;
            }
            for (uint32_t x = 0; x < cols; ++x) {
                expandedDestinationRow_[x] = convertedSourceRow_[scaledSourceXs_[x]];
            }
            previousSourceY = sourceY;
        }
        auto* dstRow = reinterpret_cast<uint32_t*>(dstBytes + static_cast<size_t>(y) * dstStrideBytes);
        std::memcpy(dstRow, expandedDestinationRow_.data(), static_cast<size_t>(cols) * sizeof(uint32_t));
    }

    ANativeWindow_unlockAndPost(window_);
}

}  // namespace romm

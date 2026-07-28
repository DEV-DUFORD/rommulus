#include "video_output.h"

#include <android/log.h>
#include <algorithm>

#define LOG_TAG "romm_video_output"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

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
    // Force ANativeWindow_setBuffersGeometry to run again on the next frame,
    // since a fresh Surface has no geometry set yet.
    lastBufferWidth_ = 0;
    lastBufferHeight_ = 0;
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

    const auto bufferWidth = static_cast<int32_t>(width);
    const auto bufferHeight = static_cast<int32_t>(height);
    if (bufferWidth != lastBufferWidth_ || bufferHeight != lastBufferHeight_) {
        if (ANativeWindow_setBuffersGeometry(window_, bufferWidth, bufferHeight,
                                              WINDOW_FORMAT_RGBA_8888) != 0) {
            LOGE("ANativeWindow_setBuffersGeometry failed for %dx%d", bufferWidth, bufferHeight);
            return;
        }
        lastBufferWidth_ = bufferWidth;
        lastBufferHeight_ = bufferHeight;
    }

    ANativeWindow_Buffer buffer;
    if (ANativeWindow_lock(window_, &buffer, nullptr) != 0) {
        // Transient failure (e.g. compositor busy). Drop this frame rather
        // than block the emulation thread for an unbounded time.
        return;
    }

    const auto* srcBytes = static_cast<const uint8_t*>(data);
    auto* dstBytes = static_cast<uint8_t*>(buffer.bits);
    const uint32_t rows = std::min(static_cast<uint32_t>(height), static_cast<uint32_t>(buffer.height));
    const uint32_t cols = std::min(static_cast<uint32_t>(width), static_cast<uint32_t>(buffer.width));
    const size_t dstStrideBytes = static_cast<size_t>(buffer.stride) * 4;

    for (uint32_t y = 0; y < rows; ++y) {
        const uint8_t* srcRow = srcBytes + static_cast<size_t>(y) * pitch;
        auto* dstRow = reinterpret_cast<uint32_t*>(dstBytes + static_cast<size_t>(y) * dstStrideBytes);
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
}

}  // namespace romm

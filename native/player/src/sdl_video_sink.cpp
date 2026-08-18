// sdl_video_sink.cpp — SDL3 software video output for the Linux player.
//
// SDL3 API notes (verified against the installed SDL3 headers):
//   - SDL_CreateRenderer(window, name) — SDL3 dropped the SDL2 flags
//     parameter; pass nullptr for the default renderer.
//   - SDL_SetRenderLogicalPresentation(renderer, w, h, mode) replaces
//     SDL2's SDL_RenderSetLogicalSize; modes are
//     SDL_LOGICAL_PRESENTATION_INTEGER_SCALE / _LETTERBOX.
//   - SDL_RenderLine/SDL_RenderTexture take float coordinates/rects in
//     SDL3 (not SDL2's int/SDL_Rect).
//   - SDL_SetWindowFullscreen(window, bool) takes a plain bool in SDL3.
//
// Scanline overlay: the spec's suggested 1x2 texture stretched over the
// frame would produce two H/2 bands, not 1px lines, so this implementation
// builds a 1xH texture (one column, one row per logical pixel row,
// alternating transparent / semi-transparent black) and stretches it over
// the frame — the "simple and correct" variant, rebuilt only when the
// geometry changes.
#include "native/player/sdl_video_sink.h"

#include <SDL3/SDL.h>

#include <native/engine/LogSink.h>
#include "pixel_format.h"

#include <algorithm>
#include <cmath>

namespace romm::player {

namespace {

constexpr const char* kTag = "sdl_video";
constexpr uint8_t kScanlineAlpha = 96;  // ~37% black

// Builds the 1xH scanline overlay texture on `renderer`. Returns nullptr
// on failure.
SDL_Texture* buildScanlineTexture(SDL_Renderer* renderer, unsigned height) {
    SDL_Texture* texture =
        SDL_CreateTexture(renderer, SDL_PIXELFORMAT_RGBA32,
                          SDL_TEXTUREACCESS_STREAMING, 1, static_cast<int>(height));
    if (texture == nullptr) {
        return nullptr;
    }
    // SDL3 textures default to SDL_BLENDMODE_NONE, which ignores alpha and
    // would render the transparent rows as solid black bars; enable blending
    // so the semi-transparent rows dim the frame instead.
    if (!SDL_SetTextureBlendMode(texture, SDL_BLENDMODE_BLEND)) {
        SDL_DestroyTexture(texture);
        return nullptr;
    }
    std::vector<uint8_t> pixels(height * 4);
    for (unsigned row = 0; row < height; ++row) {
        const uint8_t alpha = (row % 2 == 1) ? kScanlineAlpha : 0;
        pixels[row * 4 + 0] = 0;
        pixels[row * 4 + 1] = 0;
        pixels[row * 4 + 2] = 0;
        pixels[row * 4 + 3] = alpha;
    }
    if (!SDL_UpdateTexture(texture, nullptr, pixels.data(), 4)) {
        SDL_DestroyTexture(texture);
        return nullptr;
    }
    return texture;
}

}  // namespace

void SdlVideoSink::submitFrame(const void* data, unsigned width, unsigned height,
                               size_t pitch, enum retro_pixel_format format) {
    // A null framebuffer is "duplicate the last frame" (RETRO_ENVIRONMENT_
    // GET_CAN_DUPE): a deliberate no-op that must not clear the screen.
    if (data == nullptr || width == 0 || height == 0) {
        return;
    }

    std::lock_guard<std::mutex> lock(mutex_);

    if (width != width_ || height != height_) {
        width_ = width;
        height_ = height;
        staging_.resize(static_cast<size_t>(width) * height * 4);
    }

    // Row-by-row conversion to RGBA8888 (byte order R,G,B,A). `pitch` is
    // the core's row stride in bytes; the staging buffer is packed at
    // width*4.
    const uint8_t* src = static_cast<const uint8_t*>(data);
    uint8_t* dst = staging_.data();
    for (unsigned row = 0; row < height; ++row) {
        romm::video::convertRow(format, src + static_cast<size_t>(row) * pitch,
                                dst + static_cast<size_t>(row) * width * 4, width);
    }

    frameReady_ = true;
}

void SdlVideoSink::attachWindow(romm::video::NativeWindowHandle window) {
    std::lock_guard<std::mutex> lock(mutex_);
    destroySurfaceLocked();

    window_ = static_cast<SDL_Window*>(window);
    if (window_ == nullptr) {
        return;
    }

    renderer_ = SDL_CreateRenderer(window_, nullptr);
    if (renderer_ == nullptr) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_CreateRenderer failed: ") +
                                  SDL_GetError());
        window_ = nullptr;
        return;
    }

    applyLogicalPresentationLocked();
}

void SdlVideoSink::detachWindow() {
    std::lock_guard<std::mutex> lock(mutex_);
    destroySurfaceLocked();
}

void SdlVideoSink::destroySurfaceLocked() {
    if (scanlineTexture_ != nullptr) {
        SDL_DestroyTexture(scanlineTexture_);
        scanlineTexture_ = nullptr;
        scanlineTextureHeight_ = 0;
    }
    if (texture_ != nullptr) {
        SDL_DestroyTexture(texture_);
        texture_ = nullptr;
        textureWidth_ = 0;
        textureHeight_ = 0;
    }
    if (renderer_ != nullptr) {
        SDL_DestroyRenderer(renderer_);
        renderer_ = nullptr;
    }
    window_ = nullptr;
    frameReady_ = false;
}

void SdlVideoSink::setDisplayAspectRatio(double aspectRatio) {
    std::lock_guard<std::mutex> lock(mutex_);
    displayAspectRatio_ = aspectRatio > 0.0 && std::isfinite(aspectRatio)
            ? aspectRatio
            : 0.0;
    presentationDirty_ = true;
}

void SdlVideoSink::applyLogicalPresentationLocked() {
    if (renderer_ == nullptr || width_ == 0 || height_ == 0) {
        return;
    }

    const double aspect = displayAspectRatio_ > 0.0
            ? displayAspectRatio_
            : static_cast<double>(width_) / height_;
    const int logicalHeight = static_cast<int>(height_);
    const int logicalWidth = std::max(
            1, static_cast<int>(std::lround(aspect * logicalHeight)));
    SDL_SetRenderLogicalPresentation(
            renderer_, logicalWidth, logicalHeight,
            integerScaling_ ? SDL_LOGICAL_PRESENTATION_INTEGER_SCALE
                            : SDL_LOGICAL_PRESENTATION_LETTERBOX);
    presentationDirty_ = false;
}

bool SdlVideoSink::present() {
    // Snapshot of the geometry, taken under the lock: submitFrame() may
    // change width_/height_ concurrently, and the scanline section below
    // runs OUTSIDE the mutex (a data race on height_ otherwise).
    unsigned frameWidth = 0;
    unsigned frameHeight = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!frameReady_ || renderer_ == nullptr || width_ == 0 || height_ == 0) {
            // No new frame (or no window yet): leave the screen untouched.
            // frameReady_ stays set so the frame is presented once a
            // window is attached.
            return false;
        }
        frameWidth = width_;
        frameHeight = height_;

        // (Re)create the streaming texture when the geometry changed.
        if (texture_ == nullptr || textureWidth_ != width_ ||
            textureHeight_ != height_) {
            if (texture_ != nullptr) {
                SDL_DestroyTexture(texture_);
                texture_ = nullptr;
            }
            texture_ = SDL_CreateTexture(renderer_, SDL_PIXELFORMAT_RGBA32,
                                         SDL_TEXTUREACCESS_STREAMING,
                                         static_cast<int>(width_),
                                         static_cast<int>(height_));
            textureWidth_ = width_;
            textureHeight_ = height_;
            if (texture_ == nullptr) {
                romm::log::sink().log(romm::log::Severity::Warn, kTag,
                                      std::string("SDL_CreateTexture failed: ") +
                                          SDL_GetError());
                frameReady_ = false;
                return false;
            }
            presentationDirty_ = true;
        }
        if (presentationDirty_) {
            applyLogicalPresentationLocked();
        }

        // Copy the staging buffer into the texture, then clear the flag
        // under the lock (before rendering) so a frame converted during
        // the render below is not lost.
        if (!SDL_UpdateTexture(texture_, nullptr, staging_.data(),
                               static_cast<int>(frameWidth * 4))) {
            romm::log::sink().log(romm::log::Severity::Warn, kTag,
                                  std::string("SDL_UpdateTexture failed: ") +
                                      SDL_GetError());
            frameReady_ = false;
            return false;
        }
        frameReady_ = false;
    }

    // Render outside the lock: the texture now owns a copy of the frame,
    // and the emulation thread may convert the next one while we present.
    SDL_RenderClear(renderer_);
    SDL_RenderTexture(renderer_, texture_, nullptr, nullptr);

    if (scanlines_) {
        // Uses the frameWidth_/frameHeight_ snapshot from above: this block
        // runs outside the mutex, where height_ could change under us.
        if (scanlineTexture_ == nullptr ||
            scanlineTextureHeight_ != frameHeight) {
            if (scanlineTexture_ != nullptr) {
                SDL_DestroyTexture(scanlineTexture_);
                scanlineTexture_ = nullptr;
            }
            scanlineTexture_ = buildScanlineTexture(renderer_, frameHeight);
            scanlineTextureHeight_ =
                scanlineTexture_ != nullptr ? frameHeight : 0;
        }
        if (scanlineTexture_ != nullptr) {
            SDL_RenderTexture(renderer_, scanlineTexture_, nullptr, nullptr);
        }
    }

    SDL_RenderPresent(renderer_);
    return true;
}

void SdlVideoSink::setIntegerScaling(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    integerScaling_ = enabled;
    presentationDirty_ = true;
    applyLogicalPresentationLocked();
}

void SdlVideoSink::setScanlines(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    scanlines_ = enabled;
}

void SdlVideoSink::setFullscreen(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        SDL_SetWindowFullscreen(window_, enabled);
    }
}

}  // namespace romm::player

// sdl_video_sink.cpp — SDL3 presentation of software-emulated core frames.
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
#include <cstring>

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
    presentationState_.request();
}

void SdlVideoSink::attachWindow(romm::video::NativeWindowHandle window) {
    std::lock_guard<std::mutex> lock(mutex_);
    destroySurfaceLocked();

    window_ = static_cast<SDL_Window*>(window);
    if (window_ == nullptr) {
        return;
    }

#ifdef _WIN32
    const char* requestedDriver = SDL_GetHint(SDL_HINT_RENDER_DRIVER);
    if (requestedDriver != nullptr && requestedDriver[0] != '\0') {
        romm::log::sink().log(
                romm::log::Severity::Info, kTag,
                std::string("Explicit SDL presentation driver: ") + requestedDriver);
        renderer_ = SDL_CreateRenderer(window_, nullptr);
    } else {
        // Preserve SDL's GPU-first preference order and final software
        // fallback, but expose individual failures rather than hiding them.
        for (int i = 0; i < SDL_GetNumRenderDrivers() && renderer_ == nullptr; ++i) {
            const char* driver = SDL_GetRenderDriver(i);
            renderer_ = SDL_CreateRenderer(window_, driver);
            if (renderer_ == nullptr) {
                romm::log::sink().log(
                        romm::log::Severity::Warn, kTag,
                        std::string("SDL presentation driver ") + driver +
                            " failed: " + SDL_GetError());
            }
        }
    }
#else
    renderer_ = SDL_CreateRenderer(window_, nullptr);
#endif
    if (renderer_ == nullptr) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_CreateRenderer failed: ") +
                                  SDL_GetError());
        window_ = nullptr;
        return;
    }

    const char* rendererName = SDL_GetRendererName(renderer_);
    const bool softwarePresentation =
        rendererName != nullptr && std::strcmp(rendererName, "software") == 0;
    romm::log::sink().log(
            softwarePresentation ? romm::log::Severity::Warn : romm::log::Severity::Info,
            kTag, std::string("SDL presentation renderer=") +
                (rendererName ? rendererName : "(unknown)") +
                (softwarePresentation ? " (CPU presentation)" : " (adapter-dependent acceleration)") +
                "; core emulation remains software");
#ifdef _WIN32
    if (SDL_GetHintBoolean(SDL_HINT_RENDER_DIRECT3D11_WARP, false)) {
        romm::log::sink().log(
                romm::log::Severity::Warn, kTag,
                "SDL_RENDER_DIRECT3D11_WARP explicitly requests software D3D11 presentation");
    }
#endif

    if (!SDL_SetRenderVSync(renderer_, 1)) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_SetRenderVSync failed: ") +
                                  SDL_GetError());
    }
    // Hardware-rendered cores never submit a software frame, but the pause
    // overlay still needs a drawable coordinate space.
    if (staging_.empty()) {
        int outputWidth = 0;
        int outputHeight = 0;
        if (SDL_GetRenderOutputSize(renderer_, &outputWidth, &outputHeight) &&
            outputWidth > 0 && outputHeight > 0) {
            width_ = static_cast<unsigned>(outputWidth);
            height_ = static_cast<unsigned>(outputHeight);
        }
    }
    presentationState_.request();
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
    logicalPresentationDirty_ = true;
    presentationState_.request();
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
    logicalPresentationDirty_ = false;
}

bool SdlVideoSink::present(const std::function<void(SDL_Renderer*)>& overlay) {
    // Snapshot of the geometry, taken under the lock: submitFrame() may
    // change width_/height_ concurrently, and the scanline section below
    // runs OUTSIDE the mutex (a data race on height_ otherwise).
    unsigned textureHeight = 0;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (renderer_ == nullptr || width_ == 0 || height_ == 0) {
            // No window/renderer yet: leave the screen untouched.
            // frameReady_ stays set so the frame is presented once a
            // window is attached.
            return false;
        }
        if (!presentationState_.consume()) {
            return false;
        }

        if (frameReady_) {
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
                // Apply the sharp-filter scale mode to every (re)created
                // frame texture: NEAREST for hard pixel edges, LINEAR
                // (SDL3's default) for smooth compositor-style upscaling.
                if (texture_ != nullptr) {
                    SDL_SetTextureScaleMode(texture_, sharpFilter_ ? SDL_SCALEMODE_NEAREST
                                                                   : SDL_SCALEMODE_LINEAR);
                }
                textureWidth_ = width_;
                textureHeight_ = height_;
                if (texture_ == nullptr) {
                    romm::log::sink().log(romm::log::Severity::Warn, kTag,
                                          std::string("SDL_CreateTexture failed: ") +
                                              SDL_GetError());
                    frameReady_ = false;
                    return false;
                }
                logicalPresentationDirty_ = true;
            }
            // Copy the staging buffer into the texture, then clear the flag
            // under the lock (before rendering) so a frame converted during
            // the render below is not lost.
            if (!SDL_UpdateTexture(texture_, nullptr, staging_.data(),
                                   static_cast<int>(width_ * 4))) {
                romm::log::sink().log(romm::log::Severity::Warn, kTag,
                                      std::string("SDL_UpdateTexture failed: ") +
                                          SDL_GetError());
                frameReady_ = false;
                return false;
            }
            frameReady_ = false;
        }
        if (logicalPresentationDirty_) {
            applyLogicalPresentationLocked();
        }

        // Snapshot the texture height for the scanline section below (which
        // runs outside the mutex, where these could change under us). While
        // paused no new frame arrives, so the texture keeps holding the last
        // presented frame and we simply re-present it.
        textureHeight = textureHeight_;
    }

    // Render outside the lock: the texture owns a copy of the last frame,
    // and the emulation thread may convert the next one while we present.
    // Render state is shared with the pause overlay, whose last operation is
    // commonly white text. Set the clear color explicitly so letterbox bars
    // are always black rather than inheriting that stale draw color.
    SDL_SetRenderDrawColor(renderer_, 0, 0, 0, 255);
    SDL_RenderClear(renderer_);
    if (texture_ != nullptr) {
        SDL_RenderTexture(renderer_, texture_, nullptr, nullptr);

        if (scanlines_) {
            if (scanlineTexture_ == nullptr || scanlineTextureHeight_ != textureHeight) {
                if (scanlineTexture_ != nullptr) {
                    SDL_DestroyTexture(scanlineTexture_);
                    scanlineTexture_ = nullptr;
                }
                scanlineTexture_ = buildScanlineTexture(renderer_, textureHeight);
                scanlineTextureHeight_ =
                    scanlineTexture_ != nullptr ? textureHeight : 0;
            }
            if (scanlineTexture_ != nullptr) {
                SDL_RenderTexture(renderer_, scanlineTexture_, nullptr, nullptr);
            }
        }
    }

    // Draw UI in output pixels, not in the core's low-resolution logical
    // canvas. Otherwise a 240p core rasterizes the pause menu at 240p and SDL
    // enlarges it with the game, making even TrueType text visibly pixelated.
    // Restore the game presentation immediately afterward so the next frame
    // retains its configured aspect ratio and integer-scaling behavior.
    if (overlay) {
        int logicalWidth = 0;
        int logicalHeight = 0;
        int outputWidth = 0;
        int outputHeight = 0;
        SDL_RendererLogicalPresentation logicalMode = SDL_LOGICAL_PRESENTATION_DISABLED;
        SDL_GetRenderLogicalPresentation(
                renderer_, &logicalWidth, &logicalHeight, &logicalMode);
        SDL_GetRenderOutputSize(renderer_, &outputWidth, &outputHeight);
        if (outputWidth > 0 && outputHeight > 0) {
            SDL_SetRenderLogicalPresentation(
                    renderer_, outputWidth, outputHeight,
                    SDL_LOGICAL_PRESENTATION_DISABLED);
        }
        overlay(renderer_);
        SDL_SetRenderLogicalPresentation(
                renderer_, logicalWidth, logicalHeight, logicalMode);
    }

    SDL_RenderPresent(renderer_);
    return true;
}

void SdlVideoSink::requestRepaint() {
    std::lock_guard<std::mutex> lock(mutex_);
    presentationState_.request();
}

void SdlVideoSink::setIntegerScaling(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (integerScaling_ == enabled) return;
    integerScaling_ = enabled;
    logicalPresentationDirty_ = true;
    presentationState_.request();
    applyLogicalPresentationLocked();
}

void SdlVideoSink::setScanlines(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (scanlines_ == enabled) return;
    scanlines_ = enabled;
    presentationState_.request();
}

void SdlVideoSink::setSharpFilter(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (sharpFilter_ == enabled) return;
    sharpFilter_ = enabled;
    if (texture_ != nullptr) {
        SDL_SetTextureScaleMode(texture_, sharpFilter_ ? SDL_SCALEMODE_NEAREST
                                                       : SDL_SCALEMODE_LINEAR);
    }
    presentationState_.request();
}

void SdlVideoSink::setFullscreen(bool enabled) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) {
        SDL_SetWindowFullscreen(window_, enabled);
        presentationState_.request();
    }
}

}  // namespace romm::player

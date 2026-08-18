// sdl_video_sink.h — SDL3 software video output for the Linux player
// (Phase 8, LINUX_X64.md section 12).
//
// Thread model (mirrors the engine's VideoSink contract):
//   - submitFrame() runs on the EMULATION thread. It converts the core's
//     software framebuffer to RGBA8888 row-by-row into a mutex-protected
//     staging buffer and flags frameReady_. It never touches the renderer.
//   - attachWindow()/detachWindow()/present()/setIntegerScaling()/
//     setScanlines()/setFullscreen() run on the MAIN thread. present()
//     copies the staging buffer into a streaming texture (under the mutex)
//     and then renders outside the lock, so the emulation thread is only
//     ever blocked for the duration of SDL_UpdateTexture.
//
// The window handle is an SDL_Window* created by main; this sink takes
// ownership of the renderer/texture it creates for that window.
#pragma once

#include <native/engine/VideoSink.h>

#include <cstddef>
#include <mutex>
#include <vector>

struct SDL_Renderer;
struct SDL_Texture;
struct SDL_Window;

namespace romm::player {

class SdlVideoSink final : public romm::video::VideoSink {
public:
    // Engine seam (main thread unless noted).
    void attachWindow(romm::video::NativeWindowHandle window) override;
    void detachWindow() override;
    void setDisplayAspectRatio(double aspectRatio) override;
    // Emulation thread.
    void submitFrame(const void* data, unsigned width, unsigned height,
                     size_t pitch, enum retro_pixel_format format) override;

    // Main-thread presentation loop.
    // Presents the latest converted frame if one is ready; returns false
    // (and leaves the screen untouched) when frameReady_ is not set.
    bool present();

    // Toggles integer scaling. Non-integer output is letterboxed.
    void setIntegerScaling(bool enabled);

    // Toggles a 1-logical-pixel scanline overlay (a 1xH texture of
    // alternating transparent / semi-transparent black rows, stretched
    // over the frame).
    void setScanlines(bool enabled);

    void setFullscreen(bool enabled);

private:
    // Destroys texture/scanline texture/renderer. Call with mutex_ held.
    void destroySurfaceLocked();

    // Applies the core's display aspect ratio and current scaling mode.
    // Call with mutex_ held on the main thread.
    void applyLogicalPresentationLocked();

    // SDL objects (main thread only, but guarded by mutex_ against the
    // emulation thread's geometry reads).
    SDL_Window* window_ = nullptr;
    SDL_Renderer* renderer_ = nullptr;
    SDL_Texture* texture_ = nullptr;
    unsigned textureWidth_ = 0;
    unsigned textureHeight_ = 0;
    SDL_Texture* scanlineTexture_ = nullptr;
    unsigned scanlineTextureHeight_ = 0;

    // Staging buffer (emulation thread writes, main thread reads the
    // pointer/size under mutex_).
    std::vector<uint8_t> staging_;
    unsigned width_ = 0;
    unsigned height_ = 0;
    double displayAspectRatio_ = 0.0;
    bool presentationDirty_ = true;
    bool frameReady_ = false;

    // Presentation options (main thread).
    bool integerScaling_ = false;
    bool scanlines_ = false;

    mutable std::mutex mutex_;
};

}  // namespace romm::player

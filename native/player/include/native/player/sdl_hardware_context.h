#pragma once

#include <SDL3/SDL_video.h>

#include <native/engine/HardwareContext.h>

#include <atomic>
#include <cstddef>
#include <mutex>
#include <vector>

namespace romm::player {

class SdlHardwareContext final : public romm::gl::HardwareContext {
public:
    SdlHardwareContext(SDL_Window* window, bool useOffscreenPresentation);
    ~SdlHardwareContext() override;

    bool createContext() override;
    void setBufferGeometry(unsigned width, unsigned height) override;
    void setContentGeometry(unsigned width, unsigned height) override;
    void attachWindow(romm::video::NativeWindowHandle window) override;
    void detachWindow() override;
    WindowUpdateResult applyPendingWindowUpdate() override;
    bool hasPendingWindowUpdate() override;
    bool hasSurface() override;
    void unmakeCurrent() override;
    bool makeCurrent() override;
    void setScanlines(bool enabled) override;
    void setIntegerScaling(bool enabled) override;
    void setSharpFilter(bool enabled) override;
    bool swapBuffers() override;
    void destroyContext() override;
    void* currentContext() const override;
    uintptr_t currentFramebuffer() const override;
    retro_proc_address_t getProcAddress(const char* name) override;
    bool isValid() const override;

    // Overlay compositing for the pause menu (Linux offscreen-FBO
    // presentation path only). The main thread rasterizes PauseOverlay
    // off-window into RGBA8888 pixels (see main.cpp's HardwareOverlayRaster)
    // and stages them here; swapBuffers(), which always runs on the
    // emulation thread that owns this GL context, uploads the staged pixels
    // to a texture and composites them over the retained game framebuffer.
    // `pitch` is the staged buffer's row stride in bytes, which may exceed
    // width * 4 (e.g. an SDL_Surface's row alignment). Thread-safe: guarded
    // by mutex_ against a concurrent swapBuffers() call.
    void setOverlayFrame(const void* rgba, unsigned width, unsigned height, size_t pitch);

    // Disables overlay compositing so the next swapBuffers() presents the
    // retained game framebuffer alone (called on resume, before gameplay
    // presentation resumes). Safe to call from any thread.
    void clearOverlay();

private:
    mutable std::mutex mutex_;
    SDL_Window* window_ = nullptr;
    SDL_Window* pendingWindow_ = nullptr;
    SDL_GLContext context_ = nullptr;
    bool windowUpdatePending_ = false;
    bool surfaceAttached_ = false;
    unsigned bufferWidth_ = 0;
    unsigned bufferHeight_ = 0;
    unsigned contentWidth_ = 0;
    unsigned contentHeight_ = 0;
    unsigned framebuffer_ = 0;
    unsigned colorTexture_ = 0;
    unsigned depthStencil_ = 0;
    unsigned scanlineProgram_ = 0;
    unsigned scanlineVertexArray_ = 0;
    bool useOffscreenPresentation_ = false;
    std::atomic<bool> scanlinesEnabled_{false};
    std::atomic<bool> integerScalingEnabled_{false};
    std::atomic<bool> sharpFilterEnabled_{false};

    // Overlay compositing state (see setOverlayFrame()/clearOverlay()).
    // overlayStaging_/overlayWidth_/overlayHeight_ are written by the main
    // thread and consumed (uploaded to overlayTexture_) by the emulation
    // thread in swapBuffers(), both under mutex_.
    unsigned overlayTexture_ = 0;
    unsigned overlayFramebuffer_ = 0;
    unsigned overlayTextureWidth_ = 0;
    unsigned overlayTextureHeight_ = 0;
    unsigned overlayWidth_ = 0;
    unsigned overlayHeight_ = 0;
    bool overlayEnabled_ = false;
    bool overlayDirty_ = false;
    std::vector<uint8_t> overlayStaging_;

    bool createFramebufferLocked();
    void destroyFramebufferLocked();
    bool createScanlineProgramLocked();
    void drawScanlinesLocked(int outputWidth, int outputHeight);
    bool createOverlayResourcesLocked();
    void destroyOverlayResourcesLocked();
    void uploadOverlayTextureLocked();
};

}  // namespace romm::player

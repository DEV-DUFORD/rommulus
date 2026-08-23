#pragma once

#include <SDL3/SDL_video.h>

#include <native/engine/HardwareContext.h>

#include <atomic>
#include <mutex>

namespace romm::player {

class SdlHardwareContext final : public romm::gl::HardwareContext {
public:
    SdlHardwareContext(SDL_Window* window, bool useOffscreenPresentation);
    ~SdlHardwareContext() override;

    bool createContext() override;
    void setBufferGeometry(unsigned width, unsigned height) override;
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

private:
    mutable std::mutex mutex_;
    SDL_Window* window_ = nullptr;
    SDL_Window* pendingWindow_ = nullptr;
    SDL_GLContext context_ = nullptr;
    bool windowUpdatePending_ = false;
    bool surfaceAttached_ = false;
    unsigned bufferWidth_ = 0;
    unsigned bufferHeight_ = 0;
    unsigned framebuffer_ = 0;
    unsigned colorTexture_ = 0;
    unsigned depthStencil_ = 0;
    unsigned scanlineProgram_ = 0;
    bool useOffscreenPresentation_ = false;
    std::atomic<bool> scanlinesEnabled_{false};
    std::atomic<bool> integerScalingEnabled_{false};
    std::atomic<bool> sharpFilterEnabled_{false};

    bool createFramebufferLocked();
    void destroyFramebufferLocked();
    bool createScanlineProgramLocked();
    void drawScanlinesLocked(int outputWidth, int outputHeight);
};

}  // namespace romm::player

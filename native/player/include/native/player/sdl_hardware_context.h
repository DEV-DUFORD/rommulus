#pragma once

#include <SDL3/SDL_video.h>

#include <native/engine/HardwareContext.h>

#include <mutex>

namespace romm::player {

class SdlHardwareContext final : public romm::gl::HardwareContext {
public:
    explicit SdlHardwareContext(SDL_Window* window);
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

    bool createFramebufferLocked();
    void destroyFramebufferLocked();
};

}  // namespace romm::player

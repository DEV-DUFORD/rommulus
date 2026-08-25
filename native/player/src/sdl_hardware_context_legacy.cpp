#include "native/player/sdl_hardware_context.h"

#include <SDL3/SDL.h>

#include <native/engine/LogSink.h>

#include <string>

namespace romm::player {
namespace {

constexpr const char* kTag = "sdl_gl_context";

void logSdlError(const char* operation) {
    romm::log::sink().log(
        romm::log::Severity::Error, kTag,
        std::string(operation) + " failed: " + SDL_GetError());
}

}  // namespace

SdlHardwareContext::SdlHardwareContext(
    SDL_Window* window,
    bool /*useOffscreenPresentation*/
)
    : window_(window), pendingWindow_(window), windowUpdatePending_(true) {
    context_ = SDL_GL_CreateContext(window_);
    if (context_ == nullptr) {
        logSdlError("SDL_GL_CreateContext");
    } else {
        SDL_GL_MakeCurrent(window_, nullptr);
    }
}

SdlHardwareContext::~SdlHardwareContext() {
    destroyContext();
}

bool SdlHardwareContext::createContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (context_ == nullptr || window_ == nullptr) return false;
    if (!SDL_GL_MakeCurrent(window_, context_)) {
        logSdlError("SDL_GL_MakeCurrent");
        return false;
    }
    if (!SDL_GL_SetSwapInterval(1)) {
        romm::log::sink().log(
            romm::log::Severity::Warn, kTag,
            std::string("SDL_GL_SetSwapInterval failed: ") + SDL_GetError());
    }
    return true;
}

void SdlHardwareContext::setBufferGeometry(unsigned, unsigned) {}

void SdlHardwareContext::setContentGeometry(unsigned, unsigned) {}

void SdlHardwareContext::attachWindow(romm::video::NativeWindowHandle window) {
    std::lock_guard<std::mutex> lock(mutex_);
    pendingWindow_ = static_cast<SDL_Window*>(window);
    windowUpdatePending_ = true;
}

void SdlHardwareContext::detachWindow() {
    std::lock_guard<std::mutex> lock(mutex_);
    pendingWindow_ = nullptr;
    windowUpdatePending_ = true;
}

romm::gl::HardwareContext::WindowUpdateResult
SdlHardwareContext::applyPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!windowUpdatePending_) return WindowUpdateResult::kNone;
    windowUpdatePending_ = false;
    if (pendingWindow_ == nullptr) {
        if (context_ != nullptr && window_ != nullptr) {
            SDL_GL_MakeCurrent(window_, nullptr);
        }
        window_ = nullptr;
        surfaceAttached_ = false;
        return WindowUpdateResult::kDetached;
    }
    if (context_ == nullptr) return WindowUpdateResult::kFailed;
    window_ = pendingWindow_;
    pendingWindow_ = nullptr;
    if (!SDL_GL_MakeCurrent(window_, context_)) {
        logSdlError("SDL_GL_MakeCurrent");
        surfaceAttached_ = false;
        return WindowUpdateResult::kFailed;
    }
    surfaceAttached_ = true;
    return WindowUpdateResult::kAttached;
}

bool SdlHardwareContext::hasPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);
    return windowUpdatePending_;
}

bool SdlHardwareContext::hasSurface() {
    std::lock_guard<std::mutex> lock(mutex_);
    return surfaceAttached_;
}

void SdlHardwareContext::unmakeCurrent() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (window_ != nullptr) SDL_GL_MakeCurrent(window_, nullptr);
}

bool SdlHardwareContext::makeCurrent() {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_ != nullptr && window_ != nullptr &&
           SDL_GL_MakeCurrent(window_, context_);
}

void SdlHardwareContext::setScanlines(bool) {}

void SdlHardwareContext::setIntegerScaling(bool) {}

void SdlHardwareContext::setSharpFilter(bool) {}

bool SdlHardwareContext::swapBuffers() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!surfaceAttached_ || window_ == nullptr) return false;
    if (!SDL_GL_SwapWindow(window_)) {
        logSdlError("SDL_GL_SwapWindow");
        return false;
    }
    return true;
}

void SdlHardwareContext::destroyContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (context_ != nullptr) {
        if (window_ != nullptr) SDL_GL_MakeCurrent(window_, nullptr);
        SDL_GL_DestroyContext(context_);
        context_ = nullptr;
    }
    pendingWindow_ = nullptr;
    windowUpdatePending_ = false;
    surfaceAttached_ = false;
}

void* SdlHardwareContext::currentContext() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_;
}

uintptr_t SdlHardwareContext::currentFramebuffer() const {
    return 0;
}

retro_proc_address_t SdlHardwareContext::getProcAddress(const char* name) {
    return reinterpret_cast<retro_proc_address_t>(SDL_GL_GetProcAddress(name));
}

bool SdlHardwareContext::isValid() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_ != nullptr;
}

bool SdlHardwareContext::createFramebufferLocked() {
    return false;
}

void SdlHardwareContext::destroyFramebufferLocked() {}

bool SdlHardwareContext::createScanlineProgramLocked() {
    return false;
}

void SdlHardwareContext::drawScanlinesLocked(int, int) {}

}  // namespace romm::player

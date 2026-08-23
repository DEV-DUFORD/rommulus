#include "native/player/sdl_hardware_context.h"

#include <SDL3/SDL.h>
#include <GLES3/gl3.h>

#include <native/engine/LogSink.h>

#include <algorithm>
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

SdlHardwareContext::SdlHardwareContext(SDL_Window* window)
    : window_(window), pendingWindow_(window), windowUpdatePending_(true) {
    // SDL requires context creation on the main thread. The emulation thread
    // takes ownership with SDL_GL_MakeCurrent() in createContext().
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
    if (context_ == nullptr || window_ == nullptr) {
        return false;
    }
    if (!SDL_GL_MakeCurrent(window_, context_)) {
        logSdlError("SDL_GL_MakeCurrent");
        return false;
    }
    if (!createFramebufferLocked()) {
        return false;
    }
    if (!SDL_GL_SetSwapInterval(1)) {
        romm::log::sink().log(
                romm::log::Severity::Warn, kTag,
                std::string("SDL_GL_SetSwapInterval failed: ") + SDL_GetError());
    }
    return true;
}

void SdlHardwareContext::setBufferGeometry(unsigned width, unsigned height) {
    std::lock_guard<std::mutex> lock(mutex_);
    bufferWidth_ = width;
    bufferHeight_ = height;
}

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
    if (context_ == nullptr) {
        logSdlError("window attach before SDL_GL_CreateContext");
        return WindowUpdateResult::kFailed;
    }

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
    if (context_ == nullptr || window_ == nullptr) return false;
    if (!SDL_GL_MakeCurrent(window_, context_)) {
        logSdlError("SDL_GL_MakeCurrent");
        return false;
    }
    return true;
}

bool SdlHardwareContext::swapBuffers() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!surfaceAttached_ || window_ == nullptr) return false;
    if (framebuffer_ != 0 && bufferWidth_ > 0 && bufferHeight_ > 0) {
        int outputWidth = 0;
        int outputHeight = 0;
        SDL_GetWindowSizeInPixels(window_, &outputWidth, &outputHeight);
        if (outputWidth > 0 && outputHeight > 0) {
            GLint previousDrawFramebuffer = 0;
            GLint previousReadFramebuffer = 0;
            GLint previousViewport[4] = {};
            GLint previousScissorBox[4] = {};
            GLboolean previousColorMask[4] = {};
            GLfloat previousClearColor[4] = {};
            const GLboolean scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
            glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
            glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFramebuffer);
            glGetIntegerv(GL_VIEWPORT, previousViewport);
            glGetIntegerv(GL_SCISSOR_BOX, previousScissorBox);
            glGetBooleanv(GL_COLOR_WRITEMASK, previousColorMask);
            glGetFloatv(GL_COLOR_CLEAR_VALUE, previousClearColor);

            const double scale = std::min(
                static_cast<double>(outputWidth) / bufferWidth_,
                static_cast<double>(outputHeight) / bufferHeight_);
            const int width = static_cast<int>(bufferWidth_ * scale);
            const int height = static_cast<int>(bufferHeight_ * scale);
            const int x = (outputWidth - width) / 2;
            const int y = (outputHeight - height) / 2;

            glDisable(GL_SCISSOR_TEST);
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glViewport(0, 0, outputWidth, outputHeight);
            glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, framebuffer_);
            glBlitFramebuffer(
                0, 0, static_cast<GLint>(bufferWidth_), static_cast<GLint>(bufferHeight_),
                x, y, x + width, y + height, GL_COLOR_BUFFER_BIT, GL_LINEAR);

            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousDrawFramebuffer);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, previousReadFramebuffer);
            glViewport(
                previousViewport[0], previousViewport[1],
                previousViewport[2], previousViewport[3]);
            glScissor(
                previousScissorBox[0], previousScissorBox[1],
                previousScissorBox[2], previousScissorBox[3]);
            if (scissorEnabled) {
                glEnable(GL_SCISSOR_TEST);
            } else {
                glDisable(GL_SCISSOR_TEST);
            }
            glColorMask(
                previousColorMask[0], previousColorMask[1],
                previousColorMask[2], previousColorMask[3]);
            glClearColor(
                previousClearColor[0], previousClearColor[1],
                previousClearColor[2], previousClearColor[3]);
        }
    }
    if (!SDL_GL_SwapWindow(window_)) {
        logSdlError("SDL_GL_SwapWindow");
        return false;
    }
    return true;
}

void SdlHardwareContext::destroyContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (context_ != nullptr) {
        destroyFramebufferLocked();
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
    std::lock_guard<std::mutex> lock(mutex_);
    return framebuffer_;
}

retro_proc_address_t SdlHardwareContext::getProcAddress(const char* name) {
    return reinterpret_cast<retro_proc_address_t>(SDL_GL_GetProcAddress(name));
}

bool SdlHardwareContext::isValid() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return context_ != nullptr;
}

bool SdlHardwareContext::createFramebufferLocked() {
    destroyFramebufferLocked();
    if (bufferWidth_ == 0 || bufferHeight_ == 0) {
        logSdlError("invalid hardware framebuffer geometry");
        return false;
    }

    glGenTextures(1, &colorTexture_);
    glBindTexture(GL_TEXTURE_2D, colorTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(bufferWidth_),
        static_cast<GLsizei>(bufferHeight_), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glGenRenderbuffers(1, &depthStencil_);
    glBindRenderbuffer(GL_RENDERBUFFER, depthStencil_);
    glRenderbufferStorage(
        GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, static_cast<GLsizei>(bufferWidth_),
        static_cast<GLsizei>(bufferHeight_));

    glGenFramebuffers(1, &framebuffer_);
    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, colorTexture_, 0);
    glFramebufferRenderbuffer(
        GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, depthStencil_);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        romm::log::sink().log(
            romm::log::Severity::Error, kTag, "hardware framebuffer is incomplete");
        destroyFramebufferLocked();
        return false;
    }
    return true;
}

void SdlHardwareContext::destroyFramebufferLocked() {
    if (framebuffer_ != 0) glDeleteFramebuffers(1, &framebuffer_);
    if (depthStencil_ != 0) glDeleteRenderbuffers(1, &depthStencil_);
    if (colorTexture_ != 0) glDeleteTextures(1, &colorTexture_);
    framebuffer_ = 0;
    depthStencil_ = 0;
    colorTexture_ = 0;
}

}  // namespace romm::player

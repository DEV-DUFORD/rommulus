#include "native/player/sdl_hardware_context.h"

#include <SDL3/SDL.h>
#include <GLES3/gl3.h>

#include <native/engine/LogSink.h>

#include <algorithm>
#include <cmath>
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
    bool useOffscreenPresentation
)
    : window_(window),
      pendingWindow_(window),
      windowUpdatePending_(true),
      useOffscreenPresentation_(useOffscreenPresentation) {
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
    if (useOffscreenPresentation_ && !createFramebufferLocked()) {
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

void SdlHardwareContext::setContentGeometry(unsigned width, unsigned height) {
    std::lock_guard<std::mutex> lock(mutex_);
    contentWidth_ = width;
    contentHeight_ = height;
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

void SdlHardwareContext::setScanlines(bool enabled) {
    scanlinesEnabled_.store(enabled);
}

void SdlHardwareContext::setIntegerScaling(bool enabled) {
    integerScalingEnabled_.store(enabled);
}

void SdlHardwareContext::setSharpFilter(bool enabled) {
    sharpFilterEnabled_.store(enabled);
}

bool SdlHardwareContext::swapBuffers() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (!surfaceAttached_ || window_ == nullptr) return false;
    int outputWidth = 0;
    int outputHeight = 0;
    SDL_GetWindowSizeInPixels(window_, &outputWidth, &outputHeight);
    if (framebuffer_ != 0 && bufferWidth_ > 0 && bufferHeight_ > 0 &&
        outputWidth > 0 && outputHeight > 0) {
        // A core may render only a native-resolution region at the top-left of
        // the offscreen buffer instead of filling it (e.g. lrps2 reports a
        // fixed 640x448 base geometry but draws the game's actual resolution,
        // which for many PS2 titles is narrower, e.g. 512x448). Present just
        // that content region, stretched to the buffer's aspect ratio and
        // centered, so a narrower native frame is centered rather than being
        // left-aligned inside the full buffer (which the outer centering of
        // bufferWidth_ x bufferHeight_ cannot compensate for).
        const unsigned srcW = (contentWidth_ > 0) ? std::min(contentWidth_, bufferWidth_) : bufferWidth_;
        const unsigned srcH = (contentHeight_ > 0) ? std::min(contentHeight_, bufferHeight_) : bufferHeight_;

        // Destination keeps the buffer's aspect (base geometry), which is what
        // the reported content is meant to be displayed at.
        double scale = std::min(
            static_cast<double>(outputWidth) / bufferWidth_,
            static_cast<double>(outputHeight) / bufferHeight_);
        if (integerScalingEnabled_.load() && scale >= 1.0) {
            scale = std::floor(scale);
        }
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
            0, 0, static_cast<GLint>(srcW), static_cast<GLint>(srcH),
            x, y, x + width, y + height, GL_COLOR_BUFFER_BIT,
            sharpFilterEnabled_.load() ? GL_NEAREST : GL_LINEAR);
    }
    if (scanlinesEnabled_.load() && outputWidth > 0 && outputHeight > 0) {
        drawScanlinesLocked(outputWidth, outputHeight);
    }
    if (!SDL_GL_SwapWindow(window_)) {
        logSdlError("SDL_GL_SwapWindow");
        return false;
    }
    if (framebuffer_ != 0) {
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer_);
    }
    return true;
}

void SdlHardwareContext::destroyContext() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (context_ != nullptr) {
        if (window_ != nullptr && SDL_GL_MakeCurrent(window_, context_)) {
            destroyFramebufferLocked();
            if (scanlineProgram_ != 0) {
                glDeleteProgram(scanlineProgram_);
                scanlineProgram_ = 0;
            }
        }
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
    return useOffscreenPresentation_ ? framebuffer_ : 0;
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

bool SdlHardwareContext::createScanlineProgramLocked() {
    static constexpr const char* kVertexShader = R"(
        #version 300 es
        const vec2 positions[3] = vec2[3](
            vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
        );
        void main() {
            gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
        }
    )";
    static constexpr const char* kFragmentShader = R"(
        #version 300 es
        precision mediump float;
        uniform float rowHeight;
        out vec4 color;
        void main() {
            if ((int(floor(gl_FragCoord.y / rowHeight)) & 1) == 0) discard;
            color = vec4(0.0, 0.0, 0.0, 0.375);
        }
    )";

    const auto compile = [](GLenum type, const char* source) {
        const GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled = GL_FALSE;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled != GL_TRUE) {
            glDeleteShader(shader);
            return GLuint{0};
        }
        return shader;
    };

    const GLuint vertexShader = compile(GL_VERTEX_SHADER, kVertexShader);
    const GLuint fragmentShader = compile(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertexShader == 0 || fragmentShader == 0) {
        if (vertexShader != 0) glDeleteShader(vertexShader);
        if (fragmentShader != 0) glDeleteShader(fragmentShader);
        romm::log::sink().log(
            romm::log::Severity::Warn, kTag, "failed to compile scanline shader");
        return false;
    }

    scanlineProgram_ = glCreateProgram();
    glAttachShader(scanlineProgram_, vertexShader);
    glAttachShader(scanlineProgram_, fragmentShader);
    glLinkProgram(scanlineProgram_);
    glDeleteShader(vertexShader);
    glDeleteShader(fragmentShader);
    GLint linked = GL_FALSE;
    glGetProgramiv(scanlineProgram_, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        glDeleteProgram(scanlineProgram_);
        scanlineProgram_ = 0;
        romm::log::sink().log(
            romm::log::Severity::Warn, kTag, "failed to link scanline shader");
        return false;
    }
    return true;
}

void SdlHardwareContext::drawScanlinesLocked(int outputWidth, int outputHeight) {
    if (scanlineProgram_ == 0 && !createScanlineProgramLocked()) return;

    GLint previousFramebuffer = 0;
    GLint previousProgram = 0;
    GLint previousVertexArray = 0;
    GLint previousViewport[4] = {};
    GLint previousBlendSrcRgb = 0;
    GLint previousBlendDstRgb = 0;
    GLint previousBlendSrcAlpha = 0;
    GLint previousBlendDstAlpha = 0;
    GLint previousBlendEquationRgb = 0;
    GLint previousBlendEquationAlpha = 0;
    GLint previousScissorBox[4] = {};
    GLboolean previousColorMask[4] = {};
    const GLboolean blendEnabled = glIsEnabled(GL_BLEND);
    const GLboolean depthEnabled = glIsEnabled(GL_DEPTH_TEST);
    const GLboolean stencilEnabled = glIsEnabled(GL_STENCIL_TEST);
    const GLboolean cullEnabled = glIsEnabled(GL_CULL_FACE);
    const GLboolean scissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    const GLboolean rasterizerDiscardEnabled = glIsEnabled(GL_RASTERIZER_DISCARD);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousFramebuffer);
    glGetIntegerv(GL_CURRENT_PROGRAM, &previousProgram);
    glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &previousVertexArray);
    glGetIntegerv(GL_VIEWPORT, previousViewport);
    glGetIntegerv(GL_BLEND_SRC_RGB, &previousBlendSrcRgb);
    glGetIntegerv(GL_BLEND_DST_RGB, &previousBlendDstRgb);
    glGetIntegerv(GL_BLEND_SRC_ALPHA, &previousBlendSrcAlpha);
    glGetIntegerv(GL_BLEND_DST_ALPHA, &previousBlendDstAlpha);
    glGetIntegerv(GL_BLEND_EQUATION_RGB, &previousBlendEquationRgb);
    glGetIntegerv(GL_BLEND_EQUATION_ALPHA, &previousBlendEquationAlpha);
    glGetIntegerv(GL_SCISSOR_BOX, previousScissorBox);
    glGetBooleanv(GL_COLOR_WRITEMASK, previousColorMask);

    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glViewport(0, 0, outputWidth, outputHeight);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_RASTERIZER_DISCARD);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    glBlendEquation(GL_FUNC_ADD);
    glUseProgram(scanlineProgram_);
    const GLint rowHeight = glGetUniformLocation(scanlineProgram_, "rowHeight");
    if (rowHeight >= 0) {
        glUniform1f(
            rowHeight,
            std::max(1.0f, std::floor(outputHeight / 240.0f)));
    }
    glBindVertexArray(0);
    glDrawArrays(GL_TRIANGLES, 0, 3);

    glBindVertexArray(previousVertexArray);
    glUseProgram(previousProgram);
    glBlendFuncSeparate(
        previousBlendSrcRgb, previousBlendDstRgb,
        previousBlendSrcAlpha, previousBlendDstAlpha);
    glBlendEquationSeparate(previousBlendEquationRgb, previousBlendEquationAlpha);
    if (!blendEnabled) glDisable(GL_BLEND);
    if (depthEnabled) glEnable(GL_DEPTH_TEST);
    if (stencilEnabled) glEnable(GL_STENCIL_TEST);
    if (cullEnabled) glEnable(GL_CULL_FACE);
    if (scissorEnabled) glEnable(GL_SCISSOR_TEST);
    if (rasterizerDiscardEnabled) glEnable(GL_RASTERIZER_DISCARD);
    glScissor(
        previousScissorBox[0], previousScissorBox[1],
        previousScissorBox[2], previousScissorBox[3]);
    glColorMask(
        previousColorMask[0], previousColorMask[1],
        previousColorMask[2], previousColorMask[3]);
    glViewport(
        previousViewport[0], previousViewport[1],
        previousViewport[2], previousViewport[3]);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, previousFramebuffer);
}

}  // namespace romm::player

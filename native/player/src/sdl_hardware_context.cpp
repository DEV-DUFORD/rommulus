#include "native/player/sdl_hardware_context.h"

#include "native/player/overlay_pixels.h"

#include <SDL3/SDL.h>
#include <GLES3/gl3.h>

#include <native/engine/LogSink.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstdio>
#include <cstring>
#include <string>

namespace romm::player {

namespace {

constexpr const char* kTag = "sdl_gl_context";
// Opt-in diagnostics for the offscreen-FBO present path (core renders into an
// offscreen framebuffer that swapBuffers() blits into the window). Enable with
// ROMM_PS2_PRESENT_DIAGNOSTICS=1. Kept distinct and low-noise (one line on any
// dims change, one line per second, one sampled FBO read-back per second)
// so the next on-device test can read player.log and tell whether the core is
// actually producing non-black frames (and at what content geometry) even
// while the window is black — i.e. distinguish "core emits no/empty frames"
// (content dims 0 / all-black read-back with 0 dims) from "a valid image was
// lost in the frontend blit" (content dims nonzero but all-black read-back).
// Written directly to stderr (captured to player.log) so it is independent of
// any log-level filtering.
constexpr const char* kPresentDiagTag = "offscreen_present";

// File-local diagnostic state for the offscreen present path. Only the
// emulation thread calls swapBuffers(), and the player is a fresh process per
// session, so no locking/reset is needed here. Seeded to values that force a
// log on the first frame.
int g_diagOutW = -1;
int g_diagOutH = -1;
unsigned g_diagBufW = 0;
unsigned g_diagBufH = 0;
unsigned g_diagContentW = 0;
unsigned g_diagContentH = 0;
uint64_t g_diagFrameCount = 0;
uint64_t g_diagLastLogFrame = 0;
std::chrono::steady_clock::time_point g_diagLastLogTime{};
GLenum g_diagLastFbStatus = GL_FRAMEBUFFER_COMPLETE;
GLenum g_diagLastGlError = GL_NO_ERROR;
std::chrono::steady_clock::time_point g_diagLastGlLogTime{};

bool presentDiagnosticsEnabled() {
    static const bool enabled = [] {
        const char* value = std::getenv("ROMM_PS2_PRESENT_DIAGNOSTICS");
        return value != nullptr && value[0] != '\0' &&
            std::strcmp(value, "0") != 0 && std::strcmp(value, "false") != 0;
    }();
    return enabled;
}

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

    // Bounded offscreen-present diagnostics (see kPresentDiagTag). This path
    // blits the core's offscreen FBO into the window, so these logs confirm
    // whether frames are flowing and at what content geometry — the key
    // evidence for whether a black screen is a core-side empty-frame issue
    // (content dims stay 0 / frame counter stalls) vs. a blit problem.
    const bool diagnosticsEnabled = presentDiagnosticsEnabled();
    const auto diagNow = diagnosticsEnabled
        ? std::chrono::steady_clock::now()
        : std::chrono::steady_clock::time_point{};
    const bool diagPerSecond =
        diagnosticsEnabled &&
        (g_diagLastLogTime == std::chrono::steady_clock::time_point{} ||
         diagNow - g_diagLastLogTime >= std::chrono::seconds(1));
    if (diagnosticsEnabled && useOffscreenPresentation_) {
        ++g_diagFrameCount;
        const bool dimsChanged =
            outputWidth != g_diagOutW || outputHeight != g_diagOutH ||
            contentWidth_ != g_diagContentW || contentHeight_ != g_diagContentH ||
            bufferWidth_ != g_diagBufW || bufferHeight_ != g_diagBufH;
        if (dimsChanged) {
            std::fprintf(
                stderr,
                "[%s] dims: content=%ux%u%s buffer=%ux%u%s window=%dx%d%s\n",
                kPresentDiagTag,
                contentWidth_, contentHeight_,
                (contentWidth_ == 0 || contentHeight_ == 0) ? " (ZERO)" : "",
                bufferWidth_, bufferHeight_,
                (bufferWidth_ == 0 || bufferHeight_ == 0) ? " (ZERO)" : "",
                outputWidth, outputHeight,
                (outputWidth <= 0 || outputHeight <= 0) ? " (ZERO)" : "");
            g_diagOutW = outputWidth;
            g_diagOutH = outputHeight;
            g_diagContentW = contentWidth_;
            g_diagContentH = contentHeight_;
            g_diagBufW = bufferWidth_;
            g_diagBufH = bufferHeight_;
        }
        if (diagPerSecond) {
            const uint64_t framesThisSecond = g_diagFrameCount - g_diagLastLogFrame;
            std::fprintf(
                stderr,
                "[%s] frames: %llu/s (total %llu) content=%ux%u buffer=%ux%u "
                "window=%dx%d\n",
                kPresentDiagTag,
                static_cast<unsigned long long>(framesThisSecond),
                static_cast<unsigned long long>(g_diagFrameCount),
                contentWidth_, contentHeight_, bufferWidth_, bufferHeight_,
                outputWidth, outputHeight);
            g_diagLastLogTime = diagNow;
            g_diagLastLogFrame = g_diagFrameCount;
        }
    }
    if (overlayEnabled_) {
        // Pause-menu overlay: opaque and sized to the window's output
        // pixels, so it is composited alone (no need to also blit the game
        // FBO underneath). uploadOverlayTextureLocked() is a no-op unless
        // the main thread staged a new frame via setOverlayFrame().
        uploadOverlayTextureLocked();
        if (overlayFramebuffer_ != 0 && overlayWidth_ > 0 && overlayHeight_ > 0 &&
            outputWidth > 0 && outputHeight > 0) {
            glDisable(GL_SCISSOR_TEST);
            glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
            glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
            glViewport(0, 0, outputWidth, outputHeight);
            glBindFramebuffer(GL_READ_FRAMEBUFFER, overlayFramebuffer_);
            glBlitFramebuffer(
                0, static_cast<GLint>(overlayHeight_),
                static_cast<GLint>(overlayWidth_), 0,
                0, 0, outputWidth, outputHeight, GL_COLOR_BUFFER_BIT, GL_LINEAR);
        }
    } else if (framebuffer_ != 0 && bufferWidth_ > 0 && bufferHeight_ > 0 &&
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

        // Bounded GL health check after the blit: logged only when the FBO is
        // incomplete or a GL error is present, and only when the observed
        // state changes (throttled to at most one line per 500ms if it keeps
        // flapping).
        if (diagnosticsEnabled) {
            const GLenum fbStatus = glCheckFramebufferStatus(GL_READ_FRAMEBUFFER);
            const GLenum blitError = glGetError();
            if ((fbStatus != GL_FRAMEBUFFER_COMPLETE || blitError != GL_NO_ERROR) &&
                (fbStatus != g_diagLastFbStatus || blitError != g_diagLastGlError)) {
                if (g_diagLastGlLogTime == std::chrono::steady_clock::time_point{} ||
                    diagNow - g_diagLastGlLogTime >= std::chrono::milliseconds(500)) {
                    std::fprintf(
                        stderr,
                        "[%s] gl: fbo-status=0x%lx error=0x%lx\n",
                        kPresentDiagTag,
                        static_cast<unsigned long>(fbStatus),
                        static_cast<unsigned long>(blitError));
                    g_diagLastGlLogTime = diagNow;
                }
                g_diagLastFbStatus = fbStatus;
                g_diagLastGlError = blitError;
            }
        }

        // Bounded pixel read-back, at most once per second: sample a few
        // points of the FBO over the exact content region the blit reads
        // (four corners + center) to tell whether the core actually produced
        // a non-black image. All-black here with nonzero content dims means
        // the image is being lost between the FBO and the window; all-black
        // with content dims 0 means the core emitted an empty frame.
        if (diagPerSecond) {
            const unsigned sx[5] = {0, srcW - 1, 0, srcW - 1, srcW / 2};
            const unsigned sy[5] = {0, 0, srcH - 1, srcH - 1, srcH / 2};
            unsigned nonBlack = 0;
            unsigned char pixel[4] = {0, 0, 0, 0};
            for (int i = 0; i < 5; ++i) {
                glReadPixels(
                    static_cast<GLint>(sx[i]), static_cast<GLint>(sy[i]), 1, 1,
                    GL_RGBA, GL_UNSIGNED_BYTE, pixel);
                if (pixel[0] != 0 || pixel[1] != 0 || pixel[2] != 0) ++nonBlack;
            }
            std::fprintf(
                stderr,
                "[%s] fb-readback: %u/5 samples non-black (content=%ux%u)\n",
                kPresentDiagTag, nonBlack, srcW, srcH);
        }
    }
    if (!overlayEnabled_ && scanlinesEnabled_.load() && outputWidth > 0 && outputHeight > 0) {
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
            destroyOverlayResourcesLocked();
            if (scanlineProgram_ != 0) {
                glDeleteProgram(scanlineProgram_);
                scanlineProgram_ = 0;
            }
            if (scanlineVertexArray_ != 0) {
                glDeleteVertexArrays(1, &scanlineVertexArray_);
                scanlineVertexArray_ = 0;
            }
        }
        if (window_ != nullptr) SDL_GL_MakeCurrent(window_, nullptr);
        SDL_GL_DestroyContext(context_);
        context_ = nullptr;
    }
    pendingWindow_ = nullptr;
    windowUpdatePending_ = false;
    surfaceAttached_ = false;
    overlayEnabled_ = false;
    overlayDirty_ = false;
    overlayStaging_.clear();
    overlayStaging_.shrink_to_fit();
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

void SdlHardwareContext::setOverlayFrame(
    const void* rgba, unsigned width, unsigned height, size_t pitch) {
    if (rgba == nullptr || width == 0 || height == 0) return;
    std::lock_guard<std::mutex> lock(mutex_);
    overlayStaging_.resize(static_cast<size_t>(width) * 4 * height);
    // Row-by-row copy: `pitch` is the source buffer's stride (e.g. an
    // SDL_Surface's row alignment) and may exceed width * 4, while
    // overlayStaging_ is always tightly packed for glTexSubImage2D.
    copyPackedRgbaRows(overlayStaging_.data(), rgba, width, height, pitch);
    overlayWidth_ = width;
    overlayHeight_ = height;
    overlayEnabled_ = true;
    overlayDirty_ = true;
}

void SdlHardwareContext::clearOverlay() {
    std::lock_guard<std::mutex> lock(mutex_);
    overlayEnabled_ = false;
}

bool SdlHardwareContext::createOverlayResourcesLocked() {
    destroyOverlayResourcesLocked();
    if (overlayWidth_ == 0 || overlayHeight_ == 0) return false;

    glGenTextures(1, &overlayTexture_);
    glBindTexture(GL_TEXTURE_2D, overlayTexture_);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D, 0, GL_RGBA8, static_cast<GLsizei>(overlayWidth_),
        static_cast<GLsizei>(overlayHeight_), 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);

    glGenFramebuffers(1, &overlayFramebuffer_);
    glBindFramebuffer(GL_FRAMEBUFFER, overlayFramebuffer_);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, overlayTexture_, 0);
    if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
        romm::log::sink().log(
            romm::log::Severity::Error, kTag, "overlay framebuffer is incomplete");
        destroyOverlayResourcesLocked();
        return false;
    }
    overlayTextureWidth_ = overlayWidth_;
    overlayTextureHeight_ = overlayHeight_;
    return true;
}

void SdlHardwareContext::destroyOverlayResourcesLocked() {
    if (overlayFramebuffer_ != 0) glDeleteFramebuffers(1, &overlayFramebuffer_);
    if (overlayTexture_ != 0) glDeleteTextures(1, &overlayTexture_);
    overlayFramebuffer_ = 0;
    overlayTexture_ = 0;
    overlayTextureWidth_ = 0;
    overlayTextureHeight_ = 0;
}

void SdlHardwareContext::uploadOverlayTextureLocked() {
    if (!overlayDirty_) return;
    if (overlayTexture_ == 0 || overlayTextureWidth_ != overlayWidth_ ||
        overlayTextureHeight_ != overlayHeight_) {
        if (!createOverlayResourcesLocked()) {
            overlayDirty_ = false;
            return;
        }
    }
    glBindTexture(GL_TEXTURE_2D, overlayTexture_);
    // The staged buffer is always tightly packed (setOverlayFrame() copies
    // out of the caller's possibly-wider pitch), so alignment 1 is both
    // correct and required for arbitrary widths.
    glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
    glTexSubImage2D(
        GL_TEXTURE_2D, 0, 0, 0, static_cast<GLsizei>(overlayWidth_),
        static_cast<GLsizei>(overlayHeight_), GL_RGBA, GL_UNSIGNED_BYTE,
        overlayStaging_.data());
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    overlayDirty_ = false;
}

bool SdlHardwareContext::createScanlineProgramLocked() {
    static constexpr const char* kEsVertexShader = R"(#version 300 es
        const vec2 positions[3] = vec2[3](
            vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
        );
        void main() {
            gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
        }
    )";
    static constexpr const char* kEsFragmentShader = R"(#version 300 es
        precision mediump float;
        uniform float rowHeight;
        out vec4 color;
        void main() {
            if ((int(floor(gl_FragCoord.y / rowHeight)) & 1) == 0) discard;
            color = vec4(0.0, 0.0, 0.0, 0.375);
        }
    )";
    static constexpr const char* kDesktopVertexShader = R"(
        #version 330 core
        const vec2 positions[3] = vec2[3](
            vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0)
        );
        void main() {
            gl_Position = vec4(positions[gl_VertexID], 0.0, 1.0);
        }
    )";
    static constexpr const char* kDesktopFragmentShader = R"(
        #version 330 core
        uniform float rowHeight;
        out vec4 color;
        void main() {
            if ((int(floor(gl_FragCoord.y / rowHeight)) & 1) == 0) discard;
            color = vec4(0.0, 0.0, 0.0, 0.375);
        }
    )";

    int profile = 0;
    SDL_GL_GetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, &profile);
    const bool isEs = profile == SDL_GL_CONTEXT_PROFILE_ES;
    const char* vertexSource = isEs ? kEsVertexShader : kDesktopVertexShader;
    const char* fragmentSource = isEs ? kEsFragmentShader : kDesktopFragmentShader;

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

    const GLuint vertexShader = compile(GL_VERTEX_SHADER, vertexSource);
    const GLuint fragmentShader = compile(GL_FRAGMENT_SHADER, fragmentSource);
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
    glGenVertexArrays(1, &scanlineVertexArray_);
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
    glBindVertexArray(scanlineVertexArray_);
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

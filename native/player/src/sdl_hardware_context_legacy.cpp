#include "native/player/sdl_hardware_context.h"

#include <SDL3/SDL.h>

#include <native/engine/LogSink.h>

#include <chrono>
#include <cstdio>
#include <string>

namespace romm::player {
namespace {

constexpr const char* kTag = "sdl_gl_context";
// Diagnostics tag for the Steam Deck direct-framebuffer present path. Kept
// distinct and low-noise (one line on any dims change, one line per second)
// so the next on-device test can read player.log and tell whether the core is
// actually producing frames (and at what content geometry) even while the
// window is black — i.e. distinguish "core emits no/empty frames" from a
// presentation problem. Written directly to stderr (captured to player.log)
// so it is independent of any log-level filtering.
constexpr const char* kPresentDiagTag = "deck_present";

void logSdlError(const char* operation) {
    romm::log::sink().log(
        romm::log::Severity::Error, kTag,
        std::string(operation) + " failed: " + SDL_GetError());
}

// File-local diagnostic state for the direct-framebuffer present path. Only
// the emulation thread calls swapBuffers(), and the deck player is a fresh
// process per session, so no locking/reset is needed here. Seeded to values
// that force a log on the first frame.
int g_diagOutW = -1;
int g_diagOutH = -1;
unsigned g_diagBufW = 0;
unsigned g_diagBufH = 0;
unsigned g_diagContentW = 0;
unsigned g_diagContentH = 0;
uint64_t g_diagFrameCount = 0;
uint64_t g_diagLastLogFrame = 0;
std::chrono::steady_clock::time_point g_diagLastLogTime{};

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

    int outputWidth = 0;
    int outputHeight = 0;
    SDL_GetWindowSizeInPixels(window_, &outputWidth, &outputHeight);

    // Bounded direct-path diagnostics (see kPresentDiagTag). This present path
    // has NO offscreen FBO (createFramebufferLocked() returns false and
    // currentFramebuffer() returns 0), so the core renders directly into the
    // window's default/back framebuffer and swapBuffers() merely swaps — there
    // is no engine-side blit to center or crop the content. These logs confirm
    // whether frames are flowing and at what content geometry, which is the
    // key evidence for whether a black screen is a core-side empty-frame issue
    // (content dims stay 0 / frame counter stalls) vs. a presentation problem.
    ++g_diagFrameCount;
    const bool dimsChanged =
        outputWidth != g_diagOutW || outputHeight != g_diagOutH ||
        contentWidth_ != g_diagContentW || contentHeight_ != g_diagContentH ||
        bufferWidth_ != g_diagBufW || bufferHeight_ != g_diagBufH;
    if (dimsChanged) {
        std::fprintf(
            stderr,
            "[%s] direct-present dims: content=%ux%u buffer=%ux%u window=%dx%d\n",
            kPresentDiagTag, contentWidth_, contentHeight_, bufferWidth_,
            bufferHeight_, outputWidth, outputHeight);
        g_diagOutW = outputWidth;
        g_diagOutH = outputHeight;
        g_diagContentW = contentWidth_;
        g_diagContentH = contentHeight_;
        g_diagBufW = bufferWidth_;
        g_diagBufH = bufferHeight_;
    }
    const auto now = std::chrono::steady_clock::now();
    if (g_diagLastLogTime == std::chrono::steady_clock::time_point{} ||
        now - g_diagLastLogTime >= std::chrono::seconds(1)) {
        const uint64_t framesThisSecond = g_diagFrameCount - g_diagLastLogFrame;
        std::fprintf(
            stderr,
            "[%s] direct-present frames: %llu/s (total %llu)\n", kPresentDiagTag,
            static_cast<unsigned long long>(framesThisSecond),
            static_cast<unsigned long long>(g_diagFrameCount));
        g_diagLastLogTime = now;
        g_diagLastLogFrame = g_diagFrameCount;
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

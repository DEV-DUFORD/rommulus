// sdl_hardware_context_software_only.cpp — ROMM_WIN32_SOFTWARE_ONLY boundary
// implementation of SdlHardwareContext (the full contract lives in
// native/player/include/native/player/sdl_hardware_context.h).
//
// Compiled INSTEAD of sdl_hardware_context.cpp when a WIN32 build sets
// ROMM_WIN32_SOFTWARE_ONLY=ON: it provides the same class, so the player
// links with no unresolved GL/ANGLE symbols and never includes <GLES3/gl3.h>
// or the ANGLE include directory, but it creates no render context and never
// touches EGL/GLES. createContext() fails, so any session that somehow
// reaches the hardware path takes the documented "failed to create context"
// failure instead of rendering. The player's launch gate (main.cpp) rejects
// known-hardware-rendering cores before this class is ever constructed; this
// no-op is the defense-in-depth second layer that guarantees no GL API can be
// reached even if a new hardware core id misses the classification list.
#include "native/player/sdl_hardware_context.h"

namespace romm::player {

SdlHardwareContext::SdlHardwareContext(
    SDL_Window* window,
    bool /*useOffscreenPresentation*/
)
    : window_(window), pendingWindow_(window), windowUpdatePending_(true) {}

SdlHardwareContext::~SdlHardwareContext() { destroyContext(); }

bool SdlHardwareContext::createContext() {
    // No hardware render context exists in this build: take the documented
    // "failed to create context" path instead of rendering.
    return false;
}

void SdlHardwareContext::setBufferGeometry(unsigned /*width*/, unsigned /*height*/) {}

void SdlHardwareContext::setContentGeometry(unsigned /*width*/, unsigned /*height*/) {}

void SdlHardwareContext::attachWindow(romm::video::NativeWindowHandle window) {
    pendingWindow_ = static_cast<SDL_Window*>(window);
    windowUpdatePending_ = true;
}

void SdlHardwareContext::detachWindow() {
    pendingWindow_ = nullptr;
    windowUpdatePending_ = true;
}

SdlHardwareContext::WindowUpdateResult SdlHardwareContext::applyPendingWindowUpdate() {
    // No surface can ever be created in this build.
    windowUpdatePending_ = false;
    return WindowUpdateResult::kNone;
}

bool SdlHardwareContext::hasPendingWindowUpdate() { return windowUpdatePending_; }

bool SdlHardwareContext::hasSurface() { return false; }

void SdlHardwareContext::unmakeCurrent() {}

bool SdlHardwareContext::makeCurrent() { return false; }

void SdlHardwareContext::setScanlines(bool /*enabled*/) {}

void SdlHardwareContext::setIntegerScaling(bool /*enabled*/) {}

void SdlHardwareContext::setSharpFilter(bool /*enabled*/) {}

bool SdlHardwareContext::swapBuffers() { return false; }

void SdlHardwareContext::destroyContext() {}

void* SdlHardwareContext::currentContext() const { return nullptr; }

uintptr_t SdlHardwareContext::currentFramebuffer() const { return 0; }

retro_proc_address_t SdlHardwareContext::getProcAddress(const char* /*name*/) {
    return nullptr;
}

bool SdlHardwareContext::isValid() const { return false; }

// Overlay compositing: the no-op context owns no GL resources to composite
// into; both entry points are safe no-ops.
void SdlHardwareContext::setOverlayFrame(
        const void* /*rgba*/, unsigned /*width*/, unsigned /*height*/, size_t /*pitch*/) {}

void SdlHardwareContext::clearOverlay() {}

// Private resource helpers: the no-op context owns no GL resources.
bool SdlHardwareContext::createFramebufferLocked() { return false; }

void SdlHardwareContext::destroyFramebufferLocked() {}

bool SdlHardwareContext::createScanlineProgramLocked() { return false; }

void SdlHardwareContext::drawScanlinesLocked(int /*outputWidth*/, int /*outputHeight*/) {}

bool SdlHardwareContext::createOverlayResourcesLocked() { return false; }

void SdlHardwareContext::destroyOverlayResourcesLocked() {}

void SdlHardwareContext::uploadOverlayTextureLocked() {}

}  // namespace romm::player

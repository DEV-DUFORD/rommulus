// HardwareContext.h — platform-neutral hardware-rendering context seam for
// the RomMulus native engine.
//
// LINUX_X64.md sections 11.2/11.6/11.7, Phase 7 Wave 4: the engine tree
// (native/engine/) must never include platform headers. Hardware-rendering
// cores (e.g. GLideN64) draw directly into the platform backbuffer and
// expect the host to provide a render context, bind the window surface,
// resolve procedure addresses, and swap buffers each frame. Platform code
// registers a HardwareContext implementation at startup; the emulation
// session consumes it exclusively through romm::gl::context().
//
// The default context (used when no platform context is registered) is a
// safe no-op: createContext() fails, so a session without a registered
// hardware platform takes the documented "failed to create context" path
// instead of crashing.
#pragma once

#include "libretro.h"

#include <native/engine/VideoSink.h>

#include <cstddef>
#include <cstdint>
#include <memory>

namespace romm::gl {

// The opaque window handle is shared with the software video seam.
using NativeWindowHandle = video::NativeWindowHandle;

// A platform-owned render context for hardware-rendering cores.
//
// Thread-safety contract (mirrored by the Android implementation):
//   - createContext()/destroyContext() run on the session's emulation
//     thread (the former before retro_run begins, the latter during
//     session teardown).
//   - attachWindow()/detachWindow() may be called from the UI thread; they
//     only queue the update. The emulation thread consumes queued updates
//     through applyPendingWindowUpdate(), keeping every context call and
//     core context callback on the same thread.
class HardwareContext {
public:
    enum class WindowUpdateResult {
        kNone,
        kAttached,
        kDetached,
        kFailed,
    };

    virtual ~HardwareContext() = default;

    // Creates the platform display, chooses a render config, and creates
    // the render context. Must be called from the emulation thread before
    // retro_run begins. Returns false if no context could be created.
    virtual bool createContext() = 0;

    // Sets the buffer geometry used when (re)creating the window surface.
    virtual void setBufferGeometry(unsigned width, unsigned height) = 0;

    // Queues a window owned by this context (takes ownership of the handle
    // reference). The emulation thread consumes it through
    // applyPendingWindowUpdate().
    virtual void attachWindow(NativeWindowHandle window) = 0;

    // Queues removal of the current window surface. Safe from any thread.
    virtual void detachWindow() = 0;

    // Applies a queued attach/detach on the calling emulation thread.
    virtual WindowUpdateResult applyPendingWindowUpdate() = 0;

    virtual bool hasPendingWindowUpdate() = 0;

    // Whether a window surface is currently bound to the context.
    virtual bool hasSurface() = 0;

    // Releases the context from the calling thread.
    virtual void unmakeCurrent() = 0;

    // Makes the existing context and surface current on the calling thread.
    // Used after temporarily yielding the window surface to another renderer.
    virtual bool makeCurrent() = 0;

    virtual void setScanlines(bool enabled) = 0;

    // Swaps the front/back buffers. Returns false on failure (e.g. context
    // lost). The caller should treat a false return as a signal to
    // re-attach the window and reset GL state.
    virtual bool swapBuffers() = 0;

    // Destroys the context and display. Called during session teardown.
    virtual void destroyContext() = 0;

    // The platform context handle to hand to the core via
    // RETRO_ENVIRONMENT_GET_HW_RENDER_INTERFACE, or nullptr while no
    // context exists.
    virtual void* currentContext() const = 0;

    // Framebuffer the core should render into. Platforms that render
    // directly to the window return 0; compositing frontends may return an
    // offscreen FBO and present it from swapBuffers().
    virtual uintptr_t currentFramebuffer() const = 0;

    // Resolves a GL procedure address for the core's
    // retro_hw_render_callback::get_proc_address. Returns nullptr when the
    // name is unknown or no context exists.
    virtual retro_proc_address_t getProcAddress(const char* name) = 0;

    virtual bool isValid() const = 0;
};

// Replaces the active context (takes ownership); pass nullptr to fall back
// to the default no-op context. Must complete before the first session
// starts — on Android the platform context is installed by a static
// initializer at library load time, before JNI_OnLoad.
void setContext(std::unique_ptr<HardwareContext> context);

// The active context, or the shared default no-op context when none is
// registered. Never returns a null reference.
HardwareContext& context();

}  // namespace romm::gl

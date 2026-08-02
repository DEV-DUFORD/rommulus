// gl_context.h — EGL / OpenGL ES context manager for hardware-rendering cores.
//
// GLideN64 (Mupen64Plus-Next) draws directly into an OpenGL ES backbuffer
// and expects the host to provide an EGL display + context, attach an
// ANativeWindow as the EGL surface, and swap buffers each frame.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window.h>
#include <condition_variable>
#include <mutex>

namespace romm {

class GlContextManager {
public:
    enum class WindowUpdateResult {
        kNone,
        kAttached,
        kDetached,
        kFailed,
    };

    GlContextManager() = default;
    ~GlContextManager();

    GlContextManager(const GlContextManager&) = delete;
    GlContextManager& operator=(const GlContextManager&) = delete;

    // Creates EGL display, chooses a config (RGBA8888 + depth 16), and
    // creates a GLES3 context (falls back to GLES2 if ES3 is unavailable).
    // Must be called from the emulation thread before retro_run begins.
    bool createDisplay();

    void setBufferGeometry(unsigned width, unsigned height);

    // Queues an ANativeWindow owned by this manager. The emulation thread
    // consumes it through applyPendingWindowUpdate(), keeping every EGL call
    // and core context callback on the same thread.
    void attachWindow(ANativeWindow* window);

    // Queues removal of the current window surface. Safe from any thread.
    void detachWindow();

    // Applies a queued attach/detach on the calling emulation thread.
    WindowUpdateResult applyPendingWindowUpdate();

    bool hasPendingWindowUpdate();

    // Releases the context from the calling thread.
    void unmakeCurrent();

    // Swaps the front/back buffers. Returns false on failure (e.g. context
    // lost). The caller should treat a false return as a signal to
    // re-attach the window and reset GL state.
    bool swapBuffers();

    // Destroys the EGL context and display. Called during session teardown.
    void destroyDisplay();

    // Accessors
    EGLContext eglContext() const { return context_; }
    EGLDisplay eglDisplay() const { return display_; }
    bool isValid() const { return context_ != EGL_NO_CONTEXT; }
    bool hasSurface();

private:
    std::mutex mutex_;
    std::condition_variable windowUpdateApplied_;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig  config_  = EGL_NO_CONFIG_KHR;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    ANativeWindow* pendingWindow_ = nullptr;
    bool windowUpdatePending_ = false;
    int32_t bufferWidth_ = 0;
    int32_t bufferHeight_ = 0;
};

}  // namespace romm

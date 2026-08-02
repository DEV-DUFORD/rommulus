// gl_context.h — EGL / OpenGL ES context manager for hardware-rendering cores.
//
// GLideN64 (Mupen64Plus-Next) draws directly into an OpenGL ES backbuffer
// and expects the host to provide an EGL display + context, attach an
// ANativeWindow as the EGL surface, and swap buffers each frame.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <android/native_window.h>
#include <mutex>

namespace romm {

class GlContextManager {
public:
    GlContextManager() = default;
    ~GlContextManager();

    GlContextManager(const GlContextManager&) = delete;
    GlContextManager& operator=(const GlContextManager&) = delete;

    // Creates EGL display, chooses a config (RGBA8888 + depth 16), and
    // creates a GLES3 context (falls back to GLES2 if ES3 is unavailable).
    // Must be called from the emulation thread before retro_run begins.
    bool createDisplay();

    // Attaches an ANativeWindow as the EGL window surface and makes the
    // context current. Destroys any previously attached surface first.
    // Safe to call from the UI thread (serialized via mutex).
    bool attachWindow(ANativeWindow* window);

    // Detaches the current window surface. Safe from any thread.
    void detachWindow();

    // Makes the context current on the calling thread (uses the last
    // attached surface, or EGL_NO_SURFACE if nothing is attached).
    void makeCurrent();

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
    bool hasSurface() const { return surface_ != EGL_NO_SURFACE; }

private:
    std::mutex mutex_;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLConfig  config_  = EGL_NO_CONFIG_KHR;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
};

}  // namespace romm

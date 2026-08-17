// AndroidHardwareContext.cpp — Android's HardwareContext (Phase 7 Wave 4).
//
// Absorbs the EGL context manager that used to live in
// the legacy gl_context.cpp: EGL display initialization, RGBA8888 +
// depth-16 config selection, GLES3-with-GLES2-fallback context creation,
// the queued window-surface attach/detach protocol, buffer swap, and
// teardown. Behavior is unchanged; only the ownership boundary moved —
// the emulation session now talks to romm::gl::context() instead of
// constructing this class directly.
#include <native/platform/android/AndroidHardwareContext.h>

#include <native/engine/LogSink.h>

#include <android/native_window.h>

#include <cstdarg>
#include <cstdio>
#include <memory>

namespace romm::android {

namespace {

// Same logcat tag as the former GlContextManager, so existing diagnostics
// and log filters keep working through the engine's LogSink registry.
constexpr const char* kLogTag = "romm_gl_context";

void logAt(romm::log::Severity severity, const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    romm::log::sink().log(severity, kLogTag, buffer);
}

EGLint eglError(const char* msg) {
    EGLint err = eglGetError();
    logAt(romm::log::Severity::Error, "%s: EGL error 0x%x", msg, err);
    return err;
}

// Registers AndroidHardwareContext as the engine's active hardware context
// at library load time: static initializers in a shared library run when
// the library is loaded, before JNI_OnLoad, so jni_bridge.cpp stays
// untouched.
struct HardwareContextRegistrar {
    HardwareContextRegistrar() {
        romm::gl::setContext(std::make_unique<AndroidHardwareContext>());
    }
};
const HardwareContextRegistrar kHardwareContextRegistrar;

}  // namespace

AndroidHardwareContext::~AndroidHardwareContext() {
    destroyContext();
}

bool AndroidHardwareContext::createContext() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        logAt(romm::log::Severity::Error, "eglGetDisplay failed");
        return false;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(display_, &major, &minor)) {
        eglError("eglInitialize");
        display_ = EGL_NO_DISPLAY;
        return false;
    }
    logAt(romm::log::Severity::Info, "EGL initialized %d.%d", major, minor);

    // Request a config compatible with an ES3 context. Request ES2 bit
    // so the config is pickable on devices where ES3 configs are rare,
    // then create an ES3 context on top.
    const EGLint configAttribs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_RED_SIZE, 8,
        EGL_GREEN_SIZE, 8,
        EGL_BLUE_SIZE, 8,
        EGL_ALPHA_SIZE, 8,
        EGL_DEPTH_SIZE, 16,
        EGL_NONE
    };

    EGLint numConfigs = 0;
    if (!eglChooseConfig(display_, configAttribs, &config_, 1, &numConfigs) ||
        numConfigs == 0) {
        eglError("eglChooseConfig");
        config_ = EGL_NO_CONFIG_KHR;
        return false;
    }

    // Try GLES3 first, fall back to GLES2.
    const EGLint ctxAttribs3[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs3);

    if (context_ == EGL_NO_CONTEXT) {
        logAt(romm::log::Severity::Warn, "GLES3 context creation failed, falling back to GLES2");
        const EGLint ctxAttribs2[] = {
            EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL_NONE
        };
        context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ctxAttribs2);
    }

    if (context_ == EGL_NO_CONTEXT) {
        eglError("eglCreateContext (both ES3 and ES2)");
        return false;
    }

    // Query the actual context version.
    EGLint clientVersion = 0;
    eglQueryContext(display_, context_, EGL_CONTEXT_CLIENT_VERSION, &clientVersion);
    logAt(romm::log::Severity::Info, "EGL context created (GLES %d)", clientVersion);

    return true;
}

void AndroidHardwareContext::setBufferGeometry(unsigned width, unsigned height) {
    std::lock_guard<std::mutex> lock(mutex_);
    bufferWidth_ = static_cast<int32_t>(width);
    bufferHeight_ = static_cast<int32_t>(height);
}

void AndroidHardwareContext::attachWindow(romm::video::NativeWindowHandle window) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
    }
    pendingWindow_ = static_cast<ANativeWindow*>(window);
    windowUpdatePending_ = true;
}

void AndroidHardwareContext::detachWindow() {
    std::unique_lock<std::mutex> lock(mutex_);

    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    windowUpdatePending_ = true;
    if (display_ != EGL_NO_DISPLAY) {
        windowUpdateApplied_.wait(lock, [this]() {
            return !windowUpdatePending_ || display_ == EGL_NO_DISPLAY;
        });
    }
}

romm::gl::HardwareContext::WindowUpdateResult AndroidHardwareContext::applyPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!windowUpdatePending_) {
        return WindowUpdateResult::kNone;
    }
    windowUpdatePending_ = false;
    windowUpdateApplied_.notify_all();

    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
        logAt(romm::log::Severity::Error, "window update applied before createDisplay");
        if (pendingWindow_ != nullptr) {
            ANativeWindow_release(pendingWindow_);
            pendingWindow_ = nullptr;
        }
        return WindowUpdateResult::kFailed;
    }

    if (surface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }

    if (pendingWindow_ == nullptr) {
        logAt(romm::log::Severity::Info, "window surface detached");
        return WindowUpdateResult::kDetached;
    }

    if (bufferWidth_ > 0 && bufferHeight_ > 0 &&
        ANativeWindow_setBuffersGeometry(
            pendingWindow_, bufferWidth_, bufferHeight_, WINDOW_FORMAT_RGBA_8888) != 0) {
        logAt(romm::log::Severity::Error, "ANativeWindow_setBuffersGeometry failed for %dx%d",
              bufferWidth_, bufferHeight_);
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
        return WindowUpdateResult::kFailed;
    }

    surface_ = eglCreateWindowSurface(display_, config_, pendingWindow_, nullptr);
    ANativeWindow_release(pendingWindow_);
    pendingWindow_ = nullptr;
    if (surface_ == EGL_NO_SURFACE) {
        eglError("eglCreateWindowSurface");
        return WindowUpdateResult::kFailed;
    }

    if (!eglMakeCurrent(display_, surface_, surface_, context_)) {
        eglError("eglMakeCurrent (window update)");
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
        return WindowUpdateResult::kFailed;
    }

    logAt(romm::log::Severity::Info, "window surface attached at %dx%d", bufferWidth_, bufferHeight_);
    return WindowUpdateResult::kAttached;
}

bool AndroidHardwareContext::hasPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);
    return windowUpdatePending_;
}

bool AndroidHardwareContext::hasSurface() {
    std::lock_guard<std::mutex> lock(mutex_);
    return surface_ != EGL_NO_SURFACE;
}

void AndroidHardwareContext::unmakeCurrent() {
    if (display_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

bool AndroidHardwareContext::swapBuffers() {
    if (!eglSwapBuffers(display_, surface_)) {
        EGLint err = eglGetError();
        if (err == EGL_CONTEXT_LOST) {
            logAt(romm::log::Severity::Error, "EGL context lost during swapBuffers");
        } else {
            logAt(romm::log::Severity::Error, "eglSwapBuffers failed: 0x%x", err);
        }
        return false;
    }
    return true;
}

void AndroidHardwareContext::destroyContext() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
        pendingWindow_ = nullptr;
    }
    windowUpdatePending_ = false;
    windowUpdateApplied_.notify_all();

    if (surface_ != EGL_NO_SURFACE) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(display_, surface_);
        surface_ = EGL_NO_SURFACE;
    }

    if (context_ != EGL_NO_CONTEXT) {
        eglDestroyContext(display_, context_);
        context_ = EGL_NO_CONTEXT;
    }

    if (display_ != EGL_NO_DISPLAY) {
        eglTerminate(display_);
        display_ = EGL_NO_DISPLAY;
    }
}

void* AndroidHardwareContext::currentContext() const {
    return context_;
}

retro_proc_address_t AndroidHardwareContext::getProcAddress(const char* name) {
    // eglGetProcAddress returns __eglMustCastToProperFunctionPointerType
    // (aka void(*)()), which is the same as retro_proc_address_t.
    return reinterpret_cast<retro_proc_address_t>(eglGetProcAddress(name));
}

bool AndroidHardwareContext::isValid() const {
    return context_ != EGL_NO_CONTEXT;
}

}  // namespace romm::android

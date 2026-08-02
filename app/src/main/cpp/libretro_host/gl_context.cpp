#include "gl_context.h"

#include <android/log.h>
#include <cstring>
#include <thread>
#include <chrono>

#define LOG_TAG "romm_gl_context"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace romm {

namespace {

EGLint eglError(const char* msg) {
    EGLint err = eglGetError();
    LOGE("%s: EGL error 0x%x", msg, err);
    return err;
}

}  // namespace

GlContextManager::~GlContextManager() {
    destroyDisplay();
}

bool GlContextManager::createDisplay() {
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed");
        return false;
    }

    EGLint major = 0, minor = 0;
    if (!eglInitialize(display_, &major, &minor)) {
        eglError("eglInitialize");
        display_ = EGL_NO_DISPLAY;
        return false;
    }
    LOGI("EGL initialized %d.%d", major, minor);

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
        LOGW("GLES3 context creation failed, falling back to GLES2");
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
    LOGI("EGL context created (GLES %d)", clientVersion);

    return true;
}

void GlContextManager::setBufferGeometry(unsigned width, unsigned height) {
    std::lock_guard<std::mutex> lock(mutex_);
    bufferWidth_ = static_cast<int32_t>(width);
    bufferHeight_ = static_cast<int32_t>(height);
}

void GlContextManager::attachWindow(ANativeWindow* window) {
    std::lock_guard<std::mutex> lock(mutex_);

    if (pendingWindow_ != nullptr) {
        ANativeWindow_release(pendingWindow_);
    }
    pendingWindow_ = window;
    windowUpdatePending_ = true;
}

void GlContextManager::detachWindow() {
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

GlContextManager::WindowUpdateResult GlContextManager::applyPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);

    if (!windowUpdatePending_) {
        return WindowUpdateResult::kNone;
    }
    windowUpdatePending_ = false;
    windowUpdateApplied_.notify_all();

    if (display_ == EGL_NO_DISPLAY || context_ == EGL_NO_CONTEXT) {
        LOGE("window update applied before createDisplay");
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
        LOGI("window surface detached");
        return WindowUpdateResult::kDetached;
    }

    if (bufferWidth_ > 0 && bufferHeight_ > 0 &&
        ANativeWindow_setBuffersGeometry(
            pendingWindow_, bufferWidth_, bufferHeight_, WINDOW_FORMAT_RGBA_8888) != 0) {
        LOGE("ANativeWindow_setBuffersGeometry failed for %dx%d", bufferWidth_, bufferHeight_);
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

    LOGI("window surface attached at %dx%d", bufferWidth_, bufferHeight_);
    return WindowUpdateResult::kAttached;
}

bool GlContextManager::hasPendingWindowUpdate() {
    std::lock_guard<std::mutex> lock(mutex_);
    return windowUpdatePending_;
}

bool GlContextManager::hasSurface() {
    std::lock_guard<std::mutex> lock(mutex_);
    return surface_ != EGL_NO_SURFACE;
}

void GlContextManager::unmakeCurrent() {
    if (display_ == EGL_NO_DISPLAY) return;
    eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

bool GlContextManager::swapBuffers() {
    if (!eglSwapBuffers(display_, surface_)) {
        EGLint err = eglGetError();
        if (err == EGL_CONTEXT_LOST) {
            LOGE("EGL context lost during swapBuffers");
        } else {
            LOGE("eglSwapBuffers failed: 0x%x", err);
        }
        return false;
    }
    return true;
}

void GlContextManager::destroyDisplay() {
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

}  // namespace romm

// AndroidHardwareContext.h — HardwareContext implementation backed by
// EGL / OpenGL ES.
//
// LINUX_X64.md sections 11.2/11.6/11.7, Phase 7 Wave 4: absorbs the EGL
// context manager that used to live in the legacy gl_context.cpp:
// display/config/context creation, window surface binding, buffer swap,
// and teardown. Behavior is unchanged; only the ownership boundary moved —
// the emulation session now talks to romm::gl::context() instead of
// constructing this class directly.
//
// It is registered as the engine's active context by a static initializer
// in AndroidHardwareContext.cpp, so engine code never touches the EGL
// headers directly and jni_bridge.cpp needs no changes beyond casting its
// platform window pointer to the engine's opaque handle.
#pragma once

#include <EGL/egl.h>
#include <EGL/eglext.h>

#include <native/engine/HardwareContext.h>

#include <condition_variable>
#include <cstdint>
#include <mutex>

struct ANativeWindow;

namespace romm::android {

class AndroidHardwareContext final : public romm::gl::HardwareContext {
public:
    ~AndroidHardwareContext() override;

    bool createContext() override;
    void setBufferGeometry(unsigned width, unsigned height) override;
    void attachWindow(romm::video::NativeWindowHandle window) override;
    void detachWindow() override;
    WindowUpdateResult applyPendingWindowUpdate() override;
    bool hasPendingWindowUpdate() override;
    bool hasSurface() override;
    void unmakeCurrent() override;
    bool makeCurrent() override;
    bool swapBuffers() override;
    void destroyContext() override;
    void* currentContext() const override;
    uintptr_t currentFramebuffer() const override;
    retro_proc_address_t getProcAddress(const char* name) override;
    bool isValid() const override;

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

}  // namespace romm::android

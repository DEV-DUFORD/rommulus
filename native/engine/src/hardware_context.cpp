// hardware_context.cpp — registry backing romm::gl::setContext()/context()
// (Phase 7 Wave 4).
#include <native/engine/HardwareContext.h>

#include <mutex>

namespace romm::gl {

namespace {

// Shared fallback context: reports that no hardware-rendering context can
// be created, so a session running before any platform context is
// registered (or on a platform that never registers one) takes the
// documented "failed to create context" path instead of crashing. The
// default cannot release a window handle it does not understand, so
// attachWindow() deliberately leaks rather than crash; on real platforms
// the registered context takes ownership.
class NoOpContext final : public HardwareContext {
public:
    bool createContext() override { return false; }
    void setBufferGeometry(unsigned /*width*/, unsigned /*height*/) override {}
    void attachWindow(NativeWindowHandle /*window*/) override {}
    void detachWindow() override {}
    WindowUpdateResult applyPendingWindowUpdate() override { return WindowUpdateResult::kNone; }
    bool hasPendingWindowUpdate() override { return false; }
    bool hasSurface() override { return false; }
    void unmakeCurrent() override {}
    bool makeCurrent() override { return false; }
    bool swapBuffers() override { return false; }
    void destroyContext() override {}
    void* currentContext() const override { return nullptr; }
    uintptr_t currentFramebuffer() const override { return 0; }
    retro_proc_address_t getProcAddress(const char* /*name*/) override { return nullptr; }
    bool isValid() const override { return false; }
};

NoOpContext& defaultContext() {
    static NoOpContext context;
    return context;
}

std::mutex& registryMutex() {
    static std::mutex mutex;
    return mutex;
}

std::unique_ptr<HardwareContext>& activeContext() {
    static std::unique_ptr<HardwareContext> context;
    return context;
}

}  // namespace

void setContext(std::unique_ptr<HardwareContext> context) {
    std::lock_guard<std::mutex> lock(registryMutex());
    activeContext() = std::move(context);
}

HardwareContext& context() {
    std::lock_guard<std::mutex> lock(registryMutex());
    if (activeContext() != nullptr) return *activeContext();
    return defaultContext();
}

}  // namespace romm::gl

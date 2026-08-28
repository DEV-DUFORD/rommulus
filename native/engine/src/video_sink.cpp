// video_sink.cpp — registry backing romm::video::setSink()/sink()
// (Phase 7 Wave 4).
#include <native/engine/VideoSink.h>

#include <mutex>

namespace romm::video {

namespace {

// Shared fallback sink: discards every frame and window. A session running
// before any platform sink is registered (or on a platform that never
// registers one) simply renders nothing — there is no display target. The
// default cannot release a window handle it does not understand, so
// attachWindow() deliberately leaks rather than crash; on real platforms
// the registered sink takes ownership.
class NoOpSink final : public VideoSink {
public:
    void attachWindow(NativeWindowHandle /*window*/) override {}
    void detachWindow() override {}
    void submitFrame(const void* /*data*/, unsigned /*width*/, unsigned /*height*/,
                     size_t /*pitch*/, enum retro_pixel_format /*format*/) override {}
};

NoOpSink& defaultSink() {
    static NoOpSink sink;
    return sink;
}

std::mutex& registryMutex() {
    static std::mutex mutex;
    return mutex;
}

std::unique_ptr<VideoSink>& activeSink() {
    static std::unique_ptr<VideoSink> sink;
    return sink;
}

}  // namespace

void setSink(std::unique_ptr<VideoSink> sink) {
    std::lock_guard<std::mutex> lock(registryMutex());
    activeSink() = std::move(sink);
}

VideoSink& sink() {
    std::lock_guard<std::mutex> lock(registryMutex());
    if (activeSink() != nullptr) return *activeSink();
    return defaultSink();
}

}  // namespace romm::video

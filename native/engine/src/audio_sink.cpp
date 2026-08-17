// audio_sink.cpp — registry backing romm::audio::setSink()/sink()
// (Phase 7 Wave 3).
#include <native/engine/AudioSink.h>

#include <mutex>

namespace romm::audio {

namespace {

// Shared fallback sink: discards all samples and reports zero diagnostics.
// start() returns true so a session running before any platform sink is
// registered (or on a platform that never registers one) proceeds without
// an error log — there is simply no audio.
class NoOpSink final : public AudioSink {
public:
    bool start(const StartConfig& /*config*/) override { return true; }
    void stop() override {}
    void pushSamples(const int16_t* /*interleaved*/, size_t /*frames*/) override {}
    void pause() override {}
    void resume() override {}
    uint64_t underrunFrames() const override { return 0; }
    uint64_t overrunFrames() const override { return 0; }
};

NoOpSink& defaultSink() {
    static NoOpSink sink;
    return sink;
}

std::mutex& registryMutex() {
    static std::mutex mutex;
    return mutex;
}

std::unique_ptr<AudioSink>& activeSink() {
    static std::unique_ptr<AudioSink> sink;
    return sink;
}

}  // namespace

void setSink(std::unique_ptr<AudioSink> sink) {
    std::lock_guard<std::mutex> lock(registryMutex());
    activeSink() = std::move(sink);
}

AudioSink& sink() {
    std::lock_guard<std::mutex> lock(registryMutex());
    if (activeSink() != nullptr) return *activeSink();
    return defaultSink();
}

}  // namespace romm::audio

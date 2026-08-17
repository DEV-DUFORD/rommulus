// OboeAudioSink.h — AudioSink implementation backed by Oboe
// (Phase 7 Wave 3).
//
// LINUX_X64.md sections 11/14: the only platform audio sink on Android. It
// is registered as the engine's active sink by a static initializer in
// OboeAudioSink.cpp, so EmulationSession never touches Oboe directly and
// jni_bridge.cpp needs no changes.
//
// Ownership/thread-safety contract (unchanged from the former AudioOutput):
//   - start()/stop() are called from EmulationSession::start()/stop(), which
//     run on the JNI-calling thread (never the emulation thread or the Oboe
//     callback thread).
//   - pushSamples() is called only from the emulation thread (inside the
//     audio trampolines).
//   - onAudioReady() runs on Oboe's own realtime audio callback thread. It
//     touches only the lock-free AudioRingBuffer and atomics — no
//     allocation, no locks, no logging, no JNI, no filesystem access.
//   - onErrorAfterClose() runs on a separate (non-realtime) Oboe thread
//     after the stream has already been closed, so logging and a bounded
//     one-shot restart attempt are safe there.
#pragma once

#include <native/engine/AudioSink.h>

#include <oboe/Oboe.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>

namespace romm {
class AudioRingBuffer;  // Defined in the host tree; owned by the adapter.
}  // namespace romm

namespace romm::android {

class OboeAudioSink final : public romm::audio::AudioSink,
                            public oboe::AudioStreamCallback {
public:
    OboeAudioSink() = default;
    ~OboeAudioSink() override;

    // romm::audio::AudioSink
    bool start(const romm::audio::StartConfig& config) override;
    void stop() override;
    void pushSamples(const int16_t* interleaved, size_t frames) override;
    void pause() override;
    void resume() override;
    uint64_t underrunFrames() const override;
    uint64_t overrunFrames() const override;

    // oboe::AudioStreamCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<romm::AudioRingBuffer> ring_;
    double sampleRate_ = 0.0;
    double prebufferSeconds_ = 0.0;
    size_t prebufferFrames_ = 0;
    std::atomic<uint64_t> underrunFrames_{0};
    std::atomic<uint64_t> overrunFrames_{0};
    std::atomic<bool> restarting_{false};
    std::atomic<bool> streamStartRequested_{false};
};

}  // namespace romm::android

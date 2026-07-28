// audio_output.h — real Oboe-backed audio playback of the core's stereo
// PCM stream (LIBRETRO_REFACTOR.md section 8.2).
//
// Ownership/thread-safety contract:
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

#include "audio_ring_buffer.h"

#include <oboe/Oboe.h>

#include <atomic>
#include <cstdint>
#include <memory>

namespace romm {

class AudioOutput : public oboe::AudioStreamCallback {
public:
    AudioOutput() = default;
    ~AudioOutput() override;

    AudioOutput(const AudioOutput&) = delete;
    AudioOutput& operator=(const AudioOutput&) = delete;

    // Opens and starts a low-latency Oboe output stream at (or converted
    // from) coreSampleRate. Returns false on failure; the session continues
    // running without audio in that case rather than failing the whole
    // launch over it.
    bool start(double coreSampleRate);

    // Stops and closes the stream. Safe to call even if start() was never
    // called or already failed.
    void stop();

    // Producer-only (emulation thread): pushes `frames` interleaved stereo
    // frames into the ring buffer. Frames that don't fit are dropped and
    // counted as overrun, never blocking the emulation thread.
    void pushSamples(const int16_t* interleaved, size_t frames);

    uint64_t underrunFrames() const { return underrunFrames_.load(std::memory_order_relaxed); }
    uint64_t overrunFrames() const { return overrunFrames_.load(std::memory_order_relaxed); }

    // oboe::AudioStreamCallback
    oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream, void* audioData,
                                          int32_t numFrames) override;
    void onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) override;

private:
    std::shared_ptr<oboe::AudioStream> stream_;
    std::unique_ptr<AudioRingBuffer> ring_;
    double sampleRate_ = 0.0;
    std::atomic<uint64_t> underrunFrames_{0};
    std::atomic<uint64_t> overrunFrames_{0};
    std::atomic<bool> restarting_{false};
};

}  // namespace romm

// OboeAudioSink.cpp — Android's AudioSink (Phase 7 Wave 3).
//
// Absorbs the Oboe stream logic that used to live in
// the legacy audio_output.cpp (LIBRETRO_REFACTOR.md section 8.2): the
// low-latency shared output stream, the lock-free ring buffer, prebuffered
// start, underrun/overrun accounting, and the bounded one-shot reopen on
// device loss. Behavior is unchanged; only the ownership boundary moved —
// the emulation session now talks to romm::audio::sink() instead of
// constructing this class directly.
#include <native/platform/android/OboeAudioSink.h>

#include "audio_ring_buffer.h"

#include <native/engine/LogSink.h>

#include <algorithm>
#include <cstdarg>
#include <cstdio>
#include <memory>

namespace romm::android {

namespace {

// Size the ring in a few audio bursts, not one large arbitrary latency
// buffer (LIBRETRO_REFACTOR.md section 8.2): ~400ms of headroom at the
// core's own sample rate absorbs scheduler jitter (measured on the
// physical Google TV Streamer: a 200ms ring showed a small, non-zero,
// roughly-constant underrun rate around 0.15-0.35% of produced audio
// frames during clean, uninterrupted runs; 400ms gives more margin against
// the occasional multi-callback-period stall without adding perceptible
// latency) without adding much perceptible latency.
constexpr double kRingBufferSeconds = 0.4;
constexpr size_t kMinRingFrames = 1024;

// Same logcat tag as the former AudioOutput, so existing diagnostics and
// log filters keep working through the engine's LogSink registry.
constexpr const char* kLogTag = "romm_audio_output";

void logAt(romm::log::Severity severity, const char* fmt, ...) {
    char buffer[512];
    va_list args;
    va_start(args, fmt);
    std::vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);
    romm::log::sink().log(severity, kLogTag, buffer);
}

// Registers OboeAudioSink as the engine's active audio sink at library
// load time: static initializers in a shared library run when the library
// is loaded, before JNI_OnLoad, so jni_bridge.cpp stays untouched.
struct AudioSinkRegistrar {
    AudioSinkRegistrar() { romm::audio::setSink(std::make_unique<OboeAudioSink>()); }
};
const AudioSinkRegistrar kAudioSinkRegistrar;

}  // namespace

OboeAudioSink::~OboeAudioSink() {
    stop();
}

bool OboeAudioSink::start(const romm::audio::StartConfig& config) {
    sampleRate_ = config.sampleRate > 0.0 ? config.sampleRate : 44100.0;
    prebufferSeconds_ = std::max(config.prebufferSeconds, 0.0);
    prebufferFrames_ = static_cast<size_t>(sampleRate_ * prebufferSeconds_);
    const size_t capacityFrames = std::max<size_t>(
        static_cast<size_t>(sampleRate_ * kRingBufferSeconds), kMinRingFrames);
    ring_ = std::make_unique<romm::AudioRingBuffer>(capacityFrames);
    underrunFrames_.store(0, std::memory_order_relaxed);
    overrunFrames_.store(0, std::memory_order_relaxed);
    streamStartRequested_.store(false, std::memory_order_relaxed);

    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Output)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
        // Shared mode is resilient when Android TV system audio services
        // start or stop their own streams. Exclusive streams on the target
        // Google TV Streamer were repeatedly disconnected within seconds.
        ->setSharingMode(oboe::SharingMode::Shared)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setFormat(oboe::AudioFormat::I16)
        ->setSampleRate(static_cast<int32_t>(sampleRate_))
        // Prefer the core's own sample rate and let Oboe convert to
        // whatever the device stream actually opens at, per section 8.2:
        // "Start with Oboe's supported conversion path."
        ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
        ->setCallback(this);

    oboe::Result result = builder.openStream(stream_);
    if (result != oboe::Result::OK) {
        logAt(romm::log::Severity::Error, "openStream failed: %s",
              oboe::convertToText(result));
        stream_.reset();
        ring_.reset();
        return false;
    }

    if (prebufferFrames_ == 0) {
        result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            logAt(romm::log::Severity::Error, "requestStart failed: %s",
                  oboe::convertToText(result));
            stream_->close();
            stream_.reset();
            ring_.reset();
            return false;
        }
        streamStartRequested_.store(true, std::memory_order_relaxed);
    }

    logAt(romm::log::Severity::Info,
          "audio stream opened: requested=%.1fHz actual=%dHz capacityFrames=%zu prebufferFrames=%zu",
          sampleRate_, stream_->getSampleRate(), capacityFrames, prebufferFrames_);
    return true;
}

void OboeAudioSink::stop() {
    if (stream_) {
        // stop() blocks until the stream is actually stopped, so no
        // onAudioReady call can still be in flight once this returns —
        // only then is it safe to free the ring buffer below.
        stream_->stop();
        stream_->close();
        stream_.reset();
    }
    ring_.reset();
    // A deliberate session stop starts a new one-shot restart budget. Do not
    // reset this in start(): onErrorAfterClose() calls start() itself, and
    // doing so there would turn the bounded retry into an infinite loop.
    restarting_.store(false, std::memory_order_relaxed);
}

void OboeAudioSink::pushSamples(const int16_t* interleaved, size_t frames) {
    if (!ring_) return;
    const size_t written = ring_->write(interleaved, frames);
    if (written < frames) {
        overrunFrames_.fetch_add(frames - written, std::memory_order_relaxed);
    }

    if (!streamStartRequested_.load(std::memory_order_relaxed) &&
        ring_->availableFrames() >= prebufferFrames_ &&
        !streamStartRequested_.exchange(true, std::memory_order_relaxed)) {
        const oboe::Result result = stream_->requestStart();
        if (result != oboe::Result::OK) {
            logAt(romm::log::Severity::Error, "deferred requestStart failed: %s",
                  oboe::convertToText(result));
        } else {
            logAt(romm::log::Severity::Info,
                  "audio stream started after prebuffering %zu frames",
                  ring_->availableFrames());
        }
    }
}

void OboeAudioSink::pause() {
    // No-op: the session implements pause by simply not pushing samples
    // (runLoop skips retro_run()), so the ring drains and onAudioReady
    // fills silence — the stream itself is never paused. Pausing the Oboe
    // stream here would change the existing runtime behavior.
}

void OboeAudioSink::resume() {
    // No-op, paired with pause(): samples flow again as soon as the
    // emulation thread pushes them.
}

uint64_t OboeAudioSink::underrunFrames() const {
    return underrunFrames_.load(std::memory_order_relaxed);
}

uint64_t OboeAudioSink::overrunFrames() const {
    return overrunFrames_.load(std::memory_order_relaxed);
}

oboe::DataCallbackResult OboeAudioSink::onAudioReady(oboe::AudioStream* /*stream*/,
                                                     void* audioData,
                                                     int32_t numFrames) {
    auto* out = static_cast<int16_t*>(audioData);
    const size_t requested = static_cast<size_t>(numFrames);
    const size_t framesRead = ring_ ? ring_->read(out, requested) : 0;

    if (framesRead < requested) {
        const size_t missing = requested - framesRead;
        // Fill the shortfall with silence rather than blocking or leaving
        // stale/uninitialized samples in the output buffer.
        std::fill(out + framesRead * 2, out + requested * 2, static_cast<int16_t>(0));
        underrunFrames_.fetch_add(missing, std::memory_order_relaxed);
    }

    return oboe::DataCallbackResult::Continue;
}

void OboeAudioSink::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // This runs on a separate, non-realtime Oboe thread after the stream
    // has already been closed, so logging and a bounded restart attempt
    // are both safe here (unlike onAudioReady).
    logAt(romm::log::Severity::Error, "audio stream error after close: %s",
          oboe::convertToText(error));

    if (restarting_.exchange(true, std::memory_order_relaxed)) {
        logAt(romm::log::Severity::Error,
              "audio stream already attempted one restart; not retrying again");
        return;
    }

    const double rate = sampleRate_;
    stream_.reset();
    ring_.reset();
    romm::audio::StartConfig config;
    config.sampleRate = rate;
    config.prebufferSeconds = prebufferSeconds_;
    if (!start(config)) {
        logAt(romm::log::Severity::Error, "one-shot audio stream restart failed");
    }
}

}  // namespace romm::android

#include "audio_output.h"

#include <android/log.h>
#include <algorithm>

#define LOG_TAG "romm_audio_output"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace romm {

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
}  // namespace

AudioOutput::~AudioOutput() {
    stop();
}

bool AudioOutput::start(double coreSampleRate) {
    sampleRate_ = coreSampleRate > 0.0 ? coreSampleRate : 44100.0;
    const auto capacityFrames = std::max<size_t>(
        static_cast<size_t>(sampleRate_ * kRingBufferSeconds), kMinRingFrames);
    ring_ = std::make_unique<AudioRingBuffer>(capacityFrames);
    underrunFrames_.store(0, std::memory_order_relaxed);
    overrunFrames_.store(0, std::memory_order_relaxed);

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
        LOGE("openStream failed: %s", oboe::convertToText(result));
        stream_.reset();
        ring_.reset();
        return false;
    }

    result = stream_->requestStart();
    if (result != oboe::Result::OK) {
        LOGE("requestStart failed: %s", oboe::convertToText(result));
        stream_->close();
        stream_.reset();
        ring_.reset();
        return false;
    }

    LOGI("audio stream started: requested=%.1fHz actual=%dHz capacityFrames=%zu",
         sampleRate_, stream_->getSampleRate(), capacityFrames);
    return true;
}

void AudioOutput::stop() {
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

void AudioOutput::pushSamples(const int16_t* interleaved, size_t frames) {
    if (!ring_) return;
    const size_t written = ring_->write(interleaved, frames);
    if (written < frames) {
        overrunFrames_.fetch_add(frames - written, std::memory_order_relaxed);
    }
}

oboe::DataCallbackResult AudioOutput::onAudioReady(oboe::AudioStream* /*stream*/, void* audioData,
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

void AudioOutput::onErrorAfterClose(oboe::AudioStream* /*stream*/, oboe::Result error) {
    // This runs on a separate, non-realtime Oboe thread after the stream
    // has already been closed, so logging and a bounded restart attempt
    // are both safe here (unlike onAudioReady).
    LOGE("audio stream error after close: %s", oboe::convertToText(error));

    if (restarting_.exchange(true, std::memory_order_relaxed)) {
        LOGE("audio stream already attempted one restart; not retrying again");
        return;
    }

    const double rate = sampleRate_;
    stream_.reset();
    ring_.reset();
    if (!start(rate)) {
        LOGE("one-shot audio stream restart failed");
    }
}

}  // namespace romm

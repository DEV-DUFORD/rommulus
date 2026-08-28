// AudioSink.h — platform-neutral audio output seam for the RomMulus native
// engine.
//
// LINUX_X64.md sections 11/14, Phase 7 Wave 3: the engine tree
// (native/engine/) must never include platform headers (no platform audio
// APIs, no Android headers, no desktop toolkit headers). Platform code
// registers an AudioSink
// implementation at startup; the emulation session consumes the core's
// interleaved stereo PCM exclusively through romm::audio::sink().
//
// The default sink (used when no platform sink is registered) is a no-op:
// start() succeeds and samples are discarded, so a session without a
// registered audio platform simply runs silently without an error log.
#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

namespace romm::audio {

// Parameters for opening an output stream at the core's own sample rate
// (retro_system_av_info::sample_rate).
struct StartConfig {
    // The core's intended sample rate. Implementations may open the device
    // stream at a different rate and convert (the platform's supported
    // conversion path), per LIBRETRO_REFACTOR.md section 8.2.
    double sampleRate = 44100.0;
    // Seconds of audio to accumulate before playback begins; 0.0 starts
    // playback immediately.
    double prebufferSeconds = 0.0;
};

// A destination for the core's interleaved stereo 16-bit PCM.
//
// Thread-safety contract (mirrored by the Android implementation):
//   - start()/stop() are called from the session's caller thread (the
//     JNI-calling thread on Android), never from the emulation thread or
//     the realtime audio callback thread.
//   - pushSamples() is called only from the emulation thread (inside the
//     audio trampolines).
//   - The realtime audio callback must never allocate, lock, log, touch
//     the filesystem, or call into the core (LIBRETRO_REFACTOR.md
//     section 8.2).
class AudioSink {
public:
    virtual ~AudioSink() = default;

    // Opens an output stream at (or converted from) config.sampleRate.
    // Returns false if the stream could not be opened; a false return must
    // not be fatal to the session (video and input still work).
    virtual bool start(const StartConfig& config) = 0;

    // Stops and tears down the stream. Safe to call even if start() was
    // never called or already failed.
    virtual void stop() = 0;

    // Producer-only (emulation thread): pushes `frames` interleaved stereo
    // frames. Frames that don't fit are dropped and counted as overrun;
    // this never blocks the emulation thread.
    virtual void pushSamples(const int16_t* interleaved, size_t frames) = 0;

    // Pauses playback without destroying stream state; resume() continues.
    // Implementations may treat these as no-ops when the producer simply
    // stops pushing samples while paused (the buffer drains and the
    // callback fills silence) — the Android implementation does.
    virtual void pause() = 0;
    virtual void resume() = 0;

    // Diagnostics (LIBRETRO_REFACTOR.md section 8.2: "track underruns,
    // overruns"): frames of silence inserted because the consumer outpaced
    // the producer, and frames dropped because the producer outpaced the
    // consumer.
    virtual uint64_t underrunFrames() const = 0;
    virtual uint64_t overrunFrames() const = 0;
};

// Replaces the active sink (takes ownership); pass nullptr to fall back to
// the default no-op sink. Must complete before the first session starts —
// on Android the platform sink is installed by a static initializer at
// library load time, before JNI_OnLoad.
void setSink(std::unique_ptr<AudioSink> sink);

// The active sink, or the shared default no-op sink when none is
// registered. Never returns a null reference.
AudioSink& sink();

}  // namespace romm::audio

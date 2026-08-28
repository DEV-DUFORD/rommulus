// sdl_audio_sink.cpp — SDL3 audio output for the Linux player.
//
// SDL3 API notes (verified against the installed SDL3 headers):
//   - SDL_AudioSpec is {SDL_AudioFormat format; int channels; int freq;} —
//     SDL3 dropped the SDL2 `samples` member.
//   - SDL_OpenAudioDevice(SDL_AudioDeviceID, const SDL_AudioSpec*) returns
//     a device id (0 on failure); SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK is the
//     default playback device id.
//   - SDL_GetAudioDeviceFormat(devid, spec, &sample_frames) reports the
//     format the device was actually opened in.
//   - SDL_CreateAudioStream(src_spec, dst_spec) + SDL_BindAudioStream
//     (devid, stream) is the SDL3 stream API; there is no
//     SDL_GetAudioDeviceStatus in SDL3.
//
// Diagnostics heuristics (LIBRETRO_REFACTOR.md section 8.2 "track
// underruns, overruns"):
//   - underrun: pushSamples() finds SDL_GetAudioStreamAvailable() == 0 —
//     the consumer has drained the stream buffer, so the frames being
//     pushed will be consumed immediately and any producer stall turns
//     into silence. Those frames are counted as underrun frames, but only
//     after kUnderrunWarmupFrames have been pushed. start() prebuffers the
//     stream with silence before the device starts consuming (§11.8), so
//     the startup gap is real prebuffer time, not starvation, and is not
//     counted.
//   - overrun: pushSamples() finds the queued audio (device-format bytes)
//     above a bound of two seconds of audio — the stream buffer is about
//     to overflow and SDL will drop data. Those frames are counted as
//     overrun frames.
#include "native/player/sdl_audio_sink.h"

#include <SDL3/SDL.h>

#include <native/engine/LogSink.h>

namespace romm::player {

namespace {

constexpr const char* kTag = "sdl_audio";

// Underrun counting is skipped until this many frames have been pushed: a
// safety net for the startup window (see the prebuffer below for why the
// buffer is normally already full by the first push).
constexpr uint64_t kUnderrunWarmupFrames = 1024;

// StartConfig.prebufferSeconds is 0.0 for most cores (the engine only sets it
// for Mupen64Plus-Next). §11.8 requires "start after a measured prebuffer",
// so fall back to this when the engine passes nothing. 100ms comfortably
// covers the device-open-to-first-push startup gap without adding audible
// latency.
constexpr double kDefaultPrebufferSeconds = 0.1;

// Silence is pushed to the stream in chunks of this many source frames so a
// single SDL_PutAudioStreamData call stays small.
constexpr int kPrebufferChunkFrames = 4096;

// Two seconds of device-format audio, in bytes.
int overrunBoundFor(const SDL_AudioSpec& deviceSpec) {
    const int frameBytes =
        SDL_AUDIO_BYTESIZE(deviceSpec.format) * deviceSpec.channels;
    return deviceSpec.freq > 0 ? deviceSpec.freq * frameBytes * 2 : 0;
}

}  // namespace

bool SdlAudioSink::start(const romm::audio::StartConfig& config) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (started_) {
        stopLocked();
    }

    // The core's format: interleaved stereo S16 at the core's rate.
    SDL_AudioSpec srcSpec{};
    srcSpec.format = SDL_AUDIO_S16;
    srcSpec.channels = 2;
    srcSpec.freq = static_cast<int>(config.sampleRate);

    device_ = SDL_OpenAudioDevice(SDL_AUDIO_DEVICE_DEFAULT_PLAYBACK, &srcSpec);
    if (device_ == 0) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_OpenAudioDevice failed: ") +
                                  SDL_GetError());
        return false;
    }

    // The device may grant a different rate than requested (SDL never
    // converts the device stream itself); the audio stream converts src
    // (core) -> dst (device).
    SDL_AudioSpec deviceSpec{};
    int sampleFrames = 0;
    if (!SDL_GetAudioDeviceFormat(device_, &deviceSpec, &sampleFrames)) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_GetAudioDeviceFormat failed: ") +
                                  SDL_GetError());
        SDL_CloseAudioDevice(device_);
        device_ = 0;
        return false;
    }

    stream_ = SDL_CreateAudioStream(&srcSpec, &deviceSpec);
    if (stream_ == nullptr) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_CreateAudioStream failed: ") +
                                  SDL_GetError());
        SDL_CloseAudioDevice(device_);
        device_ = 0;
        return false;
    }

    if (!SDL_BindAudioStream(device_, stream_)) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_BindAudioStream failed: ") +
                                  SDL_GetError());
        SDL_DestroyAudioStream(stream_);
        stream_ = nullptr;
        SDL_CloseAudioDevice(device_);
        device_ = 0;
        return false;
    }

    overrunBoundBytes_ = overrunBoundFor(deviceSpec);
    framesPushed_ = 0;  // restart the underrun warmup window for this device

    // §11.8 "start after a measured prebuffer": in the non-simplified SDL3
    // form (open + create + bind, no callback) the device starts _unpaused_
    // and begins draining the moment the stream is bound. The emulation
    // thread only starts after start() returns, so without a prebuffer the
    // device would consume silence for the whole startup gap and the stream
    // would sit near zero bytes — every early push would then register as an
    // underrun. Pause the device, fill the stream with prebufferSeconds of
    // silence, then resume so playback begins from a full buffer.
    const double prebufferSeconds =
        config.prebufferSeconds > 0.0 ? config.prebufferSeconds : kDefaultPrebufferSeconds;
    if (!SDL_PauseAudioDevice(device_)) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_PauseAudioDevice failed: ") +
                                  SDL_GetError());
    }
    const uint64_t silenceFrames =
        static_cast<uint64_t>(prebufferSeconds * static_cast<double>(srcSpec.freq));
    int16_t silence[kPrebufferChunkFrames * 2] = {};  // stereo, zero = silence
    uint64_t remaining = silenceFrames;
    while (remaining > 0) {
        const int thisFrames =
            remaining < static_cast<uint64_t>(kPrebufferChunkFrames)
                ? static_cast<int>(remaining)
                : kPrebufferChunkFrames;
        if (!SDL_PutAudioStreamData(
                stream_, silence,
                thisFrames * 2 * static_cast<int>(sizeof(int16_t)))) {
            romm::log::sink().log(romm::log::Severity::Warn, kTag,
                                  std::string("SDL_PutAudioStreamData failed: ") +
                                      SDL_GetError());
        }
        remaining -= static_cast<uint64_t>(thisFrames);
    }
    if (!SDL_ResumeAudioDevice(device_)) {
        romm::log::sink().log(romm::log::Severity::Warn, kTag,
                              std::string("SDL_ResumeAudioDevice failed: ") +
                                  SDL_GetError());
    }
    paused_ = false;
    started_ = true;
    return true;
}

void SdlAudioSink::stop() {
    std::lock_guard<std::mutex> lock(mutex_);
    stopLocked();
}

void SdlAudioSink::stopLocked() {
    if (stream_ != nullptr) {
        SDL_UnbindAudioStream(stream_);
        SDL_DestroyAudioStream(stream_);
        stream_ = nullptr;
    }
    if (device_ != 0) {
        SDL_CloseAudioDevice(device_);
        device_ = 0;
    }
    started_ = false;
    paused_ = false;
    // Counters are intentionally kept: main reads them after stop() for
    // the result JSON.
}

void SdlAudioSink::pushSamples(const int16_t* interleaved, size_t frames) {
    if (interleaved == nullptr || frames == 0) {
        return;
    }
    std::lock_guard<std::mutex> lock(mutex_);
    if (!started_ || stream_ == nullptr) {
        return;
    }

    // SDL_GetAudioStreamAvailable reports queued output in DEVICE-format
    // bytes.
    const int queuedBytes = SDL_GetAudioStreamAvailable(stream_);
    framesPushed_ += frames;
    if (queuedBytes == 0 && framesPushed_ > kUnderrunWarmupFrames) {
        // Consumer drained the buffer: these frames will be consumed
        // immediately; count them as underrun frames (see file header).
        // Gated on framesPushed_ so the first fill of an empty stream is
        // not misreported as an underrun.
        underrunFrames_ += frames;
    } else if (queuedBytes > overrunBoundBytes_) {
        // Buffer above the bound: the stream is about to drop data; count
        // these frames as overrun (see file header).
        overrunFrames_ += frames;
    }

    // S16 stereo = 4 bytes per frame. Never blocks the emulation thread;
    // SDL drops what doesn't fit.
    SDL_PutAudioStreamData(stream_, interleaved, static_cast<int>(frames * 4));
}

void SdlAudioSink::pause() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (started_ && !paused_) {
        SDL_PauseAudioDevice(device_);
        paused_ = true;
    }
}

void SdlAudioSink::resume() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (started_ && paused_) {
        SDL_ResumeAudioDevice(device_);
        paused_ = false;
    }
}

uint64_t SdlAudioSink::underrunFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return underrunFrames_;
}

uint64_t SdlAudioSink::overrunFrames() const {
    std::lock_guard<std::mutex> lock(mutex_);
    return overrunFrames_;
}

}  // namespace romm::player

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
//     after kUnderrunWarmupFrames have been pushed (the first push always
//     sees an empty buffer with 0 prebuffer).
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

// Underrun counting is skipped until this many frames have been pushed: the
// very first push always sees an empty stream buffer (0 prebuffer) and would
// otherwise report the entire batch as an underrun.
constexpr uint64_t kUnderrunWarmupFrames = 1024;

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
    started_ = true;
    paused_ = false;
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

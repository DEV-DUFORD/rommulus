// sdl_audio_sink.h — SDL3 audio output for the Linux player (Phase 8,
// LINUX_X64.md section 12).
//
// The core produces interleaved stereo S16 PCM at its own sample rate
// (retro_system_av_info::sample_rate). This sink opens the default
// playback device at that rate, lets SDL's audio-stream machinery convert
// to the device's actual format, and binds the stream to the device so the
// device callback drains it in realtime. The emulation thread only ever
// calls pushSamples(); start/stop/pause/resume come from the main thread.
// All state is serialized on a single mutex (the realtime callback runs
// inside SDL and never touches this object).
#pragma once

#include <native/engine/AudioSink.h>

#include <cstdint>
#include <mutex>

struct SDL_AudioStream;

namespace romm::player {

class SdlAudioSink final : public romm::audio::AudioSink {
public:
    bool start(const romm::audio::StartConfig& config) override;
    void stop() override;
    void pushSamples(const int16_t* interleaved, size_t frames) override;
    void pause() override;
    void resume() override;
    uint64_t underrunFrames() const override;
    uint64_t overrunFrames() const override;

private:
    // Teardown; call with mutex_ held (stop() locks first).
    void stopLocked();

    // SDL state; touched only under mutex_ (the device callback lives
    // inside SDL and is fed exclusively through the bound stream).
    SDL_AudioStream* stream_ = nullptr;
    uint32_t device_ = 0;
    bool started_ = false;
    bool paused_ = false;

    // Diagnostics (see class docs in the .cpp for the exact heuristics).
    uint64_t underrunFrames_ = 0;
    uint64_t overrunFrames_ = 0;
    // Total frames pushed since start(); used to gate underrun counting
    // during the startup window (start() prebuffers the stream with silence
    // before the device starts consuming, so the buffer is normally already
    // full by the first push; this is a safety net).
    uint64_t framesPushed_ = 0;
    // Device-format bytes of queued audio at/above which a push is counted
    // as an overrun (the stream buffer is about to drop data).
    int overrunBoundBytes_ = 0;

    mutable std::mutex mutex_;
};

}  // namespace romm::player

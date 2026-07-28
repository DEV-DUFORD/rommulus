// audio_ring_buffer.h — lock-free single-producer/single-consumer ring
// buffer of interleaved 16-bit stereo audio frames.
//
// Producer: the emulation thread, via EmulationSession's audio trampolines.
// Consumer: Oboe's realtime audio callback thread.
//
// LIBRETRO_REFACTOR.md section 8.2 requires the realtime audio callback to
// never allocate, lock, log, call into JNI, or touch the filesystem. This
// ring buffer is designed for that: read()/write() only touch a
// pre-allocated buffer and two atomics, no locks.
#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace romm {

class AudioRingBuffer {
public:
    // capacityFrames is the number of stereo frames the ring can hold.
    explicit AudioRingBuffer(size_t capacityFrames)
        : capacityFrames_(capacityFrames), buffer_(capacityFrames * kChannels) {}

    // Producer-only. Returns the number of frames actually written (less
    // than `frames` if the ring is full — callers should track that as an
    // overrun rather than blocking).
    size_t write(const int16_t* interleaved, size_t frames) {
        const size_t w = writeIndex_.load(std::memory_order_relaxed);
        const size_t r = readIndex_.load(std::memory_order_acquire);
        const size_t used = w - r;
        const size_t free = capacityFrames_ - used;
        const size_t toWrite = std::min(frames, free);

        for (size_t i = 0; i < toWrite; ++i) {
            const size_t idx = (w + i) % capacityFrames_;
            buffer_[idx * kChannels] = interleaved[i * kChannels];
            buffer_[idx * kChannels + 1] = interleaved[i * kChannels + 1];
        }

        writeIndex_.store(w + toWrite, std::memory_order_release);
        return toWrite;
    }

    // Consumer-only (the realtime audio callback). Returns the number of
    // frames actually read (less than `frames` on underrun — the caller
    // must fill the remainder with silence itself; this method never
    // blocks or waits).
    size_t read(int16_t* outInterleaved, size_t frames) {
        const size_t r = readIndex_.load(std::memory_order_relaxed);
        const size_t w = writeIndex_.load(std::memory_order_acquire);
        const size_t available = w - r;
        const size_t toRead = std::min(frames, available);

        for (size_t i = 0; i < toRead; ++i) {
            const size_t idx = (r + i) % capacityFrames_;
            outInterleaved[i * kChannels] = buffer_[idx * kChannels];
            outInterleaved[i * kChannels + 1] = buffer_[idx * kChannels + 1];
        }

        readIndex_.store(r + toRead, std::memory_order_release);
        return toRead;
    }

    // Producer-only. Discards all buffered frames (used when restarting a
    // stream after an error) — must only be called while the consumer is
    // not running.
    void reset() {
        readIndex_.store(0, std::memory_order_relaxed);
        writeIndex_.store(0, std::memory_order_relaxed);
    }

private:
    static constexpr size_t kChannels = 2;

    size_t capacityFrames_;
    std::vector<int16_t> buffer_;

    // Monotonically increasing frame counters (not wrapped); wrap happens
    // only in the `% capacityFrames_` indexing above. size_t is 32/64-bit
    // and won't overflow in any realistic session lifetime.
    std::atomic<size_t> writeIndex_{0};
    std::atomic<size_t> readIndex_{0};
};

}  // namespace romm

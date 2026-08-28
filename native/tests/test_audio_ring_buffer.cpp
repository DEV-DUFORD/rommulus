// test_audio_ring_buffer.cpp — SPSC audio ring buffer: capacity, wrap-around,
// overrun, underrun, reset, and data integrity.
#include "audio_ring_buffer.h"

#include "romm_test.h"

#include <cstdint>

using romm::AudioRingBuffer;

namespace {

void fill(int16_t* interleaved, int frames, int base) {
    for (int i = 0; i < frames; ++i) {
        interleaved[i * 2] = static_cast<int16_t>(base + i);
        interleaved[i * 2 + 1] = static_cast<int16_t>(-(base + i));
    }
}

void testBasicWriteRead() {
    AudioRingBuffer ring(8);
    int16_t src[8 * 2];
    fill(src, 4, 100);
    CHECK_EQ(ring.write(src, 4), 4u);
    CHECK_EQ(ring.availableFrames(), 4u);

    int16_t out[8 * 2];
    for (int i = 0; i < 16; ++i) out[i] = 0x7777;
    CHECK_EQ(ring.read(out, 8), 4u);  // underrun: only 4 frames available
    for (int i = 0; i < 4; ++i) {
        CHECK_EQ(out[i * 2], src[i * 2]);
        CHECK_EQ(out[i * 2 + 1], src[i * 2 + 1]);
    }
    CHECK_EQ(ring.availableFrames(), 0u);
    CHECK_EQ(ring.read(out, 1), 0u);  // empty: underrun of the whole request
}

void testCapacityAndOverrun() {
    AudioRingBuffer ring(4);
    int16_t src[8 * 2];
    fill(src, 8, 0);
    CHECK_EQ(ring.write(src, 4), 4u);  // fills exactly to capacity
    CHECK_EQ(ring.availableFrames(), 4u);
    CHECK_EQ(ring.write(src, 4), 0u);  // full: nothing written (overrun)
    CHECK_EQ(ring.availableFrames(), 4u);

    // Overrun must not clobber the oldest buffered frames.
    int16_t out[8 * 2];
    CHECK_EQ(ring.read(out, 8), 4u);
    for (int i = 0; i < 4; ++i) {
        CHECK_EQ(out[i * 2], src[i * 2]);
        CHECK_EQ(out[i * 2 + 1], src[i * 2 + 1]);
    }
}

void testPartialOverrun() {
    // One stereo frame is 4 bytes, so "k frames ahead" is src + 2*k.
    AudioRingBuffer ring(4);
    int16_t src[12 * 2];
    fill(src, 12, 10);  // frame i carries value 10+i
    CHECK_EQ(ring.write(src, 4), 4u);      // frames 0..3 (values 10..13): full
    int16_t drain[2 * 2];
    CHECK_EQ(ring.read(drain, 2), 2u);     // consume frames 0..1: free space 2
    CHECK_EQ(ring.write(src + 12, 6), 2u); // frames 6..11 (values 16..21): only 2 fit
    CHECK_EQ(ring.availableFrames(), 4u);
    int16_t out[12 * 2];
    CHECK_EQ(ring.read(out, 4), 4u);
    // The ring now holds the two surviving old frames (values 12, 13)
    // followed by the two newly written frames (values 16, 17), in order.
    CHECK_EQ(out[0], src[4]);
    CHECK_EQ(out[1], src[5]);
    CHECK_EQ(out[2 * 2], src[12]);
    CHECK_EQ(out[2 * 2 + 1], src[13]);
}

void testWrapAround() {
    AudioRingBuffer ring(8);
    int16_t src[16 * 2];
    fill(src, 16, 0);

    // Write 10 (8 accepted), read 10 (8 accepted): both indices advance by
    // 8, landing exactly back on the buffer's start — the wrap boundary.
    CHECK_EQ(ring.write(src, 10), 8u);
    int16_t out[16 * 2];
    CHECK_EQ(ring.read(out, 10), 8u);
    for (int i = 0; i < 8; ++i) {
        CHECK_EQ(out[i * 2], src[i * 2]);
        CHECK_EQ(out[i * 2 + 1], src[i * 2 + 1]);
    }

    // Now the write index starts at frame 8: the next 8 writes wrap to
    // slots 0..7. (src + 16 = 8 stereo frames ahead.)
    CHECK_EQ(ring.write(src + 16, 10), 8u);
    CHECK_EQ(ring.read(out, 10), 8u);
    for (int i = 8; i < 16; ++i) {
        CHECK_EQ(out[(i - 8) * 2], src[i * 2]);
        CHECK_EQ(out[(i - 8) * 2 + 1], src[i * 2 + 1]);
    }
}

void testMultiCycleWrap() {
    // Interleave producer/consumer in small chunks for far more frames than
    // the capacity, so the 4-frame buffer's slots wrap ~25 times; every
    // consumed frame must arrive intact and in order.
    AudioRingBuffer ring(4);
    const int total = 100;
    int produced = 0;
    int consumed = 0;
    int16_t frame[2];
    int16_t out[2];
    while (consumed < total) {
        for (int k = 0; k < 3 && produced < total; ++k) {
            frame[0] = static_cast<int16_t>(produced);
            frame[1] = -static_cast<int16_t>(produced);
            if (ring.write(frame, 1) == 1u) ++produced;
        }
        for (int k = 0; k < 3 && consumed < total; ++k) {
            if (ring.read(out, 1) == 1u) {
                CHECK_EQ(out[0], static_cast<int16_t>(consumed));
                CHECK_EQ(out[1], -static_cast<int16_t>(consumed));
                ++consumed;
            }
        }
    }
    CHECK_EQ(produced, total);
    CHECK_EQ(ring.availableFrames(), 0u);
}

void testReset() {
    AudioRingBuffer ring(8);
    int16_t src[4 * 2];
    fill(src, 4, 500);
    CHECK_EQ(ring.write(src, 4), 4u);
    ring.reset();
    CHECK_EQ(ring.availableFrames(), 0u);
    int16_t out[4 * 2];
    CHECK_EQ(ring.read(out, 1), 0u);
    // Usable again after reset.
    CHECK_EQ(ring.write(src, 4), 4u);
    CHECK_EQ(ring.read(out, 4), 4u);
    CHECK_EQ(out[0], src[0]);
    CHECK_EQ(out[7], src[7]);
}

}  // namespace

int main() {
    testBasicWriteRead();
    testCapacityAndOverrun();
    testPartialOverrun();
    testWrapAround();
    testMultiCycleWrap();
    testReset();
    return rommtest::finish("test_audio_ring_buffer");
}

// input_state.h — thread-safe storage for the latest four-port RetroPad
// input snapshot (LIBRETRO_REFACTOR.md section 9).
//
// Ownership/thread-safety contract:
//   - set() is called from the JNI-calling thread (whatever thread Kotlin's
//     coroutine dispatcher runs LibretroInputAdapter's collector on),
//     whenever ControllerEventRouter's slotsFlow emits a new snapshot.
//   - digitalButton()/analogAxis()/buttonMask() are called only from the
//     emulation thread, inside inputStateTrampoline, once per
//     input_state() query the core makes (potentially many times per
//     frame — one per button/axis it polls).
//   - Every field is a plain scalar atomic, so every individual read/write
//     is torn-free and race-free. A single set() call touches seven
//     atomics per port that are not updated as one atomic transaction, so
//     a read landing mid-update could see part of the new snapshot and
//     part of the old one for that one port for at most one frame — this
//     is a deliberately accepted, bounded, self-correcting trade-off (the
//     next set() and the next frame's reads are always fully consistent
//     again), not a data race: no individual field is ever torn or
//     invalid.
#pragma once

#include "libretro.h"

#include <atomic>
#include <cstdint>

namespace romm {

class InputState {
public:
    static constexpr int kPorts = 4;

    // Producer (JNI-calling thread). buttonsMask packs the sixteen
    // RETRO_DEVICE_ID_JOYPAD_* button states as bit flags (bit N = that
    // button constant's value N is pressed) -- exactly the format
    // RETRO_DEVICE_ID_JOYPAD_MASK queries expect. Analog values are
    // already-clamped signed 16-bit (see LibretroPadMapper on the Kotlin
    // side).
    void set(int port, int32_t buttonsMask, int16_t leftX, int16_t leftY, int16_t rightX,
             int16_t rightY, int16_t leftTrigger = 0, int16_t rightTrigger = 0) {
        if (port < 0 || port >= kPorts) return;
        PortState& p = ports_[port];
        p.buttonsMask.store(buttonsMask, std::memory_order_relaxed);
        p.leftX.store(leftX, std::memory_order_relaxed);
        p.leftY.store(leftY, std::memory_order_relaxed);
        p.rightX.store(rightX, std::memory_order_relaxed);
        p.rightY.store(rightY, std::memory_order_relaxed);
        p.leftTrigger.store(leftTrigger, std::memory_order_relaxed);
        p.rightTrigger.store(rightTrigger, std::memory_order_relaxed);
    }

    // Consumer (emulation thread only). Mirrors the query shape of
    // retro_input_state_t: device is RETRO_DEVICE_JOYPAD or
    // RETRO_DEVICE_ANALOG, index is only meaningful for ANALOG
    // (RETRO_DEVICE_INDEX_ANALOG_LEFT/RIGHT/BUTTON), id is the button/axis id.
    int16_t query(unsigned port_u, unsigned device, unsigned index, unsigned id) const {
        if (port_u >= static_cast<unsigned>(kPorts)) return 0;
        const PortState& p = ports_[port_u];

        if (device == RETRO_DEVICE_JOYPAD) {
            const int32_t mask = p.buttonsMask.load(std::memory_order_relaxed);
            if (id == RETRO_DEVICE_ID_JOYPAD_MASK) {
                return static_cast<int16_t>(mask & 0xFFFF);
            }
            if (id > 15) return 0;
            return (mask & (1 << id)) != 0 ? 1 : 0;
        }

        if (device == RETRO_DEVICE_ANALOG) {
            if (index == RETRO_DEVICE_INDEX_ANALOG_LEFT) {
                if (id == RETRO_DEVICE_ID_ANALOG_X) return p.leftX.load(std::memory_order_relaxed);
                if (id == RETRO_DEVICE_ID_ANALOG_Y) return p.leftY.load(std::memory_order_relaxed);
            } else if (index == RETRO_DEVICE_INDEX_ANALOG_RIGHT) {
                if (id == RETRO_DEVICE_ID_ANALOG_X) return p.rightX.load(std::memory_order_relaxed);
                if (id == RETRO_DEVICE_ID_ANALOG_Y) return p.rightY.load(std::memory_order_relaxed);
            } else if (index == RETRO_DEVICE_INDEX_ANALOG_BUTTON) {
                if (id == RETRO_DEVICE_ID_JOYPAD_L2) {
                    return p.leftTrigger.load(std::memory_order_relaxed);
                }
                if (id == RETRO_DEVICE_ID_JOYPAD_R2) {
                    return p.rightTrigger.load(std::memory_order_relaxed);
                }
            }
            return 0;
        }

        return 0;
    }

private:
    struct PortState {
        std::atomic<int32_t> buttonsMask{0};
        std::atomic<int16_t> leftX{0};
        std::atomic<int16_t> leftY{0};
        std::atomic<int16_t> rightX{0};
        std::atomic<int16_t> rightY{0};
        std::atomic<int16_t> leftTrigger{0};
        std::atomic<int16_t> rightTrigger{0};
    };

    PortState ports_[kPorts];
};

}  // namespace romm

// test_input_state.cpp — atomic four-port RetroPad snapshot set/query:
// joypad mask + individual buttons, analog axes, port bounds, mask
// truncation, and unknown device/index handling.
#include "input_state.h"

#include "romm_test.h"

#include <cstdint>

using romm::InputState;

int main() {
    InputState state;

    // Defaults: everything reads zero on every port.
    for (unsigned port = 0; port < InputState::kPorts; ++port) {
        CHECK_EQ(state.query(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), 0);
        CHECK_EQ(state.query(port, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B), 0);
        CHECK_EQ(state.query(port, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT,
                             RETRO_DEVICE_ID_ANALOG_X), 0);
    }

    // Set a full snapshot on port 1 and read it back.
    const int32_t mask = (1 << RETRO_DEVICE_ID_JOYPAD_B) | (1 << RETRO_DEVICE_ID_JOYPAD_A) |
                         (1 << RETRO_DEVICE_ID_JOYPAD_START);
    state.set(1, mask, 1234, -5678, 0, 32000, 8192, 24576);
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), mask);
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B), 1);
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A), 1);
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_START), 1);
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_SELECT), 0);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT,
                         RETRO_DEVICE_ID_ANALOG_X), 1234);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_LEFT,
                         RETRO_DEVICE_ID_ANALOG_Y), -5678);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT,
                         RETRO_DEVICE_ID_ANALOG_X), 0);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_RIGHT,
                         RETRO_DEVICE_ID_ANALOG_Y), 32000);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_BUTTON,
                         RETRO_DEVICE_ID_JOYPAD_L2), 8192);
    CHECK_EQ(state.query(1, RETRO_DEVICE_ANALOG, RETRO_DEVICE_INDEX_ANALOG_BUTTON,
                         RETRO_DEVICE_ID_JOYPAD_R2), 24576);

    // Other ports are untouched by the port-1 update.
    CHECK_EQ(state.query(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), 0);
    CHECK_EQ(state.query(2, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), 0);
    CHECK_EQ(state.query(3, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), 0);

    // Out-of-range ports are ignored on write and read zero.
    state.set(4, mask, 1, 2, 3, 4);
    state.set(-1, mask, 1, 2, 3, 4);
    CHECK_EQ(state.query(4, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), 0);
    // The port-1 snapshot survived the ignored writes.
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK), mask);

    // Button ids above 15 (other than the MASK sentinel) read zero.
    CHECK_EQ(state.query(1, RETRO_DEVICE_JOYPAD, 0, 17), 0);

    // The MASK query truncates to the low 16 bits.
    state.set(0, (1 << 20) | (1 << RETRO_DEVICE_ID_JOYPAD_SELECT), 0, 0, 0, 0);
    CHECK_EQ(state.query(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_MASK),
             (1 << RETRO_DEVICE_ID_JOYPAD_SELECT));

    // Unknown device and unknown analog index read zero.
    CHECK_EQ(state.query(0, 99, 0, 0), 0);
    CHECK_EQ(state.query(0, RETRO_DEVICE_ANALOG, 7, RETRO_DEVICE_ID_ANALOG_X), 0);

    return rommtest::finish("test_input_state");
}

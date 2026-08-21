#include "native/player/pause_chord.h"

#include "romm_test.h"

using romm::player::pauseChordPressed;

int main() {
    CHECK(!pauseChordPressed(false, false, false, false));
    CHECK(!pauseChordPressed(true, false, false, false));
    CHECK(!pauseChordPressed(false, true, false, false));
    CHECK(pauseChordPressed(true, true, false, false));
    CHECK(!pauseChordPressed(true, true, true, true));
    CHECK(pauseChordPressed(true, true, true, false));

    return rommtest::finish("test_pause_chord");
}

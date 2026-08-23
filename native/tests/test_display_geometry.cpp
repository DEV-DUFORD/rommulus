#include "romm_test.h"

#include "native/player/display_geometry.h"

int main() {
    using romm::player::n64RenderSizeForOutput;

    CHECK_EQ(n64RenderSizeForOutput(1280, 720), std::make_pair(960, 720));
    CHECK_EQ(n64RenderSizeForOutput(2560, 1440), std::make_pair(960, 720));
    CHECK_EQ(n64RenderSizeForOutput(2880, 1800), std::make_pair(960, 720));
    CHECK_EQ(n64RenderSizeForOutput(640, 480), std::make_pair(640, 480));
    CHECK_EQ(n64RenderSizeForOutput(200, 100), std::make_pair(320, 240));
    return 0;
}

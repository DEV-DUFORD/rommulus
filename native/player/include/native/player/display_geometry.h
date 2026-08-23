#pragma once

#include <array>
#include <utility>

namespace romm::player {

inline std::pair<int, int> n64RenderSizeForOutput(int outputWidth, int outputHeight) {
    static constexpr std::array<std::pair<int, int>, 13> kSizes = {{
        {320, 240}, {640, 480}, {960, 720}, {1280, 960}, {1440, 1080},
        {1600, 1200}, {1920, 1440}, {2240, 1680}, {2560, 1920},
        {2880, 2160}, {3200, 2400}, {3520, 2640}, {3840, 2880},
    }};
    std::pair<int, int> selected = kSizes.front();
    for (const auto& size : kSizes) {
        if (size.first > outputWidth || size.second > outputHeight) break;
        selected = size;
    }
    return selected;
}

}  // namespace romm::player

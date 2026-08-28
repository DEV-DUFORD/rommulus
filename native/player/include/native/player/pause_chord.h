#pragma once

namespace romm::player {

inline bool pauseChordPressed(
    bool first,
    bool second,
    bool previousFirst,
    bool previousSecond
) {
    return first && second && !(previousFirst && previousSecond);
}

}  // namespace romm::player

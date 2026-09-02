#pragma once

#include <charconv>
#include <optional>
#include <string_view>

namespace romm::player {

inline std::optional<int> steamVirtualControllerIndex(std::string_view name) {
    constexpr std::string_view prefix = "Microsoft X-Box 360 pad ";
    if (name.size() <= prefix.size() || name.substr(0, prefix.size()) != prefix) {
        return std::nullopt;
    }
    int index = -1;
    const char* begin = name.data() + prefix.size();
    const char* end = name.data() + name.size();
    const auto parsed = std::from_chars(begin, end, index);
    if (parsed.ec != std::errc{} || parsed.ptr != end || index < 0) {
        return std::nullopt;
    }
    return index;
}

inline bool controllerSlotNameMatches(
    std::string_view requested,
    std::string_view sdlGamepadName,
    std::string_view sdlJoystickName,
    int sdlGamepadOrdinal
) {
    if (requested == sdlGamepadName || requested == sdlJoystickName) return true;

    // Linux JInput uses the evdev name while SDL's HIDAPI gives Sony pads a
    // product-family name for the same physical controller.
    if (requested == "Wireless Controller" &&
        (sdlGamepadName == "PS4 Controller" || sdlJoystickName == "PS4 Controller")) {
        return true;
    }

    // Under Steam Input, JInput sees numbered virtual Xbox pads while SDL
    // suppresses those duplicates and exposes the underlying physical pads.
    const auto steamIndex = steamVirtualControllerIndex(requested);
    return steamIndex.has_value() && *steamIndex == sdlGamepadOrdinal;
}

}  // namespace romm::player

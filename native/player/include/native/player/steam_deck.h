#pragma once

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <initializer_list>
#include <sstream>
#include <string>
#include <utility>

namespace romm::player {

inline std::string normalizeSteamDeckIdentity(std::string value) {
    value.erase(
        std::remove_if(value.begin(), value.end(), [](unsigned char c) {
            return std::isspace(c) != 0 || c == '"' || c == '\'';
        }),
        value.end());
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return value;
}

inline bool isSteamDeckOsRelease(const std::string& osRelease) {
    std::istringstream lines(osRelease);
    std::string line;
    while (std::getline(lines, line)) {
        if (normalizeSteamDeckIdentity(std::move(line)) == "variant_id=steamdeck") {
            return true;
        }
    }
    return false;
}

inline bool isGamescopeSession(
    std::string currentDesktop,
    std::string sessionDesktop,
    std::string waylandDisplay,
    std::string gamescopeWaylandDisplay
) {
    currentDesktop = normalizeSteamDeckIdentity(std::move(currentDesktop));
    sessionDesktop = normalizeSteamDeckIdentity(std::move(sessionDesktop));
    waylandDisplay = normalizeSteamDeckIdentity(std::move(waylandDisplay));
    gamescopeWaylandDisplay =
        normalizeSteamDeckIdentity(std::move(gamescopeWaylandDisplay));
    return currentDesktop.find("gamescope") != std::string::npos ||
           sessionDesktop.find("gamescope") != std::string::npos ||
           waylandDisplay.find("gamescope") != std::string::npos ||
           !gamescopeWaylandDisplay.empty();
}

inline bool isSteamDeckIdentity(
    const char* steamDeckEnvironment,
    std::string systemVendor,
    std::string boardVendor,
    std::string productName,
    std::string boardName,
    const std::string& osRelease = {}
) {
    if (steamDeckEnvironment != nullptr) {
        const std::string value =
            normalizeSteamDeckIdentity(steamDeckEnvironment);
        if (value == "1" || value == "true") return true;
    }

    systemVendor = normalizeSteamDeckIdentity(std::move(systemVendor));
    boardVendor = normalizeSteamDeckIdentity(std::move(boardVendor));
    productName = normalizeSteamDeckIdentity(std::move(productName));
    boardName = normalizeSteamDeckIdentity(std::move(boardName));
    const bool valveHardware =
        systemVendor.find("valve") != std::string::npos ||
        boardVendor.find("valve") != std::string::npos;
    const bool deckBoard =
        productName == "jupiter" || productName == "galileo" ||
        boardName == "jupiter" || boardName == "galileo";
    return deckBoard || (valveHardware && productName == "steamdeck") ||
           isSteamDeckOsRelease(osRelease);
}

inline std::string readFirstLine(
    std::initializer_list<std::string> paths
) {
    for (const auto& path : paths) {
        std::ifstream input(path);
        std::string value;
        if (std::getline(input, value) && !value.empty()) return value;
    }
    return {};
}

inline std::string readTextFile(
    std::initializer_list<std::string> paths
) {
    for (const auto& path : paths) {
        std::ifstream input(path);
        if (!input) continue;
        return {
            std::istreambuf_iterator<char>(input),
            std::istreambuf_iterator<char>()
        };
    }
    return {};
}

inline std::string readDmiIdentity(const char* name) {
    return readFirstLine({
        std::string("/sys/class/dmi/id/") + name,
        std::string("/sys/devices/virtual/dmi/id/") + name
    });
}

inline bool isSteamDeck() {
    const char* environment = std::getenv("SteamDeck");
    if (environment == nullptr) environment = std::getenv("STEAM_DECK");
    return isSteamDeckIdentity(
        environment,
        readDmiIdentity("sys_vendor"),
        readDmiIdentity("board_vendor"),
        readDmiIdentity("product_name"),
        readDmiIdentity("board_name"),
        readTextFile({"/etc/os-release", "/usr/lib/os-release"}));
}

inline bool shouldUseSteamDeckPlayer() {
    const char* forceDeckPlayer = std::getenv("ROMM_FORCE_STEAM_DECK_PLAYER");
    if (forceDeckPlayer != nullptr) {
        const std::string value = normalizeSteamDeckIdentity(forceDeckPlayer);
        if (value == "1" || value == "true") return true;
    }
    const auto environmentValue = [](const char* name) {
        const char* value = std::getenv(name);
        return value != nullptr ? std::string(value) : std::string();
    };
    return isSteamDeck() && isGamescopeSession(
        environmentValue("XDG_CURRENT_DESKTOP"),
        environmentValue("XDG_SESSION_DESKTOP"),
        environmentValue("WAYLAND_DISPLAY"),
        environmentValue("GAMESCOPE_WAYLAND_DISPLAY"));
}

}  // namespace romm::player

#pragma once

#include <algorithm>
#include <cctype>
#include <cstdlib>
#include <fstream>
#include <string>
#include <utility>

namespace romm::player {

inline bool isSteamDeckIdentity(
    const char* steamDeckEnvironment,
    std::string systemVendor,
    std::string boardVendor,
    std::string productName,
    std::string boardName
) {
    if (steamDeckEnvironment != nullptr) {
        const std::string value(steamDeckEnvironment);
        if (value == "1" || value == "true") return true;
    }

    const auto normalize = [](std::string value) {
        value.erase(
            std::remove_if(value.begin(), value.end(), [](unsigned char c) {
                return std::isspace(c) != 0;
            }),
            value.end());
        std::transform(value.begin(), value.end(), value.begin(), [](unsigned char c) {
            return static_cast<char>(std::tolower(c));
        });
        return value;
    };
    systemVendor = normalize(std::move(systemVendor));
    boardVendor = normalize(std::move(boardVendor));
    productName = normalize(std::move(productName));
    boardName = normalize(std::move(boardName));
    const bool valveHardware =
        systemVendor.find("valve") != std::string::npos ||
        boardVendor.find("valve") != std::string::npos;
    const bool deckBoard =
        productName == "jupiter" || productName == "galileo" ||
        boardName == "jupiter" || boardName == "galileo";
    return deckBoard || (valveHardware && productName == "steamdeck");
}

inline std::string readDmiIdentity(const char* name) {
    std::ifstream input(std::string("/sys/devices/virtual/dmi/id/") + name);
    std::string value;
    std::getline(input, value);
    return value;
}

inline bool isSteamDeck() {
    const char* environment = std::getenv("SteamDeck");
    if (environment == nullptr) environment = std::getenv("STEAM_DECK");
    return isSteamDeckIdentity(
        environment,
        readDmiIdentity("sys_vendor"),
        readDmiIdentity("board_vendor"),
        readDmiIdentity("product_name"),
        readDmiIdentity("board_name"));
}

}  // namespace romm::player

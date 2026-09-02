#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace romm {

bool isDonkeyKong64Rom(const std::vector<uint8_t>& content, const std::string& contentPath);
bool isMarioKart64Rom(const std::vector<uint8_t>& content, const std::string& contentPath);
bool isSnowboardKids2Rom(const std::vector<uint8_t>& content, const std::string& contentPath);

}  // namespace romm

#pragma once

#include <cstdint>
#include <optional>
#include <string>

namespace romm::player {

struct SaveMetadata {
    std::string sha256;
    int64_t size = 0;
};

// Reads a checkpoint once and returns its lowercase SHA-256 and byte size.
// A read, close, or size-overflow failure returns nullopt.
std::optional<SaveMetadata> readSaveMetadata(const std::string& path);

}  // namespace romm::player

#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <vector>

namespace romm {

constexpr size_t kMgbaStableSaveRamSize = 128 * 1024;

inline bool isLegacyMgbaSaveRamSize(size_t size) {
    switch (size) {
        case 512:
        case 8 * 1024:
        case 32 * 1024:
        case 64 * 1024:
        case kMgbaStableSaveRamSize:
            return true;
        default:
            return false;
    }
}

inline bool isCompatibleSaveRamImageSize(bool isMgba, size_t expectedSize, size_t actualSize) {
    if (actualSize == expectedSize) return true;
    return isMgba && expectedSize == kMgbaStableSaveRamSize &&
           isLegacyMgbaSaveRamSize(actualSize);
}

inline bool expandSaveRamImageForRestore(bool isMgba,
                                         size_t expectedSize,
                                         const std::vector<uint8_t>& source,
                                         std::vector<uint8_t>& destination) {
    if (!isCompatibleSaveRamImageSize(isMgba, expectedSize, source.size())) return false;
    destination.assign(expectedSize, isMgba ? 0xFF : 0);
    std::copy(source.begin(), source.end(), destination.begin());
    return true;
}

}  // namespace romm

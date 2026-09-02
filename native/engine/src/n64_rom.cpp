#include "n64_rom.h"

#include <array>
#include <fstream>

namespace romm {
namespace {

constexpr size_t kN64HeaderSize = 0x40;
constexpr size_t kInternalNameOffset = 0x20;
constexpr size_t kInternalNameSize = 20;

enum class ByteOrder {
    kBigEndian,
    kByteSwapped,
    kLittleEndian,
};

bool detectByteOrder(const uint8_t* header, ByteOrder& order) {
    const std::array<uint8_t, 4> magic = {header[0], header[1], header[2], header[3]};
    if (magic == std::array<uint8_t, 4>{0x80, 0x37, 0x12, 0x40}) {
        order = ByteOrder::kBigEndian;
        return true;
    }
    if (magic == std::array<uint8_t, 4>{0x37, 0x80, 0x40, 0x12}) {
        order = ByteOrder::kByteSwapped;
        return true;
    }
    if (magic == std::array<uint8_t, 4>{0x40, 0x12, 0x37, 0x80}) {
        order = ByteOrder::kLittleEndian;
        return true;
    }
    return false;
}

size_t sourceIndex(size_t canonicalIndex, ByteOrder order) {
    switch (order) {
        case ByteOrder::kBigEndian:
            return canonicalIndex;
        case ByteOrder::kByteSwapped:
            return canonicalIndex ^ 1U;
        case ByteOrder::kLittleEndian:
            return canonicalIndex ^ 3U;
    }
    return canonicalIndex;
}

std::string readInternalName(const uint8_t* header) {
    ByteOrder order = ByteOrder::kBigEndian;
    if (!detectByteOrder(header, order)) return {};

    std::string internalName;
    internalName.reserve(kInternalNameSize);
    for (size_t i = 0; i < kInternalNameSize; ++i) {
        internalName.push_back(static_cast<char>(
            header[sourceIndex(kInternalNameOffset + i, order)]));
    }
    while (!internalName.empty() &&
           (internalName.back() == ' ' || internalName.back() == '\0')) {
        internalName.pop_back();
    }
    return internalName;
}

bool isRomNamed(const std::vector<uint8_t>& content, const std::string& contentPath,
                const char* expectedName) {
    if (content.size() >= kN64HeaderSize) {
        return readInternalName(content.data()) == expectedName;
    }
    if (contentPath.empty()) return false;

    std::array<uint8_t, kN64HeaderSize> header{};
    std::ifstream stream(contentPath, std::ios::binary);
    if (!stream.read(reinterpret_cast<char*>(header.data()), header.size())) return false;
    return readInternalName(header.data()) == expectedName;
}

}  // namespace

bool isDonkeyKong64Rom(const std::vector<uint8_t>& content, const std::string& contentPath) {
    return isRomNamed(content, contentPath, "DONKEY KONG 64");
}

bool isMarioKart64Rom(const std::vector<uint8_t>& content, const std::string& contentPath) {
    return isRomNamed(content, contentPath, "MARIOKART64");
}

bool isSnowboardKids2Rom(const std::vector<uint8_t>& content, const std::string& contentPath) {
    return isRomNamed(content, contentPath, "SNOWBOARD KIDS2");
}

}  // namespace romm

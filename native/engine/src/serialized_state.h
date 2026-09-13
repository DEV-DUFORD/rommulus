#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>

namespace romm {

constexpr uint32_t kSerializedStateFormatVersion = 1;
constexpr std::array<uint8_t, 8> kSerializedStateMagic{
    'R', 'O', 'M', 'M', 'S', 'T', 'A', 'T'
};
constexpr size_t kSerializedStateHeaderSize = 24;

inline void appendUint32Le(std::vector<uint8_t>& out, uint32_t value) {
    for (unsigned shift = 0; shift < 32; shift += 8) {
        out.push_back(static_cast<uint8_t>((value >> shift) & 0xFF));
    }
}

inline void appendUint64Le(std::vector<uint8_t>& out, uint64_t value) {
    for (unsigned shift = 0; shift < 64; shift += 8) {
        out.push_back(static_cast<uint8_t>((value >> shift) & 0xFF));
    }
}

inline uint32_t readUint32Le(const uint8_t* data) {
    uint32_t value = 0;
    for (unsigned index = 0; index < 4; ++index) {
        value |= static_cast<uint32_t>(data[index]) << (index * 8);
    }
    return value;
}

inline uint64_t readUint64Le(const uint8_t* data) {
    uint64_t value = 0;
    for (unsigned index = 0; index < 8; ++index) {
        value |= static_cast<uint64_t>(data[index]) << (index * 8);
    }
    return value;
}

inline bool encodeSerializedState(const std::string& coreIdentity,
                                  const std::vector<uint8_t>& payload,
                                  std::vector<uint8_t>& out) {
    if (coreIdentity.size() > std::numeric_limits<uint32_t>::max()) return false;
    out.clear();
    out.reserve(kSerializedStateHeaderSize + coreIdentity.size() + payload.size());
    out.insert(out.end(), kSerializedStateMagic.begin(), kSerializedStateMagic.end());
    appendUint32Le(out, kSerializedStateFormatVersion);
    appendUint32Le(out, static_cast<uint32_t>(coreIdentity.size()));
    appendUint64Le(out, static_cast<uint64_t>(payload.size()));
    out.insert(out.end(), coreIdentity.begin(), coreIdentity.end());
    out.insert(out.end(), payload.begin(), payload.end());
    return true;
}

inline bool decodeSerializedState(const std::vector<uint8_t>& encoded,
                                  const std::string& expectedCoreIdentity,
                                  size_t expectedPayloadSize,
                                  std::vector<uint8_t>& payload) {
    if (encoded.size() < kSerializedStateHeaderSize) return false;
    if (std::memcmp(encoded.data(), kSerializedStateMagic.data(), kSerializedStateMagic.size()) != 0) {
        return false;
    }
    if (readUint32Le(encoded.data() + 8) != kSerializedStateFormatVersion) return false;

    const uint32_t identitySize = readUint32Le(encoded.data() + 12);
    const uint64_t payloadSize = readUint64Le(encoded.data() + 16);
    if (payloadSize != expectedPayloadSize) return false;
    if (identitySize > encoded.size() - kSerializedStateHeaderSize) return false;
    const size_t remaining = encoded.size() - kSerializedStateHeaderSize - identitySize;
    if (payloadSize != remaining) return false;

    const auto identityBegin = encoded.begin() + kSerializedStateHeaderSize;
    const std::string identity(identityBegin, identityBegin + identitySize);
    if (identity != expectedCoreIdentity) return false;

    payload.assign(identityBegin + identitySize, encoded.end());
    return true;
}

}  // namespace romm

#include "native/player/save_metadata.h"

#ifdef _WIN32
#include <native/platform/windows/utf16.h>
#endif

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdio>
#include <cstring>
#include <limits>

namespace romm::player {
namespace {

constexpr std::array<uint32_t, 64> kRoundConstants = {
    0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1,
    0x923f82a4, 0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786,
    0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147,
    0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
    0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a,
    0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208,
    0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
};

uint32_t rotateRight(uint32_t value, unsigned amount) {
    return (value >> amount) | (value << (32U - amount));
}

class Sha256 {
public:
    void update(const uint8_t* data, std::size_t size) {
        totalBytes_ += size;
        while (size > 0) {
            const std::size_t copySize = std::min(size, block_.size() - blockSize_);
            std::memcpy(block_.data() + blockSize_, data, copySize);
            blockSize_ += copySize;
            data += copySize;
            size -= copySize;
            if (blockSize_ == block_.size()) {
                transform(block_.data());
                blockSize_ = 0;
            }
        }
    }

    std::string finish() {
        const uint64_t bitLength = totalBytes_ * 8U;
        block_[blockSize_++] = 0x80;
        if (blockSize_ > 56) {
            std::fill(block_.begin() + static_cast<std::ptrdiff_t>(blockSize_), block_.end(), 0);
            transform(block_.data());
            blockSize_ = 0;
        }
        std::fill(block_.begin() + static_cast<std::ptrdiff_t>(blockSize_), block_.begin() + 56, 0);
        for (unsigned i = 0; i < 8; ++i) {
            block_[63 - i] = static_cast<uint8_t>(bitLength >> (i * 8U));
        }
        transform(block_.data());

        static constexpr char kHex[] = "0123456789abcdef";
        std::string output(64, '0');
        for (std::size_t i = 0; i < state_.size(); ++i) {
            for (unsigned byte = 0; byte < 4; ++byte) {
                const uint8_t value = static_cast<uint8_t>(state_[i] >> (24U - byte * 8U));
                const std::size_t offset = i * 8 + byte * 2;
                output[offset] = kHex[value >> 4U];
                output[offset + 1] = kHex[value & 0x0fU];
            }
        }
        return output;
    }

private:
    void transform(const uint8_t* block) {
        std::array<uint32_t, 64> words{};
        for (std::size_t i = 0; i < 16; ++i) {
            const std::size_t offset = i * 4;
            words[i] = (static_cast<uint32_t>(block[offset]) << 24U) |
                       (static_cast<uint32_t>(block[offset + 1]) << 16U) |
                       (static_cast<uint32_t>(block[offset + 2]) << 8U) |
                       static_cast<uint32_t>(block[offset + 3]);
        }
        for (std::size_t i = 16; i < words.size(); ++i) {
            const uint32_t s0 = rotateRight(words[i - 15], 7) ^
                                rotateRight(words[i - 15], 18) ^ (words[i - 15] >> 3U);
            const uint32_t s1 = rotateRight(words[i - 2], 17) ^
                                rotateRight(words[i - 2], 19) ^ (words[i - 2] >> 10U);
            words[i] = words[i - 16] + s0 + words[i - 7] + s1;
        }

        uint32_t a = state_[0];
        uint32_t b = state_[1];
        uint32_t c = state_[2];
        uint32_t d = state_[3];
        uint32_t e = state_[4];
        uint32_t f = state_[5];
        uint32_t g = state_[6];
        uint32_t h = state_[7];
        for (std::size_t i = 0; i < words.size(); ++i) {
            const uint32_t sum1 =
                    rotateRight(e, 6) ^ rotateRight(e, 11) ^ rotateRight(e, 25);
            const uint32_t choice = (e & f) ^ (~e & g);
            const uint32_t temp1 = h + sum1 + choice + kRoundConstants[i] + words[i];
            const uint32_t sum0 =
                    rotateRight(a, 2) ^ rotateRight(a, 13) ^ rotateRight(a, 22);
            const uint32_t majority = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = sum0 + majority;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    std::array<uint32_t, 8> state_ = {
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    };
    std::array<uint8_t, 64> block_{};
    std::size_t blockSize_ = 0;
    uint64_t totalBytes_ = 0;
};

}  // namespace

std::optional<SaveMetadata> readSaveMetadata(const std::string& path) {
#ifdef _WIN32
    const auto widePath = romm::win32::utf8ToUtf16(path);
    if (!widePath) return std::nullopt;
    const std::wstring nativePath = romm::win32::toWideString(*widePath);
    FILE* file = _wfopen(nativePath.c_str(), L"rb");
#else
    FILE* file = std::fopen(path.c_str(), "rb");
#endif
    if (file == nullptr) return std::nullopt;

    Sha256 hash;
    uint64_t size = 0;
    std::array<uint8_t, 64 * 1024> buffer{};
    while (true) {
        const std::size_t count = std::fread(buffer.data(), 1, buffer.size(), file);
        if (count > 0) {
            if (size > static_cast<uint64_t>(std::numeric_limits<int64_t>::max()) - count) {
                std::fclose(file);
                return std::nullopt;
            }
            hash.update(buffer.data(), count);
            size += count;
        }
        if (count < buffer.size()) {
            if (std::ferror(file) != 0) {
                std::fclose(file);
                return std::nullopt;
            }
            break;
        }
    }
    if (std::fclose(file) != 0) return std::nullopt;
    return SaveMetadata{hash.finish(), static_cast<int64_t>(size)};
}

}  // namespace romm::player

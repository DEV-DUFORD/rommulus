#include "n64_rom.h"
#include "romm_test.h"

#include <algorithm>
#include <cstdint>
#include <vector>

namespace {

std::vector<uint8_t> n64Header(const char* name) {
    std::vector<uint8_t> header(0x40, 0);
    header[0] = 0x80;
    header[1] = 0x37;
    header[2] = 0x12;
    header[3] = 0x40;
    std::fill(header.begin() + 0x20, header.begin() + 0x34, ' ');
    std::copy(name, name + std::char_traits<char>::length(name), header.begin() + 0x20);
    return header;
}

std::vector<uint8_t> byteSwapPairs(std::vector<uint8_t> header) {
    for (size_t i = 0; i < header.size(); i += 2) {
        std::swap(header[i], header[i + 1]);
    }
    return header;
}

std::vector<uint8_t> byteSwapWords(std::vector<uint8_t> header) {
    for (size_t i = 0; i < header.size(); i += 4) {
        std::reverse(header.begin() + i, header.begin() + i + 4);
    }
    return header;
}

}  // namespace

int main() {
    const auto dk64 = n64Header("DONKEY KONG 64");
    CHECK(romm::isDonkeyKong64Rom(dk64, ""));
    CHECK(romm::isDonkeyKong64Rom(byteSwapPairs(dk64), ""));
    CHECK(romm::isDonkeyKong64Rom(byteSwapWords(dk64), ""));
    CHECK(!romm::isDonkeyKong64Rom(n64Header("SUPER MARIO 64"), ""));
    CHECK(!romm::isDonkeyKong64Rom(std::vector<uint8_t>(0x40, 0), ""));
    return 0;
}

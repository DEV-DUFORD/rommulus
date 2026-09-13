#include "save_ram_persistence.h"
#include "romm_test.h"

#include <vector>

namespace {

void testMgbaLegacySizesAreAcceptedAndExpanded() {
    for (const size_t legacySize : {size_t{512}, size_t{8192}, size_t{32768}, size_t{65536}}) {
        std::vector<uint8_t> source(legacySize, 0x42);
        std::vector<uint8_t> expanded;
        CHECK(romm::expandSaveRamImageForRestore(
            true, romm::kMgbaStableSaveRamSize, source, expanded));
        CHECK_EQ(expanded.size(), romm::kMgbaStableSaveRamSize);
        CHECK_EQ(expanded.front(), uint8_t{0x42});
        CHECK_EQ(expanded[legacySize - 1], uint8_t{0x42});
        CHECK_EQ(expanded[legacySize], uint8_t{0xFF});
    }
}

void testMgbaRejectsUnknownSizes() {
    CHECK(!romm::isCompatibleSaveRamImageSize(
        true, romm::kMgbaStableSaveRamSize, 12345));
}

void testOtherCoresRemainExact() {
    CHECK(romm::isCompatibleSaveRamImageSize(false, 32768, 32768));
    CHECK(!romm::isCompatibleSaveRamImageSize(false, 32768, 8192));
}

}  // namespace

int main() {
    testMgbaLegacySizesAreAcceptedAndExpanded();
    testMgbaRejectsUnknownSizes();
    testOtherCoresRemainExact();
    return rommtest::finish("test_save_ram_persistence");
}

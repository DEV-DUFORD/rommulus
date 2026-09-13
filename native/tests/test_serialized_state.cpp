#include "serialized_state.h"
#include "romm_test.h"

#include <vector>

namespace {

void testRoundTrip() {
    const std::vector<uint8_t> original{1, 2, 3, 4};
    std::vector<uint8_t> encoded;
    CHECK(romm::encodeSerializedState("mGBA\n0.10", original, encoded));

    std::vector<uint8_t> decoded;
    CHECK(romm::decodeSerializedState(encoded, "mGBA\n0.10", original.size(), decoded));
    CHECK(decoded == original);
}

void testRejectsWrongVersionCoreAndSize() {
    std::vector<uint8_t> encoded;
    CHECK(romm::encodeSerializedState("core\n1", {9, 8, 7}, encoded));

    std::vector<uint8_t> decoded;
    CHECK(!romm::decodeSerializedState(encoded, "other\n1", 3, decoded));
    CHECK(!romm::decodeSerializedState(encoded, "core\n1", 4, decoded));

    encoded[8] = 2;
    CHECK(!romm::decodeSerializedState(encoded, "core\n1", 3, decoded));
}

void testRejectsTruncatedPayload() {
    std::vector<uint8_t> encoded;
    CHECK(romm::encodeSerializedState("core\n1", {9, 8, 7}, encoded));
    encoded.pop_back();

    std::vector<uint8_t> decoded;
    CHECK(!romm::decodeSerializedState(encoded, "core\n1", 3, decoded));
}

}  // namespace

int main() {
    testRoundTrip();
    testRejectsWrongVersionCoreAndSize();
    testRejectsTruncatedPayload();
    return rommtest::finish("test_serialized_state");
}

// test_binding_table.cpp — host tests for the SDL-free RetroPad binding
// table (native/player/include/native/player/binding_table.h): the default
// mapping (the built-in gamepad -> RetroPad table SdlInput::poll() used to
// hardcode), get/set/reset, unbound slots, plus the sidecar identity
// normalization and JSON serialization/write (binding_sidecar.{h,cpp}).
#include "romm_test.h"

#include <cstdio>
#include <string>

#include <nlohmann/json.hpp>

#include "native/player/binding_sidecar.h"
#include "native/player/binding_table.h"

namespace {

using romm::player::BindingSource;
using romm::player::BindingTable;
using romm::player::PadAxis;
using romm::player::PadButton;

void testDefaultMapping() {
    const BindingTable table;
    CHECK(table.isDefault());

    struct Expect {
        int slot;
        PadButton button;
    };
    // The built-in mapping, in RetroPadSlot order.
    const Expect expected[] = {
        {romm::player::kSlotA, PadButton::kSouth},
        {romm::player::kSlotB, PadButton::kEast},
        {romm::player::kSlotX, PadButton::kWest},
        {romm::player::kSlotY, PadButton::kNorth},
        {romm::player::kSlotSelect, PadButton::kBack},
        {romm::player::kSlotStart, PadButton::kStart},
        {romm::player::kSlotLeftShoulder, PadButton::kLeftShoulder},
        {romm::player::kSlotRightShoulder, PadButton::kRightShoulder},
        {romm::player::kSlotDpadUp, PadButton::kDpadUp},
        {romm::player::kSlotDpadDown, PadButton::kDpadDown},
        {romm::player::kSlotDpadLeft, PadButton::kDpadLeft},
        {romm::player::kSlotDpadRight, PadButton::kDpadRight},
    };
    for (const Expect& e : expected) {
        const BindingSource& source = table.get(e.slot);
        CHECK(source.kind == BindingSource::Kind::kButton);
        CHECK(source.button == e.button);
    }

    // Slot bit positions match the libretro ABI (poll() builds the core's
    // button mask from them).
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotA), 8);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotB), 0);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotX), 9);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotY), 1);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotSelect), 2);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotStart), 3);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotLeftShoulder), 10);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotRightShoulder), 11);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotDpadUp), 4);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotDpadDown), 5);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotDpadLeft), 6);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(romm::player::kSlotDpadRight), 7);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(-1), -1);
    CHECK_EQ(romm::player::retroPadSlotJoypadBit(12), -1);
}

void testSetGetReset() {
    BindingTable table;
    CHECK(table.isDefault());

    // Set a button binding.
    table.set(romm::player::kSlotA, BindingSource::ofButton(PadButton::kStart));
    CHECK(!table.isDefault());
    const BindingSource& a = table.get(romm::player::kSlotA);
    CHECK(a.kind == BindingSource::Kind::kButton && a.button == PadButton::kStart);
    // Other slots untouched.
    CHECK(table.get(romm::player::kSlotB) ==
          BindingSource::ofButton(PadButton::kEast));

    // Set an axis-half binding (polarity is normalized to +/-1).
    table.set(romm::player::kSlotDpadLeft, BindingSource::axisDirection(PadAxis::kLeftX, -5));
    const BindingSource& left = table.get(romm::player::kSlotDpadLeft);
    CHECK(left.kind == BindingSource::Kind::kAxisDirection);
    CHECK(left.axis == PadAxis::kLeftX);
    CHECK_EQ(left.polarity, -1);

    // Unbind a slot.
    table.set(romm::player::kSlotSelect, BindingSource::unbound());
    CHECK(table.get(romm::player::kSlotSelect).kind == BindingSource::Kind::kUnbound);

    // Out-of-range slots are ignored.
    table.set(-1, BindingSource::ofButton(PadButton::kSouth));
    table.set(99, BindingSource::ofButton(PadButton::kSouth));
    CHECK(!table.isDefault());  // still non-default from the real edits

    // Reset restores every slot to the built-in mapping.
    table.reset();
    CHECK(table.isDefault());
    CHECK(table.get(romm::player::kSlotA) == BindingSource::ofButton(PadButton::kSouth));
    CHECK(table.get(romm::player::kSlotDpadLeft) ==
          BindingSource::ofButton(PadButton::kDpadLeft));
    CHECK(table.get(romm::player::kSlotSelect) == BindingSource::ofButton(PadButton::kBack));

    table.clear();
    CHECK(!table.isDefault());
    for (int slot = 0; slot < romm::player::kRetroPadSlotCount; ++slot) {
        CHECK(table.get(slot).kind == BindingSource::Kind::kUnbound);
    }
}

void testDisplayLabels() {
    CHECK(std::string(BindingSource::ofButton(PadButton::kSouth).display()) == "Button A");
    CHECK(std::string(BindingSource::axisDirection(PadAxis::kLeftX, 1).display()) ==
          "Left Stick X +");
    CHECK(std::string(BindingSource::axisDirection(PadAxis::kRightY, -1).display()) ==
          "Right Stick Y -");
    CHECK(std::string(BindingSource::unbound().display()) == "Unmapped");
    // Slot names used by the sidecar JSON.
    CHECK(std::string(romm::player::retroPadSlotName(romm::player::kSlotA)) == "a");
    CHECK(std::string(romm::player::retroPadSlotName(romm::player::kSlotSelect)) ==
          "select");
    CHECK(std::string(romm::player::retroPadSlotName(romm::player::kSlotDpadUp)) ==
          "dpad_up");
}

void testNormalizedIdentity() {
    // 32-hex GUID with nonzero vendor (bytes 1..2) / product (bytes 3..4):
    // bytes 03 4c 05 a0 17 ... -> vid 0x054c, pid 0x17a0.
    const std::string usbGuid = "034c05a017" + std::string(22, '0');
    CHECK_EQ(usbGuid.size(), 32u);
    CHECK(std::string(romm::player::normalizedDeviceIdentity(usbGuid)) ==
          "vid:054c-pid:17a0");

    // Zeroed vendor/product (non-USB or unknown layout) falls back to the
    // canonical GUID itself.
    const std::string zeroGuid = std::string(32, '0');
    CHECK(std::string(romm::player::normalizedDeviceIdentity(zeroGuid)) ==
          "guid:" + zeroGuid);

    // Malformed (wrong length / non-hex) input falls back too, lowercased.
    CHECK(std::string(romm::player::normalizedDeviceIdentity("034C05A017")) ==
          "guid:034c05a017");
}

void testSidecarSerializeAndWrite() {
    using nlohmann::json;

    romm::player::BindingTable table;
    CHECK(table.isDefault());
    // One custom edit so the JSON carries a non-default row.
    table.set(romm::player::kSlotDpadLeft,
              romm::player::BindingSource::axisDirection(romm::player::PadAxis::kLeftX, -1));

    const std::string guid = "034c05a017" + std::string(22, '0');
    romm::player::DeviceBindings device;
    device.guid = guid;
    device.identity = romm::player::normalizedDeviceIdentity(guid);
    device.table = table;

    const std::string serialized =
        romm::player::serializeBindingSidecar({device});
    json root = json::parse(serialized);
    CHECK_EQ(root["protocolVersion"].get<int>(), romm::player::kBindingSidecarVersion);
    CHECK_EQ(root["devices"].size(), 1u);
    const json& entry = root["devices"][0];
    CHECK(entry["guid"] == guid);
    CHECK(entry["identity"]["descriptor"] == "vid:054c-pid:17a0");
    CHECK_EQ(entry["identity"]["vendorId"].get<int>(), 0x054c);
    CHECK_EQ(entry["identity"]["productId"].get<int>(), 0x17a0);
    CHECK_EQ(entry["bindings"].size(), 12u);

    // Slot rows: name + typed binding, in RetroPadSlot order.
    CHECK(entry["bindings"][0]["slot"] == "a");
    CHECK(entry["bindings"][0]["type"] == "button");
    CHECK(entry["bindings"][0]["button"] == "south");
    // Index 10 in RetroPadSlot order (A,B,X,Y,Select,Start,L,R,Up,Down,Left…).
    const json& dpadLeft = entry["bindings"][10];
    CHECK(dpadLeft["slot"] == "dpad_left");
    CHECK(dpadLeft["type"] == "axis_direction");
    CHECK(dpadLeft["axis"] == "left_x");
    CHECK_EQ(dpadLeft["polarity"].get<int>(), -1);

    // Atomic write to a temp path, then read the file back and re-parse it.
    const std::string path = "/tmp/romm_binding_sidecar_test.json";
    CHECK(romm::player::writeBindingSidecar(path, {device}));
    FILE* file = std::fopen(path.c_str(), "rb");
    CHECK(file != nullptr);
    if (file != nullptr) {
        std::fseek(file, 0, SEEK_END);
        const long size = std::ftell(file);
        std::fseek(file, 0, SEEK_SET);
        std::string onDisk(static_cast<size_t>(size), '\0');
        CHECK(size > 0 && std::fread(onDisk.data(), 1, onDisk.size(), file) == onDisk.size());
        std::fclose(file);
        json reparsed = json::parse(onDisk);
        CHECK(reparsed == root);  // round-trip: disk content equals serialized
    }
    std::remove(path.c_str());

    // A missing parent directory fails cleanly (no throw, no crash).
    CHECK(!romm::player::writeBindingSidecar("/tmp/does-not-exist-xyz/c.json", {device}));
}

}  // namespace

int main() {
    testDefaultMapping();
    testSetGetReset();
    testDisplayLabels();
    testNormalizedIdentity();
    testSidecarSerializeAndWrite();
    return rommtest::finish("test_binding_table");
}

#include "romm_test.h"

#include <array>
#include <cstdio>
#include <string>

#include <nlohmann/json.hpp>

#include "native/player/keyboard_binding_sidecar.h"
#include "native/player/keyboard_binding_table.h"

using namespace romm::player;

namespace {

void testDefaultsAndEditing() {
    KeyboardBindingTable table;
    CHECK(table.isDefault());
    CHECK((table.get(kKeyboardDpadUp) == KeyboardBinding{26, 82}));
    CHECK((table.get(kKeyboardDpadDown) == KeyboardBinding{22, 81}));
    CHECK((table.get(kKeyboardDpadLeft) == KeyboardBinding{4, 80}));
    CHECK((table.get(kKeyboardDpadRight) == KeyboardBinding{7, 79}));
    CHECK((table.get(kKeyboardA) == KeyboardBinding{40, 44}));
    CHECK((table.get(kKeyboardB) == KeyboardBinding{225, 229}));
    CHECK((table.get(kKeyboardX) == KeyboardBinding{27, std::nullopt}));
    CHECK((table.get(kKeyboardY) == KeyboardBinding{29, std::nullopt}));
    CHECK((table.get(kKeyboardSelect) == KeyboardBinding{224, std::nullopt}));
    CHECK((table.get(kKeyboardStart) == KeyboardBinding{228, std::nullopt}));
    CHECK((table.get(kKeyboardLeftShoulder) == KeyboardBinding{}));
    CHECK((table.get(kKeyboardRightYPositive) == KeyboardBinding{}));

    table.setScancode(kKeyboardA, 1, 100);
    CHECK(table.get(kKeyboardA).secondaryScancode == 100);
    table.setScancode(kKeyboardA, 0, std::nullopt);
    CHECK(!table.get(kKeyboardA).primaryScancode.has_value());
    table.setScancode(kKeyboardA, 0, 512);
    CHECK(!table.get(kKeyboardA).primaryScancode.has_value());
    table.clear();
    for (int target = 0; target < kKeyboardTargetCount; ++target) {
        CHECK((table.get(target) == KeyboardBinding{}));
    }
    table.reset();
    CHECK(table.isDefault());
}

void testTargetsAndCoreRows() {
    for (int target = 0; target < kKeyboardTargetCount; ++target) {
        CHECK_EQ(keyboardTargetFromName(keyboardTargetName(target)), target);
    }
    CHECK_EQ(keyboardTargetFromName("nope"), -1);
    CHECK_EQ(coreKeyboardRowCount("gambatte"), 12);
    CHECK_EQ(coreKeyboardRowCount("mupen64plus_next"), 18);
    CHECK_EQ(coreKeyboardRowCount("pcsx_rearmed"), 24);
    CHECK_EQ(coreKeyboardRowCount("dolphin"), 20);
    CHECK_EQ(coreKeyboardTargetAt("mupen64plus_next", 14), kKeyboardLeftXNegative);
    CHECK_EQ(coreKeyboardTargetAt("mupen64plus_next", 17), kKeyboardLeftYPositive);
    CHECK_EQ(coreKeyboardTargetAt("pcsx_rearmed", 23), kKeyboardRightYPositive);
    CHECK_EQ(coreKeyboardTargetAt("dolphin", 12), kKeyboardLeftXNegative);
    CHECK_EQ(coreKeyboardTargetAt("dolphin", 19), kKeyboardRightYPositive);
    CHECK(std::string(keyboardTargetLabel(kKeyboardLeftXNegative)) == "Left Stick Left");
    CHECK(std::string(keyboardTargetLabel(kKeyboardRightYPositive)) == "Right Stick Down");

    KeyboardBindingTable n64;
    n64.resetForCore("mupen64plus_next");
    CHECK((n64.get(kKeyboardLeftXNegative) == KeyboardBinding{4, 80}));
    CHECK((n64.get(kKeyboardLeftXPositive) == KeyboardBinding{7, 79}));
    CHECK((n64.get(kKeyboardLeftYNegative) == KeyboardBinding{26, 82}));
    CHECK((n64.get(kKeyboardLeftYPositive) == KeyboardBinding{22, 81}));
    CHECK((n64.get(kKeyboardDpadUp) == KeyboardBinding{}));
    CHECK((n64.get(kKeyboardB) == KeyboardBinding{40, 44}));
    CHECK((n64.get(kKeyboardY) == KeyboardBinding{225, 229}));
    CHECK((n64.get(kKeyboardA) == KeyboardBinding{27, std::nullopt}));
    CHECK((n64.get(kKeyboardX) == KeyboardBinding{29, std::nullopt}));
}

void testRuntimeSynthesis() {
    KeyboardBindingTable table(false);
    table.setScancode(kKeyboardA, 0, 10);
    table.setScancode(kKeyboardB, 0, 11);
    table.setScancode(kKeyboardLeftXNegative, 0, 20);
    table.setScancode(kKeyboardLeftXPositive, 0, 21);
    table.setScancode(kKeyboardLeftYNegative, 0, 22);
    table.setScancode(kKeyboardRightYPositive, 0, 23);

    std::array<bool, kKeyboardScancodeCount> held{};
    held[10] = true;
    held[11] = true;
    held[20] = true;
    held[22] = true;
    held[23] = true;
    KeyboardRuntimeState state = synthesizeKeyboardState(table, held);
    CHECK((state.buttonsMask & (1 << kJoypadBitA)) != 0);
    CHECK((state.buttonsMask & (1 << kJoypadBitB)) != 0);
    CHECK_EQ(state.leftX, -32768);
    CHECK_EQ(state.leftY, -32768);
    CHECK_EQ(state.rightY, 32767);

    held[21] = true;
    state = synthesizeKeyboardState(table, held);
    CHECK_EQ(state.leftX, 0);
    CHECK_EQ(state.leftY, -32768);
}

void testSidecar() {
    KeyboardBindingTable table;
    const std::string text = serializeKeyboardBindingSidecar(table);
    const nlohmann::json root = nlohmann::json::parse(text);
    CHECK_EQ(root["protocolVersion"].get<int>(), 1);
    CHECK_EQ(root["bindings"].size(), static_cast<size_t>(kKeyboardTargetCount));
    CHECK(root["bindings"][0]["target"] == "a");
    CHECK_EQ(root["bindings"][0]["primaryScancode"].get<int>(), 40);
    CHECK(root["bindings"][23]["target"] == "right_y_positive");
    CHECK(root["bindings"][23]["primaryScancode"].is_null());

    const std::string path = "keyboard-bindings-test.json";
    CHECK(writeKeyboardBindingSidecar(path, table));
    std::remove(path.c_str());
}

}  // namespace

int main() {
    testDefaultsAndEditing();
    testTargetsAndCoreRows();
    testRuntimeSynthesis();
    testSidecar();
    return rommtest::finish("test_keyboard_binding_table");
}

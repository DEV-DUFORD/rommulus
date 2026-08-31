#include "romm_test.h"

#include "native/player/controller_slot_match.h"

int main() {
    using romm::player::controllerSlotNameMatches;
    using romm::player::steamVirtualControllerIndex;

    CHECK(controllerSlotNameMatches(
        "Wireless Controller", "PS4 Controller", "PS4 Controller", 1));
    CHECK(controllerSlotNameMatches(
        "Microsoft X-Box 360 pad 0", "Steam Deck Controller",
        "Steam Deck Controller", 0));
    CHECK(controllerSlotNameMatches(
        "Microsoft X-Box 360 pad 1", "PS4 Controller", "PS4 Controller", 1));
    CHECK(controllerSlotNameMatches(
        "Steam Deck Controller", "Steam Deck Controller", "Steam Deck Controller", 0));
    CHECK(!controllerSlotNameMatches(
        "Microsoft X-Box 360 pad 1", "Steam Deck Controller",
        "Steam Deck Controller", 0));
    CHECK(!controllerSlotNameMatches(
        "Wireless Controller", "Steam Deck Controller", "Steam Deck Controller", 0));

    CHECK(steamVirtualControllerIndex("Microsoft X-Box 360 pad 12") == 12);
    CHECK(!steamVirtualControllerIndex("Microsoft X-Box 360 pad nope").has_value());
    CHECK(!steamVirtualControllerIndex("PS4 Controller").has_value());
    return 0;
}

#include "native/player/presentation_dirty_state.h"

#include "romm_test.h"

using romm::player::PresentationDirtyState;

namespace {

void testInitialPresentationIsDirty() {
    PresentationDirtyState state;
    CHECK(state.consume());
    CHECK(!state.consume());
}

void testFrameOrOverlayRequestSchedulesOnePresentation() {
    PresentationDirtyState state;
    state.consume();

    state.request();
    state.request();
    CHECK(state.consume());
    CHECK(!state.consume());

    state.request();
    CHECK(state.consume());
}

}  // namespace

int main() {
    testInitialPresentationIsDirty();
    testFrameOrOverlayRequestSchedulesOnePresentation();
    return rommtest::finish("test_presentation_dirty_state");
}

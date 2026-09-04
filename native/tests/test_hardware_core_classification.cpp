// test_hardware_core_classification.cpp — pins the hardware-rendering core
// classification (native/player/include/native/player/hardware_core.h), the
// single source of truth behind the player's GL-path selection and the
// ROMM_WIN32_SOFTWARE_ONLY fail-closed launch gate. A core misclassified as
// software would silently lose its render context; one misclassified as
// hardware would be rejected on builds without a GPU path.
#include "romm_test.h"

#include "native/player/hardware_core.h"

int main() {
    // Hardware-rendering cores (GL via SdlHardwareContext).
    CHECK(romm::player::isHardwareRenderingCore("mupen64plus_next"));
    CHECK(romm::player::isHardwareRenderingCore("dolphin"));
    CHECK(romm::player::isHardwareRenderingCore("lrps2"));

    // Software-rendering cores (software video sink).
    CHECK(!romm::player::isHardwareRenderingCore("test_core"));
    CHECK(!romm::player::isHardwareRenderingCore("gambatte"));
    CHECK(!romm::player::isHardwareRenderingCore("fceumm"));
    CHECK(!romm::player::isHardwareRenderingCore("snes9x"));
    CHECK(!romm::player::isHardwareRenderingCore("mgba"));
    CHECK(!romm::player::isHardwareRenderingCore("sameboy"));
    CHECK(!romm::player::isHardwareRenderingCore("genesis_plus_gx"));
    CHECK(!romm::player::isHardwareRenderingCore("pcsx_rearmed"));
    CHECK(!romm::player::isHardwareRenderingCore("stella"));
    CHECK(!romm::player::isHardwareRenderingCore("mednafen_wswan"));
    CHECK(!romm::player::isHardwareRenderingCore("mednafen_ngp"));
    CHECK(!romm::player::isHardwareRenderingCore("handy"));
    CHECK(!romm::player::isHardwareRenderingCore("prosystem"));
    CHECK(!romm::player::isHardwareRenderingCore("beetle_pce_fast"));

    // Unknown/empty ids are software (the player's normal launch path).
    CHECK(!romm::player::isHardwareRenderingCore(""));
    CHECK(!romm::player::isHardwareRenderingCore("not_a_core"));

    return rommtest::finish("test_hardware_core_classification");
}

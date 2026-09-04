// hardware_core.cpp — hardware-rendering core classification (see
// native/player/include/native/player/hardware_core.h for the contract).
#include "native/player/hardware_core.h"

namespace romm::player {

bool isHardwareRenderingCore(const std::string& coreId) {
    return coreId == "mupen64plus_next" || coreId == "dolphin" ||
           coreId == "lrps2";
}

}  // namespace romm::player

// hardware_core.h — single source of truth for the hardware-rendering core
// classification, shared by the player (main.cpp) and the native tests.
//
// These cores render through a platform OpenGL context (the player's
// SdlHardwareContext) instead of the software video sink: they issue
// RETRO_ENVIRONMENT_SET_HW_RENDER at startup and cannot run without a working
// hardware render context. Every hardware-vs-software decision in the player
// — GL context attributes, SdlHardwareContext creation, the pause-menu
// present() gate, the Steam Deck player re-exec, and the
// ROMM_WIN32_SOFTWARE_ONLY fail-closed launch gate — must go through this
// helper so a new GL core is added in exactly one place.
#pragma once

#include <string>

namespace romm::player {

bool isHardwareRenderingCore(const std::string& coreId);

}  // namespace romm::player

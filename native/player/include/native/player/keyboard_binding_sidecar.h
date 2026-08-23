#pragma once

#include <string>

#include "native/player/keyboard_binding_table.h"

namespace romm::player {

constexpr int kKeyboardBindingSidecarVersion = 1;

std::string serializeKeyboardBindingSidecar(const KeyboardBindingTable& table);
bool writeKeyboardBindingSidecar(const std::string& path,
                                 const KeyboardBindingTable& table);

}  // namespace romm::player

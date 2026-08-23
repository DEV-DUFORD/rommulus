#include "native/player/keyboard_binding_sidecar.h"

#include <nlohmann/json.hpp>

#include "atomic_file_store.h"

namespace romm::player {

std::string serializeKeyboardBindingSidecar(const KeyboardBindingTable& table) {
    nlohmann::ordered_json root;
    root["protocolVersion"] = kKeyboardBindingSidecarVersion;
    nlohmann::ordered_json bindings = nlohmann::ordered_json::array();
    for (int target = 0; target < kKeyboardTargetCount; ++target) {
        const KeyboardBinding& binding = table.get(target);
        nlohmann::ordered_json entry;
        entry["target"] = keyboardTargetName(target);
        entry["primaryScancode"] = binding.primaryScancode.has_value()
            ? nlohmann::ordered_json(*binding.primaryScancode)
            : nlohmann::ordered_json(nullptr);
        entry["secondaryScancode"] = binding.secondaryScancode.has_value()
            ? nlohmann::ordered_json(*binding.secondaryScancode)
            : nlohmann::ordered_json(nullptr);
        bindings.push_back(std::move(entry));
    }
    root["bindings"] = std::move(bindings);
    return root.dump(2);
}

bool writeKeyboardBindingSidecar(const std::string& path,
                                 const KeyboardBindingTable& table) {
    const std::string json = serializeKeyboardBindingSidecar(table);
    return romm::atomicWriteFile(path, json.data(), json.size());
}

}  // namespace romm::player

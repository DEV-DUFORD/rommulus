// binding_sidecar.cpp — versioned JSON sidecar for the player's RetroPad
// binding table. See binding_sidecar.h for the schema and port notes.
//
// JSON uses the vendored header-only nlohmann/json (MIT, third_party/); the
// file lands on disk through romm::atomicWriteFile (write-temp, fsync,
// rename — the same pattern as the result file).
#include "native/player/binding_sidecar.h"

#include <nlohmann/json.hpp>

#include <cctype>
#include <cstdio>
#include <cstdlib>

#include "atomic_file_store.h"

namespace romm::player {
namespace {

using nlohmann::json;

std::string toLower(std::string value) {
    for (char& c : value) {
        c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
    }
    return value;
}

bool hexNibble(char c, int* out) {
    if (c >= '0' && c <= '9') {
        *out = c - '0';
        return true;
    }
    if (c >= 'a' && c <= 'f') {
        *out = c - 'a' + 10;
        return true;
    }
    if (c >= 'A' && c <= 'F') {
        *out = c - 'A' + 10;
        return true;
    }
    return false;
}

json bindingSourceToJson(const BindingSource& source) {
    json entry;
    switch (source.kind) {
        case BindingSource::Kind::kUnbound:
            entry["type"] = "unbound";
            break;
        case BindingSource::Kind::kButton:
            entry["type"] = "button";
            entry["button"] = padButtonName(source.button);
            break;
        case BindingSource::Kind::kAxisDirection:
            entry["type"] = "axis_direction";
            entry["axis"] = padAxisName(source.axis);
            entry["polarity"] = source.polarity < 0 ? -1 : 1;
            break;
    }
    return entry;
}

}  // namespace

DeviceBindings globalBindingDevice(const BindingTable& table,
                                   const BindingTable& secondaryTable) {
    DeviceBindings device;
    device.table = table;
    device.secondaryTable = secondaryTable;
    return device;
}

std::string normalizedDeviceIdentity(const std::string& guid) {
    const std::string canonical = toLower(guid);
    if (canonical.size() != 32) {
        return "guid:" + canonical;
    }
    // SDL's USB GUID convention (documented assumption — the follow-up
    // ingestion sub-unit confirms it against real devices): little-endian
    // uint16 vendor ID at byte offset 1, product ID at byte offset 3 of the
    // 16-byte GUID. Byte N is hex chars [2N, 2N+1], high nibble first.
    const auto byteAt = [&canonical](int n) {
        int hi = -1;
        int lo = -1;
        if (!hexNibble(canonical[2 * n], &hi) || !hexNibble(canonical[2 * n + 1], &lo)) {
            return -1;
        }
        return (hi << 4) | lo;
    };
    const int b1 = byteAt(1);
    const int b2 = byteAt(2);
    const int b3 = byteAt(3);
    const int b4 = byteAt(4);
    if (b1 < 0 || b2 < 0 || b3 < 0 || b4 < 0) {
        return "guid:" + canonical;
    }
    const int vendorId = b1 | (b2 << 8);
    const int productId = b3 | (b4 << 8);
    if (vendorId > 0 && productId > 0) {
        char buffer[48];
        std::snprintf(buffer, sizeof(buffer), "vid:%04x-pid:%04x", vendorId, productId);
        return buffer;
    }
    return "guid:" + canonical;
}

std::string serializeBindingSidecar(const std::vector<DeviceBindings>& devices) {
    json root;
    root["protocolVersion"] = kBindingSidecarVersion;
    json deviceArray = json::array();
    for (const DeviceBindings& device : devices) {
        json entry;
        entry["guid"] = toLower(device.guid);
        json identity;
        identity["vendorId"] = nullptr;
        identity["productId"] = nullptr;
        const std::string descriptor = normalizedDeviceIdentity(device.guid);
        // Re-derive the numeric fields so JSON and descriptor always agree.
        if (descriptor.rfind("vid:", 0) == 0) {
            int vendorId = 0;
            int productId = 0;
            if (std::sscanf(descriptor.c_str(), "vid:%x-pid:%x", &vendorId, &productId) == 2) {
                identity["vendorId"] = vendorId;
                identity["productId"] = productId;
            }
        }
        identity["descriptor"] = descriptor;
        entry["identity"] = identity;
        json bindings = json::array();
        for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
            json bindingEntry;
            bindingEntry["slot"] = retroPadSlotName(slot);
            bindingEntry.update(bindingSourceToJson(device.table.get(slot)));
            bindings.push_back(std::move(bindingEntry));
        }
        entry["bindings"] = std::move(bindings);
        if (!device.secondaryTable.isUnmapped()) {
            json secondary = json::array();
            for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
                json bindingEntry;
                bindingEntry["slot"] = retroPadSlotName(slot);
                bindingEntry.update(bindingSourceToJson(device.secondaryTable.get(slot)));
                secondary.push_back(std::move(bindingEntry));
            }
            entry["secondaryBindings"] = std::move(secondary);
        }
        deviceArray.push_back(std::move(entry));
    }
    root["devices"] = std::move(deviceArray);
    return root.dump(2);
}

bool writeBindingSidecar(const std::string& path,
                         const std::vector<DeviceBindings>& devices) {
    const std::string serialized = serializeBindingSidecar(devices);
    if (!romm::atomicWriteFile(path, serialized.data(), serialized.size())) {
        return false;
    }
    return true;
}

}  // namespace romm::player

// binding_sidecar.h — versioned JSON sidecar persisting the player's
// RetroPad binding table (LINUX_X64.md section 11.9: "binding persistence by
// stable SDL GUID plus normalized identity").
//
// The player atomically writes <sessionDir>/controller-bindings.json when it
// exits with a non-default BindingTable; the desktop supervisor ingests this
// file in a follow-up sub-unit (nothing reads it yet — writing it correctly
// is this sub-unit's scope). Schema v1 (2-space indent, fixed field order):
//
// {
//   "protocolVersion": 1,
//   "devices": [
//     {
//       "guid": "<32-hex-char SDL joystick GUID string>",
//       "identity": {
//         "vendorId": <int|null>,
//         "productId": <int|null>,
//         "descriptor": "vid:xxxx-pid:yyyy"
//       },
//       "bindings": [
//         {"slot": "a", "type": "button", "button": "south"},
//         {"slot": "b", "type": "axis_direction", "axis": "left_x", "polarity": -1},
//         {"slot": "select", "type": "unbound"}
//       ]
//     }
//   ]
// }
//
// The `descriptor` mirrors Android's DeviceSignature descriptor format
// ("vid:%04x-pid:%04x"). The GUID string is the stable key; the vendor/
// product fields are parsed from SDL's USB GUID byte convention (little-endian
// uint16 at byte offsets 1 and 3) and fall back to "guid:<hex>" when either
// is absent (non-USB device).
#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "native/player/binding_table.h"

namespace romm::player {

constexpr int kBindingSidecarVersion = 1;

// One connected pad's identity plus the binding table it was last edited
// with. (The player keeps a single global BindingTable — SdlInput applies it
// to every port — so each device entry carries the same table, keyed by that
// device's stable GUID for ingestion.)
struct DeviceBindings {
    int port = 0;          // 0-based console port whose table was edited
    std::string guid;      // canonical 32-hex-char SDL joystick GUID string
    std::string identity;  // normalized descriptor (normalizedDeviceIdentity)
    BindingTable table;
    BindingTable secondaryTable{false};
};

// Builds the controller-independent device entry used when a remapped global
// table outlives the last connected SDL gamepad during player shutdown.
DeviceBindings globalBindingDevice(const BindingTable& table,
                                   const BindingTable& secondaryTable);

// Builds the normalized identity descriptor for a canonicalized (lowercase,
// 32-hex) SDL GUID: "vid:%04x-pid:%04x" — Android's DeviceSignature format —
// or "guid:<hex>" when the vendor/product fields are absent. Pure function;
// host-testable without SDL.
std::string normalizedDeviceIdentity(const std::string& guid);

// Serializes the sidecar in canonical form (fixed field order, 2-space
// indent). The output always re-parses to equal device entries (round-trip).
std::string serializeBindingSidecar(const std::vector<DeviceBindings>& devices);

// Atomically writes the serialized sidecar to `path` (temp file + rename,
// the same pattern as the result file). Returns false on I/O failure.
bool writeBindingSidecar(const std::string& path,
                         const std::vector<DeviceBindings>& devices);

}  // namespace romm::player

// protocol.cpp — strict v2 request/result JSON parsing and canonical
// serialization (LINUX_X64.md section 12.2 / 12.3).
//
// Parsing is strict on purpose: this is a security boundary. Unknown
// fields are rejected (the schema must not carry origin/username/token/
// server-save-id/upload-url, and a typo must not smuggle one in), wrong
// types are rejected, and the no-throw nlohmann/json parse overload is
// used so malformed input can never raise out of the validator.
//
// v2 adds the optional request field "controllerBindings" (per-device
// RetroPad binding tables to apply at launch). Its device-entry shape
// reuses the sidecar schema (binding_sidecar.cpp) verbatim, so a sidecar's
// "devices" array pastes straight into a request.
#include "native/player/protocol.h"

#include <algorithm>
#include <array>

#include <nlohmann/json.hpp>

namespace romm::player {
namespace {

using nlohmann::ordered_json;

template <typename T>
std::optional<T> reject(std::string* error, const std::string& message) {
    if (error != nullptr) *error = message;
    return std::nullopt;
}

// No-throw parse: malformed JSON yields std::nullopt, never an exception.
std::optional<ordered_json> parseJson(const std::string& text,
                                      std::string* error) {
    ordered_json j = ordered_json::parse(text, nullptr, false);
    if (j.is_discarded()) {
        return reject<ordered_json>(error, "malformed JSON");
    }
    return j;
}

bool getString(const ordered_json& j, const char* key, std::string& out,
               std::string& error) {
    if (!j[key].is_string()) {
        error = std::string("field must be a string: ") + key;
        return false;
    }
    out = j[key].get<std::string>();
    return true;
}

// null or non-negative integer.
bool getNullableSize(const ordered_json& j, const char* key,
                     std::optional<int64_t>& out, std::string& error) {
    if (j[key].is_null()) {
        out = std::nullopt;
        return true;
    }
    if (!j[key].is_number_integer() || j[key].get<int64_t>() < 0) {
        error = std::string(key) + " must be null or a non-negative integer";
        return false;
    }
    out = j[key].get<int64_t>();
    return true;
}

// non-negative integer.
bool getNonNegativeInt(const ordered_json& j, const char* key, int64_t& out,
                       std::string& error) {
    if (!j[key].is_number_integer() || j[key].get<int64_t>() < 0) {
        error = std::string(key) + " must be a non-negative integer";
        return false;
    }
    out = j[key].get<int64_t>();
    return true;
}

// null or string.
bool getNullableString(const ordered_json& j, const char* key,
                       std::optional<std::string>& out, std::string& error) {
    if (j[key].is_null()) {
        out = std::nullopt;
        return true;
    }
    if (!j[key].is_string()) {
        error = std::string(key) + " must be a string or null";
        return false;
    }
    out = j[key].get<std::string>();
    return true;
}

template <typename Container>
void checkUnknownFields(const ordered_json& j, const Container& allowed,
                        std::string& error) {
    for (auto it = j.begin(); it != j.end(); ++it) {
        bool known = std::any_of(allowed.begin(), allowed.end(),
                                 [&](const char* name) {
                                     return it.key() == name;
                                 });
        if (!known) {
            error = "unknown field: " + it.key();
            return;
        }
    }
}

// --------------------------------------------------------------------- v2
// controllerBindings parsing (sidecar-shaped device entries).

// null or non-negative integer, narrowed to int only after the full-range
// check (vendor/product IDs are 16-bit by construction but this stays safe
// for any future widening).
bool getNullableId(const ordered_json& j, const char* key,
                   std::optional<int>& out, std::string& error) {
    if (j[key].is_null()) {
        out = std::nullopt;
        return true;
    }
    if (!j[key].is_number_integer() || j[key].get<int64_t>() < 0) {
        error = std::string(key) + " must be null or a non-negative integer";
        return false;
    }
    out = static_cast<int>(j[key].get<int64_t>());
    return true;
}

// Parses one binding entry (sidecar shape: {"slot", "type", ...}) into the
// table at `slot`. The union of all fields an entry may carry is checked
// first; the declared type then pins down the EXACT field subset, so
// {"type":"unbound","button":"south"} is rejected as well as unknown names.
bool parseBindingEntry(const ordered_json& j, int slot, BindingTable& table,
                       std::string& error) {
    static const std::array<const char*, 5> kUnionFields = {
        {"slot", "type", "button", "axis", "polarity"}};
    checkUnknownFields(j, kUnionFields, error);
    if (!error.empty()) return false;

    std::string slotName;
    if (!getString(j, "slot", slotName, error)) return false;
    const int parsedSlot = retroPadSlotFromName(slotName);
    if (parsedSlot < 0) {
        error = "unknown binding slot: " + slotName;
        return false;
    }
    // Entries must arrive in slot order (the canonical producers — the
    // sidecar and the desktop serializer — both emit all entries in order); a
    // duplicate or gap is rejected here.
    if (parsedSlot != slot) {
        error = "binding slot out of order or duplicate: " + slotName;
        return false;
    }

    if (!j.contains("type") || !j["type"].is_string()) {
        error = "binding type must be a string";
        return false;
    }
    const std::string type = j["type"].get<std::string>();

    if (type == "unbound") {
        if (j.contains("button") || j.contains("axis") || j.contains("polarity")) {
            error = "unbound binding must not carry button/axis/polarity";
            return false;
        }
        table.set(slot, BindingSource::unbound());
        return true;
    }
    if (type == "button") {
        if (j.contains("axis") || j.contains("polarity")) {
            error = "button binding must not carry axis/polarity";
            return false;
        }
        std::string buttonName;
        if (!getString(j, "button", buttonName, error)) return false;
        const auto button = padButtonFromName(buttonName);
        if (!button) {
            error = "unknown pad button: " + buttonName;
            return false;
        }
        table.set(slot, BindingSource::ofButton(*button));
        return true;
    }
    if (type == "axis") {
        if (j.contains("button") || j.contains("polarity")) {
            error = "axis binding must not carry button/polarity";
            return false;
        }
        std::string axisName;
        if (!getString(j, "axis", axisName, error)) return false;
        const auto axis = padAxisFromName(axisName);
        if (!axis) {
            error = "unknown pad axis: " + axisName;
            return false;
        }
        table.set(slot, BindingSource::ofAxis(*axis));
        return true;
    }
    if (type == "axis_direction") {
        if (j.contains("button")) {
            error = "axis_direction binding must not carry button";
            return false;
        }
        std::string axisName;
        if (!getString(j, "axis", axisName, error)) return false;
        const auto axis = padAxisFromName(axisName);
        if (!axis) {
            error = "unknown pad axis: " + axisName;
            return false;
        }
        if (!j.contains("polarity") || !j["polarity"].is_number_integer()) {
            error = "binding polarity must be an integer";
            return false;
        }
        const int polarity = j["polarity"].get<int>();
        if (polarity != -1 && polarity != 1) {
            error = "binding polarity must be -1 or 1";
            return false;
        }
        table.set(slot, BindingSource::axisDirection(*axis, polarity));
        return true;
    }
    error = "unknown binding type: " + type;
    return false;
}

bool parseNamedBindingSource(const ordered_json& j, const char* expectedSlot,
                             BindingSource& out, std::string& error) {
    std::string slotName;
    if (!getString(j, "slot", slotName, error)) return false;
    if (slotName != expectedSlot) {
        error = "binding slot out of order or duplicate: " + slotName;
        return false;
    }
    ordered_json normalized = j;
    normalized["slot"] = retroPadSlotName(0);
    BindingTable table(false);
    if (!parseBindingEntry(normalized, 0, table, error)) return false;
    out = table.get(0);
    return true;
}

// Parses the v2 request's optional controllerBindings object. Devices carry
// guid + identity + all RetroPad slot bindings in slot order. Twelve-entry
// tables from older desktop builds remain accepted; their new L2/R2/L3/R3
// slots retain the BindingTable defaults.
bool parseControllerBindings(const ordered_json& j, ControllerBindings& out,
                             std::string& error) {
    static const std::array<const char*, 2> kFields = {
        {"devices", "pauseMenuBindings"}};
    if (!j.contains("devices")) {
        error = "missing controllerBindings field: devices";
        return false;
    }
    checkUnknownFields(j, kFields, error);
    if (!error.empty()) return false;

    const ordered_json& devices = j["devices"];
    if (!devices.is_array()) {
        error = "controllerBindings.devices must be an array";
        return false;
    }

    static const std::array<const char*, 4> kDeviceFields = {
        {"guid", "identity", "bindings", "secondaryBindings"}};
    static const std::array<const char*, 3> kRequiredDeviceFields = {
        {"guid", "identity", "bindings"}};
    static const std::array<const char*, 3> kIdentityFields = {
        {"vendorId", "productId", "descriptor"}};

    for (const ordered_json& d : devices) {
        if (!d.is_object()) {
            error = "controllerBindings device must be an object";
            return false;
        }
        checkUnknownFields(d, kDeviceFields, error);
        if (!error.empty()) return false;
        for (const char* key : kRequiredDeviceFields) {
            if (!d.contains(key)) {
                error = std::string("missing controllerBindings device field: ") + key;
                return false;
            }
        }

        ControllerBindingDevice device;
        if (!getString(d, "guid", device.guid, error)) return false;

        const ordered_json& id = d["identity"];
        if (!id.is_object()) {
            error = "controllerBindings identity must be an object";
            return false;
        }
        checkUnknownFields(id, kIdentityFields, error);
        if (!error.empty()) return false;
        for (const char* key : kIdentityFields) {
            if (!id.contains(key)) {
                error = std::string("missing controllerBindings identity field: ") + key;
                return false;
            }
        }
        if (!getNullableId(id, "vendorId", device.identity.vendorId, error)) return false;
        if (!getNullableId(id, "productId", device.identity.productId, error)) return false;
        if (!getString(id, "descriptor", device.identity.descriptor, error)) return false;

        const ordered_json& bindings = d["bindings"];
        if (!bindings.is_array()) {
            error = "controllerBindings bindings must be an array";
            return false;
        }
        if (bindings.size() != 12 &&
            bindings.size() != static_cast<size_t>(kRetroPadSlotCount)) {
            error = "controllerBindings bindings must carry exactly 12 or 16 entries";
            return false;
        }
        for (size_t i = 0; i < bindings.size(); ++i) {
            if (!parseBindingEntry(bindings[i], static_cast<int>(i), device.table, error)) {
                return false;
            }
        }
        if (d.contains("secondaryBindings")) {
            const ordered_json& secondary = d["secondaryBindings"];
            if (!secondary.is_array() ||
                (secondary.size() != 12 &&
                 secondary.size() != static_cast<size_t>(kRetroPadSlotCount))) {
                error =
                    "controllerBindings secondaryBindings must carry exactly 12 or 16 entries";
                return false;
            }
            for (size_t i = 0; i < secondary.size(); ++i) {
                if (!parseBindingEntry(
                        secondary[i], static_cast<int>(i), device.secondaryTable, error)) {
                    return false;
                }
            }
        }

        out.devices.push_back(std::move(device));
    }
    if (j.contains("pauseMenuBindings")) {
        const ordered_json& pauseBindings = j["pauseMenuBindings"];
        if (!pauseBindings.is_array() || pauseBindings.size() != 2) {
            error = "pauseMenuBindings must carry exactly 2 entries";
            return false;
        }
        std::array<BindingSource, 2> parsed;
        if (!parseNamedBindingSource(
                pauseBindings[0], "primary", parsed[0], error) ||
            !parseNamedBindingSource(
                pauseBindings[1], "secondary", parsed[1], error)) {
            return false;
        }
        out.pauseMenuBindings = parsed;
    }
    return true;
}

bool getNullableScancode(const ordered_json& j, const char* key,
                         std::optional<int>& out, std::string& error) {
    if (j[key].is_null()) {
        out = std::nullopt;
        return true;
    }
    if (!j[key].is_number_integer()) {
        error = std::string(key) + " must be null or an integer";
        return false;
    }
    const int64_t value = j[key].get<int64_t>();
    if (value < 0 || value > kKeyboardScancodeMax) {
        error = std::string(key) + " must be null or an integer from 0 to 511";
        return false;
    }
    out = static_cast<int>(value);
    return true;
}

bool parseKeyboardBindings(const ordered_json& j, KeyboardBindings& out,
                           std::string& error) {
    static const std::array<const char*, 1> kFields = {{"bindings"}};
    if (!j.is_object()) {
        error = "keyboardBindings must be an object";
        return false;
    }
    if (!j.contains("bindings")) {
        error = "missing keyboardBindings field: bindings";
        return false;
    }
    checkUnknownFields(j, kFields, error);
    if (!error.empty()) return false;

    const ordered_json& bindings = j["bindings"];
    if (!bindings.is_array() ||
        bindings.size() != static_cast<size_t>(kKeyboardTargetCount)) {
        error = "keyboardBindings.bindings must carry exactly 24 entries";
        return false;
    }
    static const std::array<const char*, 3> kEntryFields = {{
        "target", "primaryScancode", "secondaryScancode",
    }};
    for (int target = 0; target < kKeyboardTargetCount; ++target) {
        const ordered_json& entry = bindings[static_cast<size_t>(target)];
        if (!entry.is_object()) {
            error = "keyboard binding entry must be an object";
            return false;
        }
        for (const char* key : kEntryFields) {
            if (!entry.contains(key)) {
                error = std::string("missing keyboard binding field: ") + key;
                return false;
            }
        }
        checkUnknownFields(entry, kEntryFields, error);
        if (!error.empty()) return false;
        std::string targetName;
        if (!getString(entry, "target", targetName, error)) return false;
        const int parsedTarget = keyboardTargetFromName(targetName);
        if (parsedTarget < 0) {
            error = "unknown keyboard binding target: " + targetName;
            return false;
        }
        if (parsedTarget != target) {
            error = "keyboard binding target out of order or duplicate: " + targetName;
            return false;
        }
        KeyboardBinding binding;
        if (!getNullableScancode(entry, "primaryScancode",
                                 binding.primaryScancode, error) ||
            !getNullableScancode(entry, "secondaryScancode",
                                 binding.secondaryScancode, error)) {
            return false;
        }
        out.table.set(target, binding);
    }
    return true;
}

}  // namespace

const char* toString(ExitKind kind) {
    switch (kind) {
        case ExitKind::Completed:
            return "completed";
        case ExitKind::UserCancelledBeforeStart:
            return "user_cancelled_before_start";
        case ExitKind::CoreRequestedShutdown:
            return "core_requested_shutdown";
        case ExitKind::LaunchFailed:
            return "launch_failed";
        case ExitKind::RuntimeFailed:
            return "runtime_failed";
    }
    return "completed";
}

std::optional<ExitKind> exitKindFromString(const std::string& value) {
    if (value == "completed") return ExitKind::Completed;
    if (value == "user_cancelled_before_start")
        return ExitKind::UserCancelledBeforeStart;
    if (value == "core_requested_shutdown")
        return ExitKind::CoreRequestedShutdown;
    if (value == "launch_failed") return ExitKind::LaunchFailed;
    if (value == "runtime_failed") return ExitKind::RuntimeFailed;
    return std::nullopt;
}

std::optional<PlayerRequest> parseRequest(const std::string& text,
                                          std::string* error) {
    std::string err;
    auto jOpt = parseJson(text, &err);
    if (!jOpt) return reject<PlayerRequest>(error, err);
    const ordered_json& j = *jOpt;
    if (!j.is_object())
        return reject<PlayerRequest>(error, "top-level JSON must be an object");

    // controllerBindings is v2's OPTIONAL field: it passes the unknown-field
    // check below but is not part of the required set (absent = defaults).
    static const std::array<const char*, 16> kFields = {{
        "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
        "corePath",        "contentPath", "contentHash", "systemDir",
        "savePath",        "candidateSavePath", "resultPath",
        "expectedSaveSize", "video", "controllerBindings", "keyboardBindings",
        "rendererOverride",
    }};
    static const std::array<const char*, 13> kRequiredFields = {{
        "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
        "corePath",        "contentPath", "contentHash", "systemDir",
        "savePath",        "candidateSavePath", "resultPath",
        "expectedSaveSize", "video",
    }};
    for (const char* key : kRequiredFields) {
        if (!j.contains(key))
            return reject<PlayerRequest>(error,
                                         std::string("missing required field: ") +
                                             key);
    }
    checkUnknownFields(j, kFields, err);
    if (!err.empty()) return reject<PlayerRequest>(error, err);

    PlayerRequest r;
    // Read as int64_t and require an exact match with the only supported
    // version BEFORE narrowing to int: a 32-bit read would wrap (e.g.
    // 2^32+1 -> 1) and bypass the "only v1 accepted" invariant.
    if (!j["protocolVersion"].is_number_integer())
        return reject<PlayerRequest>(error,
                                     "protocolVersion must be an integer");
    const auto requestVersion = j["protocolVersion"].get<int64_t>();
    if (requestVersion != kProtocolVersion)
        return reject<PlayerRequest>(error,
                                     "unsupported protocolVersion: " +
                                         std::to_string(requestVersion));
    r.protocolVersion = static_cast<int>(requestVersion);

    if (!getString(j, "sessionId", r.sessionId, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "coreId", r.coreId, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "coreBuildRevision", r.coreBuildRevision, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "corePath", r.corePath, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "contentPath", r.contentPath, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "contentHash", r.contentHash, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "systemDir", r.systemDir, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "savePath", r.savePath, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "candidateSavePath", r.candidateSavePath, err))
        return reject<PlayerRequest>(error, err);
    if (!getString(j, "resultPath", r.resultPath, err))
        return reject<PlayerRequest>(error, err);

    // 64-bit byte size: values above INT_MAX (e.g. 2^31) are legal and
    // must not be narrowed. getNullableSize enforces null-or-non-negative
    // on the full int64_t range.
    if (!getNullableSize(j, "expectedSaveSize", r.expectedSaveSize, err))
        return reject<PlayerRequest>(error, err);

    if (!j["video"].is_object())
        return reject<PlayerRequest>(error, "video must be an object");
    const ordered_json& v = j["video"];
    static const std::array<const char*, 4> kVideoFields = {{
        "fullscreen", "integerScaling", "scanlines", "sharpFilter",
    }};
    for (const char* key : kVideoFields) {
        if (!v.contains(key))
            return reject<PlayerRequest>(error,
                                         std::string("missing video field: ") +
                                              key);
        if (!v[key].is_boolean())
            return reject<PlayerRequest>(
                error, std::string("video field must be a boolean: ") + key);
    }
    checkUnknownFields(v, kVideoFields, err);
    if (!err.empty()) return reject<PlayerRequest>(error, err);
    r.video.fullscreen = v["fullscreen"].get<bool>();
    r.video.integerScaling = v["integerScaling"].get<bool>();
    r.video.scanlines = v["scanlines"].get<bool>();
    r.video.sharpFilter = v["sharpFilter"].get<bool>();

    // v2 optional field: stored controller bindings to seed the BindingTable
    // at launch. Absent (the common case — no stored overrides) means the
    // player keeps its built-in defaults.
    if (j.contains("controllerBindings")) {
        ControllerBindings bindings;
        if (!parseControllerBindings(j["controllerBindings"], bindings, err))
            return reject<PlayerRequest>(error, err);
        r.controllerBindings = std::move(bindings);
    }
    if (j.contains("keyboardBindings")) {
        KeyboardBindings bindings;
        if (!parseKeyboardBindings(j["keyboardBindings"], bindings, err))
            return reject<PlayerRequest>(error, err);
        r.keyboardBindings = std::move(bindings);
    }
    if (j.contains("rendererOverride")) {
        if (!j["rendererOverride"].is_string())
            return reject<PlayerRequest>(error, "rendererOverride must be a string");
        const std::string value = j["rendererOverride"].get<std::string>();
        if (value != "software_hw")
            return reject<PlayerRequest>(
                error, "unknown rendererOverride: " + value);
        r.rendererOverride = RendererOverride::SoftwareHw;
    }

    return r;
}

std::optional<PlayerResult> parseResult(const std::string& text,
                                        std::string* error) {
    std::string err;
    auto jOpt = parseJson(text, &err);
    if (!jOpt) return reject<PlayerResult>(error, err);
    const ordered_json& j = *jOpt;
    if (!j.is_object())
        return reject<PlayerResult>(error, "top-level JSON must be an object");

    static const std::array<const char*, 12> kRequiredFields = {{
        "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
        "candidateSavePath", "saveHash", "saveSize", "frames",
        "audioUnderrunFrames", "audioOverrunFrames", "errorCode",
        "errorMessage",
    }};
    static const std::array<const char*, 13> kKnownFields = {{
        "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
        "candidateSavePath", "saveHash", "saveSize", "frames",
        "audioUnderrunFrames", "audioOverrunFrames", "errorCode",
        "errorMessage", "video",
    }};
    for (const char* key : kRequiredFields) {
        if (!j.contains(key))
            return reject<PlayerResult>(error,
                                        std::string("missing required field: ") +
                                            key);
    }
    checkUnknownFields(j, kKnownFields, err);
    if (!err.empty()) return reject<PlayerResult>(error, err);

    PlayerResult r;
    // Read as int64_t and require an exact match with the only supported
    // version BEFORE narrowing to int (same wraparound guard as
    // parseRequest: 2^32+1 must not masquerade as v1).
    if (!j["protocolVersion"].is_number_integer())
        return reject<PlayerResult>(error,
                                    "protocolVersion must be an integer");
    const auto resultVersion = j["protocolVersion"].get<int64_t>();
    if (resultVersion != kProtocolVersion)
        return reject<PlayerResult>(error,
                                    "unsupported protocolVersion: " +
                                        std::to_string(resultVersion));
    r.protocolVersion = static_cast<int>(resultVersion);

    if (!getString(j, "sessionId", r.sessionId, err))
        return reject<PlayerResult>(error, err);
    if (!j["exitKind"].is_string())
        return reject<PlayerResult>(error, "exitKind must be a string");
    auto kind = exitKindFromString(j["exitKind"].get<std::string>());
    if (!kind)
        return reject<PlayerResult>(error,
                                    "unknown exitKind: " +
                                        j["exitKind"].get<std::string>());
    r.exitKind = *kind;
    if (!j["checkpointWritten"].is_boolean())
        return reject<PlayerResult>(error,
                                    "checkpointWritten must be a boolean");
    r.checkpointWritten = j["checkpointWritten"].get<bool>();
    if (!getString(j, "candidateSavePath", r.candidateSavePath, err))
        return reject<PlayerResult>(error, err);
    if (!getNullableString(j, "saveHash", r.saveHash, err))
        return reject<PlayerResult>(error, err);
    if (!getNullableSize(j, "saveSize", r.saveSize, err))
        return reject<PlayerResult>(error, err);
    if (!getNonNegativeInt(j, "frames", r.frames, err))
        return reject<PlayerResult>(error, err);
    if (!getNonNegativeInt(j, "audioUnderrunFrames", r.audioUnderrunFrames,
                           err))
        return reject<PlayerResult>(error, err);
    if (!getNonNegativeInt(j, "audioOverrunFrames", r.audioOverrunFrames,
                           err))
        return reject<PlayerResult>(error, err);
    if (!getNullableString(j, "errorCode", r.errorCode, err))
        return reject<PlayerResult>(error, err);
    if (!getNullableString(j, "errorMessage", r.errorMessage, err))
        return reject<PlayerResult>(error, err);
    if (j.contains("video")) {
        if (!j["video"].is_object())
            return reject<PlayerResult>(error, "video must be an object");
        const ordered_json& v = j["video"];
        static const std::array<const char*, 4> kVideoFields = {{
            "fullscreen", "integerScaling", "scanlines", "sharpFilter",
        }};
        for (const char* key : kVideoFields) {
            if (!v.contains(key) || !v[key].is_boolean())
                return reject<PlayerResult>(
                    error, std::string("video.") + key + " must be a boolean");
        }
        checkUnknownFields(v, kVideoFields, err);
        if (!err.empty()) return reject<PlayerResult>(error, err);
        VideoSettings video;
        video.fullscreen = v["fullscreen"].get<bool>();
        video.integerScaling = v["integerScaling"].get<bool>();
        video.scanlines = v["scanlines"].get<bool>();
        video.sharpFilter = v["sharpFilter"].get<bool>();
        r.video = video;
    }

    return r;
}

std::string serializeRequest(const PlayerRequest& r) {
    ordered_json j;
    j["protocolVersion"] = r.protocolVersion;
    j["sessionId"] = r.sessionId;
    j["coreId"] = r.coreId;
    j["coreBuildRevision"] = r.coreBuildRevision;
    j["corePath"] = r.corePath;
    j["contentPath"] = r.contentPath;
    j["contentHash"] = r.contentHash;
    j["systemDir"] = r.systemDir;
    j["savePath"] = r.savePath;
    j["candidateSavePath"] = r.candidateSavePath;
    j["resultPath"] = r.resultPath;
    if (r.expectedSaveSize)
        j["expectedSaveSize"] = *r.expectedSaveSize;
    else
        j["expectedSaveSize"] = ordered_json(nullptr);
    ordered_json video;
    video["fullscreen"] = r.video.fullscreen;
    video["integerScaling"] = r.video.integerScaling;
    video["scanlines"] = r.video.scanlines;
    video["sharpFilter"] = r.video.sharpFilter;
    j["video"] = video;

    // v2 optional field: written only when present (absent = player defaults),
    // so a request with no stored bindings stays byte-identical to the v1
    // layout plus the version bump. Device entries use the sidecar's
    // canonical shape (fixed field order, 2-space indent via dump(2)).
    if (r.controllerBindings.has_value()) {
        ordered_json devices = ordered_json::array();
        for (const ControllerBindingDevice& device : r.controllerBindings->devices) {
            ordered_json entry;
            entry["guid"] = device.guid;
            ordered_json identity;
            if (device.identity.vendorId.has_value())
                identity["vendorId"] = *device.identity.vendorId;
            else
                identity["vendorId"] = ordered_json(nullptr);
            if (device.identity.productId.has_value())
                identity["productId"] = *device.identity.productId;
            else
                identity["productId"] = ordered_json(nullptr);
            identity["descriptor"] = device.identity.descriptor;
            entry["identity"] = std::move(identity);
            ordered_json bindings = ordered_json::array();
            for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
                const BindingSource& source = device.table.get(slot);
                ordered_json bindingEntry;
                bindingEntry["slot"] = retroPadSlotName(slot);
                switch (source.kind) {
                    case BindingSource::Kind::kUnbound:
                        bindingEntry["type"] = "unbound";
                        break;
                    case BindingSource::Kind::kButton:
                        bindingEntry["type"] = "button";
                        bindingEntry["button"] = padButtonName(source.button);
                        break;
                    case BindingSource::Kind::kAxis:
                        bindingEntry["type"] = "axis";
                        bindingEntry["axis"] = padAxisName(source.axis);
                        break;
                    case BindingSource::Kind::kAxisDirection:
                        bindingEntry["type"] = "axis_direction";
                        bindingEntry["axis"] = padAxisName(source.axis);
                        bindingEntry["polarity"] = source.polarity < 0 ? -1 : 1;
                        break;
                }
                bindings.push_back(std::move(bindingEntry));
            }
            entry["bindings"] = std::move(bindings);
            if (!device.secondaryTable.isUnmapped()) {
                ordered_json secondary = ordered_json::array();
                for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
                    ordered_json bindingEntry;
                    bindingEntry["slot"] = retroPadSlotName(slot);
                    const BindingSource& source = device.secondaryTable.get(slot);
                    switch (source.kind) {
                        case BindingSource::Kind::kUnbound:
                            bindingEntry["type"] = "unbound";
                            break;
                        case BindingSource::Kind::kButton:
                            bindingEntry["type"] = "button";
                            bindingEntry["button"] = padButtonName(source.button);
                            break;
                        case BindingSource::Kind::kAxis:
                            bindingEntry["type"] = "axis";
                            bindingEntry["axis"] = padAxisName(source.axis);
                            break;
                        case BindingSource::Kind::kAxisDirection:
                            bindingEntry["type"] = "axis_direction";
                            bindingEntry["axis"] = padAxisName(source.axis);
                            bindingEntry["polarity"] = source.polarity < 0 ? -1 : 1;
                            break;
                    }
                    secondary.push_back(std::move(bindingEntry));
                }
                entry["secondaryBindings"] = std::move(secondary);
            }
            devices.push_back(std::move(entry));
        }
        ordered_json controllerBindings;
        controllerBindings["devices"] = std::move(devices);
        if (r.controllerBindings->pauseMenuBindings.has_value()) {
            ordered_json pauseBindings = ordered_json::array();
            static constexpr const char* kPauseSlots[] = {
                "primary", "secondary"};
            for (int i = 0; i < 2; ++i) {
                ordered_json entry;
                entry["slot"] = kPauseSlots[i];
                const BindingSource& source =
                    (*r.controllerBindings->pauseMenuBindings)[i];
                switch (source.kind) {
                    case BindingSource::Kind::kUnbound:
                        entry["type"] = "unbound";
                        break;
                    case BindingSource::Kind::kButton:
                        entry["type"] = "button";
                        entry["button"] = padButtonName(source.button);
                        break;
                    case BindingSource::Kind::kAxis:
                        entry["type"] = "axis";
                        entry["axis"] = padAxisName(source.axis);
                        break;
                    case BindingSource::Kind::kAxisDirection:
                        entry["type"] = "axis_direction";
                        entry["axis"] = padAxisName(source.axis);
                        entry["polarity"] = source.polarity < 0 ? -1 : 1;
                        break;
                }
                pauseBindings.push_back(std::move(entry));
            }
            controllerBindings["pauseMenuBindings"] =
                std::move(pauseBindings);
        }
        j["controllerBindings"] = std::move(controllerBindings);
    }
    if (r.keyboardBindings.has_value()) {
        ordered_json entries = ordered_json::array();
        for (int target = 0; target < kKeyboardTargetCount; ++target) {
            const KeyboardBinding& binding = r.keyboardBindings->table.get(target);
            ordered_json entry;
            entry["target"] = keyboardTargetName(target);
            entry["primaryScancode"] = binding.primaryScancode.has_value()
                ? ordered_json(*binding.primaryScancode) : ordered_json(nullptr);
            entry["secondaryScancode"] = binding.secondaryScancode.has_value()
                ? ordered_json(*binding.secondaryScancode) : ordered_json(nullptr);
            entries.push_back(std::move(entry));
        }
        ordered_json keyboardBindings;
        keyboardBindings["bindings"] = std::move(entries);
        j["keyboardBindings"] = std::move(keyboardBindings);
    }
    if (r.rendererOverride.has_value())
        j["rendererOverride"] = "software_hw";
    return j.dump(2);
}

std::string serializeResult(const PlayerResult& r) {
    ordered_json j;
    j["protocolVersion"] = r.protocolVersion;
    j["sessionId"] = r.sessionId;
    j["exitKind"] = toString(r.exitKind);
    j["checkpointWritten"] = r.checkpointWritten;
    j["candidateSavePath"] = r.candidateSavePath;
    if (r.saveHash)
        j["saveHash"] = *r.saveHash;
    else
        j["saveHash"] = ordered_json(nullptr);
    if (r.saveSize)
        j["saveSize"] = *r.saveSize;
    else
        j["saveSize"] = ordered_json(nullptr);
    j["frames"] = r.frames;
    j["audioUnderrunFrames"] = r.audioUnderrunFrames;
    j["audioOverrunFrames"] = r.audioOverrunFrames;
    if (r.errorCode)
        j["errorCode"] = *r.errorCode;
    else
        j["errorCode"] = ordered_json(nullptr);
    if (r.errorMessage)
        j["errorMessage"] = *r.errorMessage;
    else
        j["errorMessage"] = ordered_json(nullptr);
    if (r.video.has_value()) {
        ordered_json video;
        video["fullscreen"] = r.video->fullscreen;
        video["integerScaling"] = r.video->integerScaling;
        video["scanlines"] = r.video->scanlines;
        video["sharpFilter"] = r.video->sharpFilter;
        j["video"] = std::move(video);
    }
    return j.dump(2);
}

}  // namespace romm::player

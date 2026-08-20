// protocol.cpp — strict v1 request/result JSON parsing and canonical
// serialization (LINUX_X64.md section 12.2 / 12.3).
//
// Parsing is strict on purpose: this is a security boundary. Unknown
// fields are rejected (the schema must not carry origin/username/token/
// server-save-id/upload-url, and a typo must not smuggle one in), wrong
// types are rejected, and the no-throw nlohmann/json parse overload is
// used so malformed input can never raise out of the validator.
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

    static const std::array<const char*, 13> kFields = {{
        "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
        "corePath",        "contentPath", "contentHash", "systemDir",
        "savePath",        "candidateSavePath", "resultPath",
        "expectedSaveSize", "video",
    }};
    for (const char* key : kFields) {
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

    static const std::array<const char*, 12> kFields = {{
        "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
        "candidateSavePath", "saveHash", "saveSize", "frames",
        "audioUnderrunFrames", "audioOverrunFrames", "errorCode",
        "errorMessage",
    }};
    for (const char* key : kFields) {
        if (!j.contains(key))
            return reject<PlayerResult>(error,
                                        std::string("missing required field: ") +
                                            key);
    }
    checkUnknownFields(j, kFields, err);
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
    return j.dump(2);
}

}  // namespace romm::player

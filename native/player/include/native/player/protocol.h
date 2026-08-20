// protocol.h — versioned launch request / result file protocol for the
// Linux player process (LINUX_X64.md section 12).
//
// The desktop supervisor writes a request file atomically before spawning
// `rommulus-player --request <file>`; the player writes a result file
// atomically before successful exit. Neither file may carry credentials:
// the v1 schema deliberately has no origin, username, token, server save
// ID, or upload URL fields, and the strict parsers below REJECT unknown
// fields so a secret can never ride along in a future schema typo.
//
// Platform-neutral: no SDL, no Android, no JNI. JSON parsing uses the
// vendored header-only nlohmann/json (MIT, third_party/nlohmann/).
#pragma once

#include <cstdint>
#include <optional>
#include <string>

namespace romm::player {

// The only protocol version this binary understands.
constexpr int kProtocolVersion = 1;

struct VideoSettings {
    bool fullscreen = false;
    bool integerScaling = false;
    bool scanlines = false;
    // Sharp filter (nearest-neighbor scaling), mirroring Android's
    // VideoOptionsDialog "Sharp Filter" toggle.
    bool sharpFilter = false;
};

// Launch request version 1 (LINUX_X64.md section 12.2). Every field is
// required; contentHash may be the empty string (hash verification is
// then skipped) and expectedSaveSize may be null.
struct PlayerRequest {
    int protocolVersion = kProtocolVersion;
    std::string sessionId;
    std::string coreId;
    std::string coreBuildRevision;
    std::string corePath;
    std::string contentPath;
    std::string contentHash;  // may be empty
    std::string systemDir;
    std::string savePath;
    std::string candidateSavePath;
    std::string resultPath;
    // 64-bit byte size (like PlayerResult::saveSize): a save file may
    // legitimately exceed INT_MAX, so this is int64_t, not int. The
    // desktop Kotlin side must use Long for this field.
    std::optional<int64_t> expectedSaveSize;
    VideoSettings video;
};

// Result exit kinds (LINUX_X64.md section 12.3). Signals, a missing
// result, malformed JSON, a protocol mismatch, and a nonzero exit are
// classified by the supervisor as crashes and are NOT coerced into one
// of these values.
enum class ExitKind {
    Completed,
    UserCancelledBeforeStart,
    CoreRequestedShutdown,
    LaunchFailed,
    RuntimeFailed,
};

const char* toString(ExitKind kind);
std::optional<ExitKind> exitKindFromString(const std::string& value);

// Result version 1 (LINUX_X64.md section 12.3). saveHash, saveSize,
// errorCode, and errorMessage may be null.
struct PlayerResult {
    int protocolVersion = kProtocolVersion;
    std::string sessionId;
    ExitKind exitKind = ExitKind::Completed;
    bool checkpointWritten = false;
    std::string candidateSavePath;
    std::optional<std::string> saveHash;
    std::optional<int64_t> saveSize;
    int64_t frames = 0;
    int64_t audioUnderrunFrames = 0;
    int64_t audioOverrunFrames = 0;
    std::optional<std::string> errorCode;
    std::optional<std::string> errorMessage;
};

// Strict parsing. Returns std::nullopt (and sets *error, when non-null)
// if:
//   - the text is not valid JSON, or the top level is not an object;
//   - a required field is missing or has the wrong type;
//   - an unknown field is present (v1 must not carry origin/username/
//     token/server-save-id/upload-url — unknown fields are rejected so a
//     secret can never slip through a schema typo);
//   - protocolVersion is not 1;
//   - an integer field is negative where the schema forbids it.
std::optional<PlayerRequest> parseRequest(const std::string& json,
                                          std::string* error = nullptr);
std::optional<PlayerResult> parseResult(const std::string& json,
                                        std::string* error = nullptr);

// Canonical serialization: fixed field order (matching the section 12.2 /
// 12.3 examples), 2-space indent. The output always re-parses to an
// equal struct (round-trip).
std::string serializeRequest(const PlayerRequest& request);
std::string serializeResult(const PlayerResult& result);

}  // namespace romm::player

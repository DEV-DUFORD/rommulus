// test_player_protocol.cpp — v1 request/result schema: strict parsing,
// malformed-JSON fuzzing, missing/unknown/wrong-type fields, protocol
// version rejection, secret-field rejection, and serialize/parse
// round-trips (LINUX_X64.md section 12.2 / 12.3).
#include "native/player/protocol.h"

#include "romm_test.h"

#include <nlohmann/json.hpp>

#include <string>
#include <vector>

using romm::player::ExitKind;
using romm::player::PlayerRequest;
using romm::player::PlayerResult;
using romm::player::exitKindFromString;
using romm::player::parseRequest;
using romm::player::parseResult;
using romm::player::serializeRequest;
using romm::player::serializeResult;
using romm::player::toString;

namespace {

PlayerRequest sampleRequest() {
    PlayerRequest r;
    r.sessionId = "11111111-2222-3333-4444-555555555555";
    r.coreId = "gambatte";
    r.coreBuildRevision = "pinned-sha-abc123";
    r.corePath = "/trusted/install/cores/libgambatte_core.so";
    r.contentPath = "/xdg/cache/rommulus/roms/dragon.gb";
    r.contentHash = "sha256-abcdef";
    r.systemDir = "/xdg/data/rommulus/firmware/gb";
    r.savePath = "/xdg/data/rommulus/saves/dragon/autosave.srm";
    r.candidateSavePath = "/xdg/state/rommulus/journals/dragon/candidate.srm";
    r.resultPath = "/xdg/state/rommulus/journals/dragon/result.json";
    r.expectedSaveSize = 32768;
    r.video.fullscreen = true;
    r.video.integerScaling = false;
    r.video.scanlines = true;
    r.video.sharpFilter = true;
    return r;
}

PlayerResult sampleResult() {
    PlayerResult r;
    r.sessionId = "11111111-2222-3333-4444-555555555555";
    r.exitKind = ExitKind::Completed;
    r.checkpointWritten = true;
    r.candidateSavePath = "/xdg/state/rommulus/journals/dragon/candidate.srm";
    r.saveHash = "sha256-fedcba";
    r.saveSize = 32768;
    r.frames = 12345;
    r.audioUnderrunFrames = 7;
    r.audioOverrunFrames = 0;
    r.errorCode = std::nullopt;
    r.errorMessage = std::nullopt;
    return r;
}

void checkRoundTripRequest(const PlayerRequest& in) {
    std::string json = serializeRequest(in);
    std::string err;
    auto out = parseRequest(json, &err);
    CHECK(out.has_value());
    if (!out) {
        std::fprintf(stderr, "round-trip parse failed: %s\n", err.c_str());
        return;
    }
    CHECK_EQ(out->protocolVersion, in.protocolVersion);
    CHECK_EQ(out->sessionId, in.sessionId);
    CHECK_EQ(out->coreId, in.coreId);
    CHECK_EQ(out->coreBuildRevision, in.coreBuildRevision);
    CHECK_EQ(out->corePath, in.corePath);
    CHECK_EQ(out->contentPath, in.contentPath);
    CHECK_EQ(out->contentHash, in.contentHash);
    CHECK_EQ(out->systemDir, in.systemDir);
    CHECK_EQ(out->savePath, in.savePath);
    CHECK_EQ(out->candidateSavePath, in.candidateSavePath);
    CHECK_EQ(out->resultPath, in.resultPath);
    CHECK(out->expectedSaveSize == in.expectedSaveSize);
    CHECK_EQ(out->video.fullscreen, in.video.fullscreen);
    CHECK_EQ(out->video.integerScaling, in.video.integerScaling);
    CHECK_EQ(out->video.scanlines, in.video.scanlines);
    CHECK_EQ(out->video.sharpFilter, in.video.sharpFilter);
}

void checkRoundTripResult(const PlayerResult& in) {
    std::string json = serializeResult(in);
    std::string err;
    auto out = parseResult(json, &err);
    CHECK(out.has_value());
    if (!out) {
        std::fprintf(stderr, "round-trip parse failed: %s\n", err.c_str());
        return;
    }
    CHECK_EQ(out->protocolVersion, in.protocolVersion);
    CHECK_EQ(out->sessionId, in.sessionId);
    CHECK(out->exitKind == in.exitKind);
    CHECK_EQ(out->checkpointWritten, in.checkpointWritten);
    CHECK_EQ(out->candidateSavePath, in.candidateSavePath);
    CHECK(out->saveHash == in.saveHash);
    CHECK(out->saveSize == in.saveSize);
    CHECK_EQ(out->frames, in.frames);
    CHECK_EQ(out->audioUnderrunFrames, in.audioUnderrunFrames);
    CHECK_EQ(out->audioOverrunFrames, in.audioOverrunFrames);
    CHECK(out->errorCode == in.errorCode);
    CHECK(out->errorMessage == in.errorMessage);
}

void expectRequestRejected(const std::string& json, const char* label) {
    std::string err;
    auto r = parseRequest(json, &err);
    if (r) {
        std::fprintf(stderr, "expected rejection (%s): %s\n", label,
                     json.c_str());
    }
    CHECK(!r);
    CHECK(!err.empty());
}

void expectResultRejected(const std::string& json, const char* label) {
    std::string err;
    auto r = parseResult(json, &err);
    if (r) {
        std::fprintf(stderr, "expected rejection (%s): %s\n", label,
                     json.c_str());
    }
    CHECK(!r);
    CHECK(!err.empty());
}

}  // namespace

int main() {
    using nlohmann::json;

    // --- Round-trips -------------------------------------------------
    checkRoundTripRequest(sampleRequest());

    // Empty contentHash and null expectedSaveSize must survive a
    // round-trip (both are legal in v1).
    PlayerRequest sparse = sampleRequest();
    sparse.contentHash = "";
    sparse.expectedSaveSize = std::nullopt;
    checkRoundTripRequest(sparse);

    checkRoundTripResult(sampleResult());
    PlayerResult failed = sampleResult();
    failed.exitKind = ExitKind::RuntimeFailed;
    failed.checkpointWritten = false;
    failed.saveHash = std::nullopt;
    failed.saveSize = std::nullopt;
    failed.frames = 0;
    failed.errorCode = "CORE_LOAD_FAILED";
    failed.errorMessage = "dlopen: no such file";
    checkRoundTripResult(failed);

    // Every exit kind round-trips through its wire string.
    const ExitKind kinds[] = {ExitKind::Completed,
                              ExitKind::UserCancelledBeforeStart,
                              ExitKind::CoreRequestedShutdown,
                              ExitKind::LaunchFailed,
                              ExitKind::RuntimeFailed};
    for (ExitKind kind : kinds) {
        PlayerResult r = sampleResult();
        r.exitKind = kind;
        checkRoundTripResult(r);
        CHECK(exitKindFromString(toString(kind)) == kind);
    }
    CHECK(!exitKindFromString("exploded").has_value());
    CHECK(!exitKindFromString("COMPLETED").has_value());

    // --- Malformed JSON fuzz (must reject, never crash) --------------
    const std::vector<std::string> malformed = {
        "",
        "{",
        "}",
        "{]",
        "[}",
        "null",
        "true",
        "123",
        "\"hello\"",
        "42\n42",
        "{protocolVersion: 1}",
        "{\"protocolVersion\": }",
        "{\"protocolVersion\": 1,}",
        "{\"a\":}",
        "{\"a\" 1}",
        "{\"protocolVersion\": 1e400}",
        "\x01\x02garbage",
        "{\"protocolVersion\": \x01}",
        std::string(5000, '[') + std::string(5000, ']'),
    };
    for (const auto& text : malformed) {
        expectRequestRejected(text, "malformed request");
        expectResultRejected(text, "malformed result");
    }

    // Top level must be an object.
    expectRequestRejected("[]", "array top level");
    expectRequestRejected("1.5", "number top level");
    expectResultRejected("{}", "empty object result");

    // --- Missing required fields (request) ---------------------------
    json base = json::parse(serializeRequest(sampleRequest()));
    const std::vector<std::string> requestFields = {
        "protocolVersion", "sessionId", "coreId", "coreBuildRevision",
        "corePath",        "contentPath", "contentHash", "systemDir",
        "savePath",        "candidateSavePath", "resultPath",
        "expectedSaveSize", "video"};
    for (const auto& field : requestFields) {
        json mutated = base;
        mutated.erase(field);
        expectRequestRejected(mutated.dump(), ("missing " + field).c_str());
    }

    // --- Missing required fields (result) ----------------------------
    json resultBase = json::parse(serializeResult(sampleResult()));
    const std::vector<std::string> resultFields = {
        "protocolVersion", "sessionId", "exitKind", "checkpointWritten",
        "candidateSavePath", "saveHash", "saveSize", "frames",
        "audioUnderrunFrames", "audioOverrunFrames", "errorCode",
        "errorMessage"};
    for (const auto& field : resultFields) {
        json mutated = resultBase;
        mutated.erase(field);
        expectResultRejected(mutated.dump(), ("missing " + field).c_str());
    }

    // --- Unknown fields must be rejected (no secrets in the schema) --
    const std::vector<std::string> secretFields = {
        "origin", "username", "token", "serverSaveId", "uploadUrl",
        "extra"};
    for (const auto& field : secretFields) {
        json mutated = base;
        mutated[field] = "sneaky";
        expectRequestRejected(mutated.dump(),
                              ("unknown request field " + field).c_str());
        json mutatedResult = resultBase;
        mutatedResult[field] = "sneaky";
        expectResultRejected(mutatedResult.dump(),
                             ("unknown result field " + field).c_str());
    }

    // Unknown video sub-field.
    {
        json mutated = base;
        mutated["video"]["crtFilter"] = true;
        expectRequestRejected(mutated.dump(), "unknown video field");
        json mutatedVideoMissing = base;
        mutatedVideoMissing["video"].erase("scanlines");
        expectRequestRejected(mutatedVideoMissing.dump(),
                              "missing video field");
        json mutatedSharpMissing = base;
        mutatedSharpMissing["video"].erase("sharpFilter");
        expectRequestRejected(mutatedSharpMissing.dump(),
                              "missing sharpFilter video field");
    }

    // --- Wrong types --------------------------------------------------
    {
        json mutated = base;
        mutated["protocolVersion"] = "1";
        expectRequestRejected(mutated.dump(), "protocolVersion as string");
        mutated = base;
        mutated["protocolVersion"] = true;
        expectRequestRejected(mutated.dump(), "protocolVersion as bool");
        mutated = base;
        mutated["protocolVersion"] = 1.5;
        expectRequestRejected(mutated.dump(), "protocolVersion as float");
        mutated = base;
        mutated["sessionId"] = 42;
        expectRequestRejected(mutated.dump(), "sessionId as number");
        mutated = base;
        mutated["video"] = json::array({true, false});
        expectRequestRejected(mutated.dump(), "video as array");
        mutated = base;
        mutated["video"]["fullscreen"] = "yes";
        expectRequestRejected(mutated.dump(), "video bool as string");
        mutated = base;
        mutated["expectedSaveSize"] = "32768";
        expectRequestRejected(mutated.dump(), "expectedSaveSize as string");
        mutated = base;
        mutated["expectedSaveSize"] = -1;
        expectRequestRejected(mutated.dump(), "expectedSaveSize negative");
    }
    {
        json mutated = resultBase;
        mutated["checkpointWritten"] = "yes";
        expectResultRejected(mutated.dump(), "checkpointWritten as string");
        mutated = resultBase;
        mutated["frames"] = -5;
        expectResultRejected(mutated.dump(), "frames negative");
        mutated = resultBase;
        mutated["saveSize"] = -1;
        expectResultRejected(mutated.dump(), "saveSize negative");
        mutated = resultBase;
        mutated["saveHash"] = 7;
        expectResultRejected(mutated.dump(), "saveHash as number");
        mutated = resultBase;
        mutated["exitKind"] = "exploded";
        expectResultRejected(mutated.dump(), "unknown exitKind");
    }

    // --- Unknown protocol versions -----------------------------------
    // 4294967297 (2^32+1) wraps to 1 in a 32-bit read; the parser must
    // compare as int64_t and reject it.
    for (long version : {0L, -1L, 2L, 99L, 4294967297L}) {
        json mutated = base;
        mutated["protocolVersion"] = version;
        expectRequestRejected(mutated.dump(), "unknown request version");
        json mutatedResult = resultBase;
        mutatedResult["protocolVersion"] = version;
        expectResultRejected(mutatedResult.dump(), "unknown result version");
    }

    // --- Explicit v1 acceptance (the only supported version) ---------
    {
        std::string err;
        json v1 = base;
        v1["protocolVersion"] = 1;
        auto r = parseRequest(v1.dump(), &err);
        CHECK(r.has_value());
        if (r) CHECK_EQ(r->protocolVersion, 1);
        json v1Result = resultBase;
        v1Result["protocolVersion"] = 1;
        auto rr = parseResult(v1Result.dump(), &err);
        CHECK(rr.has_value());
        if (rr) CHECK_EQ(rr->protocolVersion, 1);
    }

    // --- expectedSaveSize is a 64-bit byte size ----------------------
    // 2^31 is above INT_MAX: legal, and must round-trip exactly instead
    // of being narrowed to -2147483648.
    {
        PlayerRequest big = sampleRequest();
        big.expectedSaveSize = 2147483648LL;  // 2^31
        checkRoundTripRequest(big);
        std::string err;
        json mutated = base;
        mutated["expectedSaveSize"] = 2147483648LL;
        auto r = parseRequest(mutated.dump(), &err);
        CHECK(r.has_value());
        if (r) CHECK_EQ(*r->expectedSaveSize, 2147483648LL);
    }

    // --- Legal edge values -------------------------------------------
    {
        std::string err;
        auto r = parseRequest(serializeRequest(sparse), &err);
        CHECK(r.has_value());
        CHECK(r->contentHash.empty());
        CHECK(!r->expectedSaveSize.has_value());
    }

    return rommtest::finish("test_player_protocol");
}

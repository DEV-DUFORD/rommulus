// test_player_protocol.cpp — v2 request/result schema: strict parsing,
// malformed-JSON fuzzing, missing/unknown/wrong-type fields, protocol
// version rejection, secret-field rejection, the optional controllerBindings
// field (round-trip + defaults + strict sub-schema), and serialize/parse
// round-trips (LINUX_X64.md section 12.2 / 12.3).
#include "native/player/protocol.h"

#include "romm_test.h"

#include <nlohmann/json.hpp>

#include <string>
#include <vector>

using romm::player::BindingSource;
using romm::player::ControllerBindings;
using romm::player::ExitKind;
using romm::player::KeyboardBindings;
using romm::player::kRetroPadSlotCount;
using romm::player::PadAxis;
using romm::player::PadButton;
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

// A v2 controllerBindings value with ONE device covering all four entry
// shapes (button / axis / axis_direction / unbound), plus one
// "apply to every controller" device with an empty guid/identity.
ControllerBindings sampleControllerBindings() {
    ControllerBindings cb;

    romm::player::ControllerBindingDevice usb;
    usb.guid = "036d04ca010000000000000000000000";  // 32-hex SDL USB GUID
    usb.identity.vendorId = 0x046d;
    usb.identity.productId = 0x01ca;
    usb.identity.descriptor = "vid:046d-pid:01ca";
    usb.table.set(0, BindingSource::ofButton(PadButton::kSouth));       // a
    usb.table.set(1, BindingSource::axisDirection(PadAxis::kLeftX, -1));  // b
    usb.table.set(2, BindingSource::ofButton(PadButton::kWest));        // x
    usb.table.set(3, BindingSource::unbound());                         // y
    usb.table.set(4, BindingSource::ofAxis(PadAxis::kLeftX));          // select
    usb.table.set(5, BindingSource::ofButton(PadButton::kStart));       // start
    usb.table.set(6, BindingSource::axisDirection(PadAxis::kLeftTrigger, 1));  // left_shoulder
    usb.table.set(7, BindingSource::ofButton(PadButton::kRightShoulder));      // right_shoulder
    usb.table.set(8, BindingSource::ofButton(PadButton::kDpadUp));     // dpad_up
    usb.table.set(9, BindingSource::ofButton(PadButton::kDpadDown));   // dpad_down
    usb.table.set(10, BindingSource::axisDirection(PadAxis::kLeftX, -1));      // dpad_left
    usb.table.set(11, BindingSource::ofButton(PadButton::kDpadRight));  // dpad_right
    usb.secondaryTable.set(0, BindingSource::ofButton(PadButton::kNorth));
    cb.devices.push_back(std::move(usb));

    romm::player::ControllerBindingDevice any;
    any.guid = "";                      // empty guid = apply to every controller
    any.identity.vendorId = std::nullopt;
    any.identity.productId = std::nullopt;
    any.identity.descriptor = "";
    cb.devices.push_back(std::move(any));  // default table

    return cb;
}

KeyboardBindings sampleKeyboardBindings() {
    KeyboardBindings bindings;
    bindings.table.setScancode(romm::player::kKeyboardA, 0, 30);
    bindings.table.setScancode(romm::player::kKeyboardA, 1, std::nullopt);
    bindings.table.setScancode(romm::player::kKeyboardLeftXNegative, 0, 100);
    return bindings;
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
    r.video = romm::player::VideoSettings{false, true, true, false};
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
    CHECK(out->rendererOverride == in.rendererOverride);

    // v2 optional field: presence must round-trip, and every device's
    // guid/identity/table must be byte-equal.
    CHECK(out->controllerBindings.has_value() == in.controllerBindings.has_value());
    if (in.controllerBindings.has_value()) {
        const auto& a = in.controllerBindings->devices;
        const auto& b = out->controllerBindings->devices;
        CHECK(a.size() == b.size());
        for (size_t d = 0; d < a.size() && d < b.size(); ++d) {
            CHECK_EQ(a[d].guid, b[d].guid);
            CHECK(a[d].identity.vendorId == b[d].identity.vendorId);
            CHECK(a[d].identity.productId == b[d].identity.productId);
            CHECK_EQ(a[d].identity.descriptor, b[d].identity.descriptor);
            for (int slot = 0; slot < kRetroPadSlotCount; ++slot) {
                CHECK(a[d].table.get(slot) == b[d].table.get(slot));
                CHECK(a[d].secondaryTable.get(slot) == b[d].secondaryTable.get(slot));
            }
        }
        CHECK(out->keyboardBindings.has_value() == in.keyboardBindings.has_value());
        if (in.keyboardBindings.has_value() && out->keyboardBindings.has_value()) {
            for (int target = 0; target < romm::player::kKeyboardTargetCount; ++target) {
                CHECK(in.keyboardBindings->table.get(target) ==
                      out->keyboardBindings->table.get(target));
            }
        }
    }
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
    CHECK(out->video.has_value() == in.video.has_value());
    if (in.video.has_value() && out->video.has_value()) {
        CHECK_EQ(out->video->fullscreen, in.video->fullscreen);
        CHECK_EQ(out->video->integerScaling, in.video->integerScaling);
        CHECK_EQ(out->video->scanlines, in.video->scanlines);
        CHECK_EQ(out->video->sharpFilter, in.video->sharpFilter);
    }
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
    {
        PlayerRequest software = sampleRequest();
        software.rendererOverride = romm::player::RendererOverride::SoftwareHw;
        checkRoundTripRequest(software);
        CHECK(serializeRequest(software).find("\"rendererOverride\": \"software_hw\"") !=
              std::string::npos);
    }

    // Empty contentHash and null expectedSaveSize must survive a
    // round-trip (both are legal in v2).
    PlayerRequest sparse = sampleRequest();
    sparse.contentHash = "";
    sparse.expectedSaveSize = std::nullopt;
    checkRoundTripRequest(sparse);

    // --- v2 controllerBindings field ---------------------------------
    // Round-trip with the optional field present (two devices: a USB pad
    // with a remapped table and an empty-guid "all controllers" entry).
    {
        PlayerRequest withBindings = sampleRequest();
        withBindings.controllerBindings = sampleControllerBindings();
        checkRoundTripRequest(withBindings);

        std::string json = serializeRequest(withBindings);
        CHECK(json.find("\"controllerBindings\"") != std::string::npos);
    }

    // Optional keyboardBindings uses all 24 ordered targets and round-trips.
    {
        PlayerRequest withKeyboard = sampleRequest();
        withKeyboard.keyboardBindings = sampleKeyboardBindings();
        checkRoundTripRequest(withKeyboard);
        const std::string text = serializeRequest(withKeyboard);
        CHECK(text.find("\"keyboardBindings\"") != std::string::npos);

        json baseKeyboard = json::parse(text);
        json mutated = baseKeyboard;
        std::swap(mutated["keyboardBindings"]["bindings"][0],
                  mutated["keyboardBindings"]["bindings"][1]);
        expectRequestRejected(mutated.dump(), "keyboard targets out of order");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"].erase(0);
        expectRequestRejected(mutated.dump(), "only 23 keyboard bindings");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0]["target"] = "unknown";
        expectRequestRejected(mutated.dump(), "unknown keyboard target");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0]["primaryScancode"] = -1;
        expectRequestRejected(mutated.dump(), "negative keyboard scancode");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0]["secondaryScancode"] = 512;
        expectRequestRejected(mutated.dump(), "keyboard scancode above 511");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0]["primaryScancode"] = "40";
        expectRequestRejected(mutated.dump(), "keyboard scancode wrong type");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0].erase("secondaryScancode");
        expectRequestRejected(mutated.dump(), "missing keyboard scancode field");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["bindings"][0]["extra"] = true;
        expectRequestRejected(mutated.dump(), "unknown keyboard binding field");
        mutated = baseKeyboard;
        mutated["keyboardBindings"]["extra"] = true;
        expectRequestRejected(mutated.dump(), "unknown keyboardBindings field");
    }

    // Optional-field default: absent on the wire -> nullopt, and a request
    // without stored bindings must NOT emit the field at all (the player
    // then keeps its built-in defaults).
    {
        const std::string json = serializeRequest(sampleRequest());
        CHECK(json.find("controllerBindings") == std::string::npos);
        std::string err;
        auto r = parseRequest(json, &err);
        CHECK(r.has_value());
        if (r) CHECK(!r->controllerBindings.has_value());
        if (r) CHECK(!r->keyboardBindings.has_value());
    }

    // A present-but-empty devices array is legal and round-trips.
    {
        PlayerRequest emptyDevices = sampleRequest();
        emptyDevices.controllerBindings = ControllerBindings{};
        checkRoundTripRequest(emptyDevices);
    }

    // Strict sub-schema: every mutation below must be rejected.
    auto requestWithBindings = sampleRequest();
    requestWithBindings.controllerBindings = sampleControllerBindings();
    const std::string bindingsJson = serializeRequest(requestWithBindings);
    {
        using nlohmann::json;
        json base = json::parse(bindingsJson);

        // Missing the inner required "devices" array.
        json mutated = base;
        mutated["controllerBindings"].erase("devices");
        expectRequestRejected(mutated.dump(), "missing controllerBindings.devices");

        // Unknown field at each nesting level (no secrets, ever).
        mutated = base;
        mutated["controllerBindings"]["extra"] = true;
        expectRequestRejected(mutated.dump(), "unknown controllerBindings field");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["serial"] = "abc";
        expectRequestRejected(mutated.dump(), "unknown device field");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["identity"]["mac"] = "aa:bb";
        expectRequestRejected(mutated.dump(), "unknown identity field");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][0]["mod"] = true;
        expectRequestRejected(mutated.dump(), "unknown binding entry field");

        // devices must be an array.
        mutated = base;
        mutated["controllerBindings"]["devices"] = json::object({{"guid", ""}});
        expectRequestRejected(mutated.dump(), "devices as object");

        // Wrong types in identity / guid.
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["guid"] = 42;
        expectRequestRejected(mutated.dump(), "guid as number");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["identity"]["vendorId"] = -1;
        expectRequestRejected(mutated.dump(), "negative vendorId");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["identity"]["productId"] = "cafe";
        expectRequestRejected(mutated.dump(), "productId as string");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["identity"].erase("descriptor");
        expectRequestRejected(mutated.dump(), "missing identity descriptor");

        // Binding-entry strictness: every slot must appear exactly once, in
        // order, with the exact field set for its declared type.
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"].erase(3);  // 11 entries
        expectRequestRejected(mutated.dump(), "only 11 binding entries");
        mutated = base;
        std::swap(mutated["controllerBindings"]["devices"][0]["bindings"][0],
                  mutated["controllerBindings"]["devices"][0]["bindings"][1]);  // out of order
        expectRequestRejected(mutated.dump(), "binding slots out of order");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][2] =
            mutated["controllerBindings"]["devices"][0]["bindings"][1];  // duplicate slot
        expectRequestRejected(mutated.dump(), "duplicate binding slot");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][0]["slot"] = "z";
        expectRequestRejected(mutated.dump(), "unknown slot name");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][0]["button"] = "trigger";
        expectRequestRejected(mutated.dump(), "unknown pad button");
        mutated = base;  // slot b is axis_direction left_x -1
        mutated["controllerBindings"]["devices"][0]["bindings"][1]["axis"] = "hat_z";
        expectRequestRejected(mutated.dump(), "unknown pad axis");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][1]["polarity"] = 2;
        expectRequestRejected(mutated.dump(), "polarity out of range");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][1].erase("polarity");
        expectRequestRejected(mutated.dump(), "missing polarity");
        // Type-specific field sets: unbound must not carry button, button
        // must not carry axis/polarity, axis_direction must not carry button.
        mutated = base;  // slot y is unbound
        mutated["controllerBindings"]["devices"][0]["bindings"][3]["button"] = "south";
        expectRequestRejected(mutated.dump(), "unbound carrying button");
        mutated = base;  // slot a is button
        mutated["controllerBindings"]["devices"][0]["bindings"][0]["axis"] = "left_x";
        expectRequestRejected(mutated.dump(), "button carrying axis");
        mutated = base;  // slot b is axis_direction
        mutated["controllerBindings"]["devices"][0]["bindings"][1]["button"] = "south";
        expectRequestRejected(mutated.dump(), "axis_direction carrying button");
        mutated = base;
        mutated["controllerBindings"]["devices"][0]["bindings"][0]["type"] = "hat";
        expectRequestRejected(mutated.dump(), "unknown binding type");
    }

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
    // compare as int64_t and reject it. (v1 requests are ALSO rejected now
    // that the protocol is at v2 — covered by version 1L below.)
    for (long version : {0L, -1L, 1L, 3L, 99L, 4294967297L}) {
        json mutated = base;
        mutated["protocolVersion"] = version;
        expectRequestRejected(mutated.dump(), "unknown request version");
        json mutatedResult = resultBase;
        mutatedResult["protocolVersion"] = version;
        expectResultRejected(mutatedResult.dump(), "unknown result version");
    }

    // --- Explicit v2 acceptance (the only supported version) ---------
    {
        std::string err;
        json v2 = base;
        v2["protocolVersion"] = 2;
        auto r = parseRequest(v2.dump(), &err);
        CHECK(r.has_value());
        if (r) CHECK_EQ(r->protocolVersion, 2);
        json v2Result = resultBase;
        v2Result["protocolVersion"] = 2;
        auto rr = parseResult(v2Result.dump(), &err);
        CHECK(rr.has_value());
        if (rr) CHECK_EQ(rr->protocolVersion, 2);
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

// test_core_library.cpp — CoreLibrary load/unload through a FAKE
// romm::dynamiclib::DynamicLibrary registered via setFactory() (the
// engine's default factory returns nullptr, so a real core .so is never
// loaded here). Covers: no backend registered, open failure, missing
// required symbol (teardown), all-symbols-missing (teardown), API version
// mismatch (teardown), successful load (including optional romm_* symbols
// staying null), double-load rejection, and idempotent unload.
#include "core_library.h"

#include <native/engine/DynamicLibrary.h>

#include "romm_test.h"

#include <atomic>
#include <memory>
#include <optional>
#include <string>
#include <utility>
#include <vector>

using romm::CoreLibrary;

namespace {

// No-op stand-ins with the exact signatures of the required retro_*
// symbols, so the fake library can hand back real, callable addresses.
void fakeSetEnvironment(retro_environment_t) {}
void fakeSetVideoRefresh(retro_video_refresh_t) {}
void fakeSetAudioSample(retro_audio_sample_t) {}
void fakeSetAudioSampleBatch(retro_audio_sample_batch_t) {}
void fakeSetInputPoll(retro_input_poll_t) {}
void fakeSetInputState(retro_input_state_t) {}
void fakeInit() {}
void fakeDeinit() {}
unsigned fakeApiVersion() { return RETRO_API_VERSION; }
unsigned fakeWrongApiVersion() { return RETRO_API_VERSION + 1; }
void fakeGetSystemInfo(struct retro_system_info*) {}
void fakeGetSystemAvInfo(struct retro_system_av_info*) {}
void fakeSetControllerPortDevice(unsigned, unsigned) {}
void fakeReset() {}
void fakeRun() {}
size_t fakeSerializeSize() { return 0; }
bool fakeSerialize(void*, size_t) { return true; }
bool fakeUnserialize(const void*, size_t) { return true; }
bool fakeLoadGame(const struct retro_game_info*) { return true; }
void fakeUnloadGame() {}
unsigned fakeGetRegion() { return 0; }
void* fakeGetMemoryData(unsigned) { return nullptr; }
size_t fakeGetMemorySize(unsigned) { return 0; }

template <typename Fn>
void* fnAddr(Fn fn) {
    return reinterpret_cast<void*>(fn);
}

// Per-test configuration shared by every FakeDynamicLibrary the factory
// creates, plus call counters to verify teardown behavior.
struct FakeConfig {
    bool openSucceeds = true;
    std::string openError = "simulated dlopen failure";
    bool provideSymbols = true;
    std::string missingSymbol;  // when non-empty, this one symbol is absent
    bool wrongApiVersion = false;
    std::atomic<int> openCalls{0};
    std::atomic<int> closeCalls{0};
    std::atomic<int> resolveCalls{0};
};

class FakeDynamicLibrary final : public romm::dynamiclib::DynamicLibrary {
public:
    explicit FakeDynamicLibrary(std::shared_ptr<FakeConfig> config)
        : config_(std::move(config)) {}

    bool open(const std::string& path) override {
        ++config_->openCalls;
        path_ = path;
        opened_ = config_->openSucceeds;
        return opened_;
    }

    std::optional<void*> resolve(const std::string& symbol) override {
        ++config_->resolveCalls;
        if (!opened_ || !config_->provideSymbols) return std::nullopt;
        if (!config_->missingSymbol.empty() && symbol == config_->missingSymbol)
            return std::nullopt;
        if (symbol == "retro_api_version") {
            return config_->wrongApiVersion ? fnAddr(fakeWrongApiVersion)
                                            : fnAddr(fakeApiVersion);
        }
        static const std::vector<std::pair<const char*, void*>> kSymbols = {
            {"retro_set_environment", fnAddr(fakeSetEnvironment)},
            {"retro_set_video_refresh", fnAddr(fakeSetVideoRefresh)},
            {"retro_set_audio_sample", fnAddr(fakeSetAudioSample)},
            {"retro_set_audio_sample_batch", fnAddr(fakeSetAudioSampleBatch)},
            {"retro_set_input_poll", fnAddr(fakeSetInputPoll)},
            {"retro_set_input_state", fnAddr(fakeSetInputState)},
            {"retro_init", fnAddr(fakeInit)},
            {"retro_deinit", fnAddr(fakeDeinit)},
            {"retro_get_system_info", fnAddr(fakeGetSystemInfo)},
            {"retro_get_system_av_info", fnAddr(fakeGetSystemAvInfo)},
            {"retro_set_controller_port_device", fnAddr(fakeSetControllerPortDevice)},
            {"retro_reset", fnAddr(fakeReset)},
            {"retro_run", fnAddr(fakeRun)},
            {"retro_serialize_size", fnAddr(fakeSerializeSize)},
            {"retro_serialize", fnAddr(fakeSerialize)},
            {"retro_unserialize", fnAddr(fakeUnserialize)},
            {"retro_load_game", fnAddr(fakeLoadGame)},
            {"retro_unload_game", fnAddr(fakeUnloadGame)},
            {"retro_get_region", fnAddr(fakeGetRegion)},
            {"retro_get_memory_data", fnAddr(fakeGetMemoryData)},
            {"retro_get_memory_size", fnAddr(fakeGetMemorySize)},
        };
        for (const auto& entry : kSymbols) {
            if (entry.first == symbol) return entry.second;
        }
        return std::nullopt;  // optional romm_* symbols are absent
    }

    void close() override {
        ++config_->closeCalls;
        opened_ = false;
    }

    std::string lastError() const override { return config_->openError; }

private:
    std::shared_ptr<FakeConfig> config_;
    std::string path_;
    bool opened_ = false;
};

void installFactory(std::shared_ptr<FakeConfig> config) {
    romm::dynamiclib::setFactory(
        [config]() -> std::unique_ptr<romm::dynamiclib::DynamicLibrary> {
            return std::make_unique<FakeDynamicLibrary>(config);
        });
}

void testNoFactoryRegistered() {
    romm::dynamiclib::setFactory(nullptr);
    CoreLibrary lib;
    CHECK(!lib.load("/fake/core.so"));
    CHECK(!lib.isLoaded());
    CHECK_EQ(lib.lastError(), std::string("no dynamic library backend registered"));
}

void testOpenFailure() {
    auto config = std::make_shared<FakeConfig>();
    config->openSucceeds = false;
    installFactory(config);

    CoreLibrary lib;
    CHECK(!lib.load("/fake/core.so"));
    CHECK(!lib.isLoaded());
    CHECK_EQ(lib.lastError(),
             std::string("core library load failed: simulated dlopen failure"));
    CHECK_EQ(config->openCalls.load(), 1);
}

void testMissingRequiredSymbolTearsDown() {
    auto config = std::make_shared<FakeConfig>();
    config->missingSymbol = "retro_run";
    installFactory(config);

    CoreLibrary lib;
    CHECK(!lib.load("/fake/core.so"));
    CHECK(!lib.isLoaded());
    CHECK_EQ(lib.lastError(), std::string("missing required symbol: retro_run"));
    CHECK_EQ(config->closeCalls.load(), 1);  // opened library is closed
}

void testAllSymbolsMissingTearsDown() {
    auto config = std::make_shared<FakeConfig>();
    config->provideSymbols = false;
    installFactory(config);

    CoreLibrary lib;
    CHECK(!lib.load("/fake/core.so"));
    CHECK(!lib.isLoaded());
    // Every required symbol fails; the last one resolved is recorded.
    CHECK_EQ(lib.lastError(), std::string("missing required symbol: retro_get_memory_size"));
    CHECK_EQ(config->closeCalls.load(), 1);
}

void testApiVersionMismatchTearsDown() {
    auto config = std::make_shared<FakeConfig>();
    config->wrongApiVersion = true;
    installFactory(config);

    CoreLibrary lib;
    CHECK(!lib.load("/fake/core.so"));
    CHECK(!lib.isLoaded());
    CHECK_EQ(lib.lastError(),
             std::string("core API version mismatch: core=" +
                         std::to_string(RETRO_API_VERSION + 1) +
                         " host=" + std::to_string(RETRO_API_VERSION)));
    CHECK_EQ(config->closeCalls.load(), 1);
}

void testSuccessfulLoadDoubleLoadAndUnload() {
    auto config = std::make_shared<FakeConfig>();
    installFactory(config);

    CoreLibrary lib;
    CHECK(lib.load("/fake/core.so"));
    CHECK(lib.isLoaded());
    CHECK_EQ(lib.lastError(), std::string());
    CHECK_EQ(config->openCalls.load(), 1);
    CHECK_EQ(config->closeCalls.load(), 0);

    // Required symbols resolved to the fake's real addresses.
    CHECK(lib.functions().retro_run != nullptr);
    CHECK(lib.functions().retro_init != nullptr);
    CHECK(lib.functions().retro_api_version != nullptr);
    CHECK_EQ(lib.functions().retro_api_version(), RETRO_API_VERSION);
    lib.functions().retro_init();  // callable no-op
    // Optional app extension symbols are absent from the fake: stay null.
    CHECK(lib.functions().romm_get_save_memory_data == nullptr);
    CHECK(lib.functions().romm_get_save_memory_size == nullptr);
    CHECK(lib.functions().romm_apply_save_memory == nullptr);
    CHECK(lib.functions().romm_restore_save_memory == nullptr);

    // Double-load onto the same instance is rejected without a new backend.
    CHECK(!lib.load("/fake/core.so"));
    CHECK_EQ(config->openCalls.load(), 1);
    CHECK_EQ(lib.lastError(), std::string("CoreLibrary already loaded; call unload() first"));
    CHECK(lib.isLoaded());

    // Unload closes the library and resets the function table; a second
    // unload is a safe no-op.
    lib.unload();
    CHECK(!lib.isLoaded());
    CHECK(lib.functions().retro_run == nullptr);
    CHECK_EQ(config->closeCalls.load(), 1);
    lib.unload();
    CHECK_EQ(config->closeCalls.load(), 1);
}

}  // namespace

int main() {
    testNoFactoryRegistered();
    testOpenFailure();
    testMissingRequiredSymbolTearsDown();
    testAllSymbolsMissingTearsDown();
    testApiVersionMismatchTearsDown();
    testSuccessfulLoadDoubleLoadAndUnload();
    romm::dynamiclib::setFactory(nullptr);
    return rommtest::finish("test_core_library");
}

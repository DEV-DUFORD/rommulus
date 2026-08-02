// core_library.h — dlopen-based loader for one Libretro core shared library.
//
// LIBRETRO_REFACTOR.md section 7.1: "Load only a core ID present in the
// signed local manifest. Resolve every required retro_* symbol and reject
// incomplete libraries. Verify retro_api_version() before initialization.
// ... Unload in the reverse order even after a partial initialization
// failure."
//
// This loader does not know about any specific core; it is generic over any
// shared library that exports the standard Libretro C ABI. Phase 2 only
// loads the app-owned synthetic test_core (see app/src/main/cpp/test_core),
// never a downloaded or third-party binary.
#pragma once

#include "libretro.h"
#include <string>

namespace romm {

// Function pointer types for every retro_* symbol the host requires.
struct CoreFunctions {
    void (*retro_set_environment)(retro_environment_t) = nullptr;
    void (*retro_set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*retro_set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*retro_set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*retro_set_input_poll)(retro_input_poll_t) = nullptr;
    void (*retro_set_input_state)(retro_input_state_t) = nullptr;

    void (*retro_init)() = nullptr;
    void (*retro_deinit)() = nullptr;
    unsigned (*retro_api_version)() = nullptr;
    void (*retro_get_system_info)(struct retro_system_info*) = nullptr;
    void (*retro_get_system_av_info)(struct retro_system_av_info*) = nullptr;
    void (*retro_set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*retro_reset)() = nullptr;
    void (*retro_run)() = nullptr;

    size_t (*retro_serialize_size)() = nullptr;
    bool (*retro_serialize)(void*, size_t) = nullptr;
    bool (*retro_unserialize)(const void*, size_t) = nullptr;

    bool (*retro_load_game)(const struct retro_game_info*) = nullptr;
    void (*retro_unload_game)() = nullptr;
    unsigned (*retro_get_region)() = nullptr;

    void* (*retro_get_memory_data)(unsigned) = nullptr;
    size_t (*retro_get_memory_size)(unsigned) = nullptr;

    // Optional app-owned extension for cores whose durable save is composed
    // of multiple non-contiguous memory regions (for example Sega CD internal
    // BRAM plus backup cartridge BRAM).
    void* (*romm_get_save_memory_data)() = nullptr;
    size_t (*romm_get_save_memory_size)() = nullptr;
    bool (*romm_apply_save_memory)() = nullptr;
};

// Loads one core .so, resolves all required symbols, and verifies the API
// version. On any failure, everything opened so far is closed (RAII) and
// isLoaded() reports false with a human-readable reason from lastError().
class CoreLibrary {
public:
    CoreLibrary() = default;
    ~CoreLibrary();

    CoreLibrary(const CoreLibrary&) = delete;
    CoreLibrary& operator=(const CoreLibrary&) = delete;

    // Attempts to dlopen(path) and resolve every required retro_* symbol.
    // Returns false (and leaves the library unloaded) on any failure.
    bool load(const std::string& path);

    // Unloads the core if loaded. Safe to call multiple times.
    void unload();

    bool isLoaded() const { return handle_ != nullptr; }
    const CoreFunctions& functions() const { return functions_; }
    const std::string& lastError() const { return lastError_; }

private:
    void* handle_ = nullptr;
    CoreFunctions functions_;
    std::string lastError_;
};

}  // namespace romm

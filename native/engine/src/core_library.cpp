#include "core_library.h"

#include <native/engine/DynamicLibrary.h>
#include <native/engine/LogSink.h>

#include <cstdio>
#include <cstdarg>
#include <memory>

#define LOG_TAG "romm_core_library"

namespace romm {

namespace {

// Formats printf-style arguments for the platform-neutral engine log sink
// (LINUX_X64.md section 14, Phase 7 Wave 1).
std::string formatLog(const char* format, ...) {
    va_list args;
    va_start(args, format);
    const int len = std::vsnprintf(nullptr, 0, format, args);
    va_end(args);
    if (len < 0) return std::string();
    std::string message(static_cast<std::size_t>(len), '\0');
    va_start(args, format);
    std::vsnprintf(message.data(), static_cast<std::size_t>(len) + 1, format, args);
    va_end(args);
    return message;
}

#define LOGE(...) \
    romm::log::sink().log(romm::log::Severity::Error, LOG_TAG, formatLog(__VA_ARGS__))

// Resolves one symbol or records a descriptive error and returns false.
template <typename Fn>
bool resolve(romm::dynamiclib::DynamicLibrary& lib, const char* name, Fn& out,
             std::string& error_out) {
    auto sym = lib.resolve(name);
    if (!sym.has_value()) {
        error_out = std::string("missing required symbol: ") + name;
        return false;
    }
    out = reinterpret_cast<Fn>(*sym);
    return true;
}

template <typename Fn>
void resolveOptional(romm::dynamiclib::DynamicLibrary& lib, const char* name, Fn& out) {
    auto sym = lib.resolve(name);
    if (sym.has_value()) {
        out = reinterpret_cast<Fn>(*sym);
    }
}

}  // namespace

CoreLibrary::~CoreLibrary() { unload(); }

bool CoreLibrary::load(const std::string& path) {
    // Reject a double-load onto the same instance; callers must unload() first.
    if (handle_ != nullptr) {
        lastError_ = "CoreLibrary already loaded; call unload() first";
        return false;
    }

    auto lib = romm::dynamiclib::create();
    if (lib == nullptr) {
        lastError_ = "no dynamic library backend registered";
        LOGE("%s", lastError_.c_str());
        return false;
    }

    if (!lib->open(path)) {
        // Capture the error once: on Android lastError() calls dlerror(), which
        // returns-and-clears the pending error, so a second call would be empty.
        auto err = lib->lastError();
        lastError_ = std::string("core library load failed: ") + (err.empty() ? "unknown error" : err);
        LOGE("%s", lastError_.c_str());
        return false;
    }

    CoreFunctions fns;
    bool ok = true;
    ok &= resolve(*lib, "retro_set_environment", fns.retro_set_environment, lastError_);
    ok &= resolve(*lib, "retro_set_video_refresh", fns.retro_set_video_refresh, lastError_);
    ok &= resolve(*lib, "retro_set_audio_sample", fns.retro_set_audio_sample, lastError_);
    ok &= resolve(*lib, "retro_set_audio_sample_batch", fns.retro_set_audio_sample_batch, lastError_);
    ok &= resolve(*lib, "retro_set_input_poll", fns.retro_set_input_poll, lastError_);
    ok &= resolve(*lib, "retro_set_input_state", fns.retro_set_input_state, lastError_);
    ok &= resolve(*lib, "retro_init", fns.retro_init, lastError_);
    ok &= resolve(*lib, "retro_deinit", fns.retro_deinit, lastError_);
    ok &= resolve(*lib, "retro_api_version", fns.retro_api_version, lastError_);
    ok &= resolve(*lib, "retro_get_system_info", fns.retro_get_system_info, lastError_);
    ok &= resolve(*lib, "retro_get_system_av_info", fns.retro_get_system_av_info, lastError_);
    ok &= resolve(*lib, "retro_set_controller_port_device", fns.retro_set_controller_port_device, lastError_);
    ok &= resolve(*lib, "retro_reset", fns.retro_reset, lastError_);
    ok &= resolve(*lib, "retro_run", fns.retro_run, lastError_);
    ok &= resolve(*lib, "retro_serialize_size", fns.retro_serialize_size, lastError_);
    ok &= resolve(*lib, "retro_serialize", fns.retro_serialize, lastError_);
    ok &= resolve(*lib, "retro_unserialize", fns.retro_unserialize, lastError_);
    ok &= resolve(*lib, "retro_load_game", fns.retro_load_game, lastError_);
    ok &= resolve(*lib, "retro_unload_game", fns.retro_unload_game, lastError_);
    ok &= resolve(*lib, "retro_get_region", fns.retro_get_region, lastError_);
    ok &= resolve(*lib, "retro_get_memory_data", fns.retro_get_memory_data, lastError_);
    ok &= resolve(*lib, "retro_get_memory_size", fns.retro_get_memory_size, lastError_);
    resolveOptional(*lib, "romm_get_save_memory_data", fns.romm_get_save_memory_data);
    resolveOptional(*lib, "romm_get_save_memory_size", fns.romm_get_save_memory_size);
    resolveOptional(*lib, "romm_apply_save_memory", fns.romm_apply_save_memory);
    resolveOptional(*lib, "romm_restore_save_memory", fns.romm_restore_save_memory);

    if (!ok) {
        LOGE("rejecting incomplete core library: %s", lastError_.c_str());
        lib->close();
        return false;
    }

    unsigned api_version = fns.retro_api_version();
    if (api_version != RETRO_API_VERSION) {
        lastError_ = "core API version mismatch: core=" + std::to_string(api_version) +
                     " host=" + std::to_string(RETRO_API_VERSION);
        LOGE("%s", lastError_.c_str());
        lib->close();
        return false;
    }

    // The header keeps its void* handle_ member (unchanged in Wave 2); it
    // now holds the DynamicLibrary instance that owns the loaded library.
    handle_ = lib.release();
    functions_ = fns;
    lastError_.clear();
    return true;
}

void CoreLibrary::unload() {
    if (handle_ == nullptr) return;
    auto* lib = static_cast<romm::dynamiclib::DynamicLibrary*>(handle_);
    lib->close();
    delete lib;
    handle_ = nullptr;
    functions_ = CoreFunctions{};
}

}  // namespace romm

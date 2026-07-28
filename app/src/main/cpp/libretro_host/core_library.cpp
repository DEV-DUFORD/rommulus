#include "core_library.h"

#include <dlfcn.h>
#include <android/log.h>

#define LOG_TAG "romm_core_library"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace romm {

namespace {

// Resolves one symbol or records a descriptive error and returns false.
template <typename Fn>
bool resolve(void* handle, const char* name, Fn& out, std::string& error_out) {
    dlerror(); // clear any pending error
    void* sym = dlsym(handle, name);
    const char* err = dlerror();
    if (err != nullptr || sym == nullptr) {
        error_out = std::string("missing required symbol: ") + name;
        return false;
    }
    out = reinterpret_cast<Fn>(sym);
    return true;
}

}  // namespace

CoreLibrary::~CoreLibrary() { unload(); }

bool CoreLibrary::load(const std::string& path) {
    // Reject a double-load onto the same instance; callers must unload() first.
    if (handle_ != nullptr) {
        lastError_ = "CoreLibrary already loaded; call unload() first";
        return false;
    }

    void* handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* err = dlerror();
        lastError_ = std::string("dlopen failed: ") + (err ? err : "unknown error");
        LOGE("%s", lastError_.c_str());
        return false;
    }

    CoreFunctions fns;
    bool ok = true;
    ok &= resolve(handle, "retro_set_environment", fns.retro_set_environment, lastError_);
    ok &= resolve(handle, "retro_set_video_refresh", fns.retro_set_video_refresh, lastError_);
    ok &= resolve(handle, "retro_set_audio_sample", fns.retro_set_audio_sample, lastError_);
    ok &= resolve(handle, "retro_set_audio_sample_batch", fns.retro_set_audio_sample_batch, lastError_);
    ok &= resolve(handle, "retro_set_input_poll", fns.retro_set_input_poll, lastError_);
    ok &= resolve(handle, "retro_set_input_state", fns.retro_set_input_state, lastError_);
    ok &= resolve(handle, "retro_init", fns.retro_init, lastError_);
    ok &= resolve(handle, "retro_deinit", fns.retro_deinit, lastError_);
    ok &= resolve(handle, "retro_api_version", fns.retro_api_version, lastError_);
    ok &= resolve(handle, "retro_get_system_info", fns.retro_get_system_info, lastError_);
    ok &= resolve(handle, "retro_get_system_av_info", fns.retro_get_system_av_info, lastError_);
    ok &= resolve(handle, "retro_set_controller_port_device", fns.retro_set_controller_port_device, lastError_);
    ok &= resolve(handle, "retro_reset", fns.retro_reset, lastError_);
    ok &= resolve(handle, "retro_run", fns.retro_run, lastError_);
    ok &= resolve(handle, "retro_serialize_size", fns.retro_serialize_size, lastError_);
    ok &= resolve(handle, "retro_serialize", fns.retro_serialize, lastError_);
    ok &= resolve(handle, "retro_unserialize", fns.retro_unserialize, lastError_);
    ok &= resolve(handle, "retro_load_game", fns.retro_load_game, lastError_);
    ok &= resolve(handle, "retro_unload_game", fns.retro_unload_game, lastError_);
    ok &= resolve(handle, "retro_get_region", fns.retro_get_region, lastError_);
    ok &= resolve(handle, "retro_get_memory_data", fns.retro_get_memory_data, lastError_);
    ok &= resolve(handle, "retro_get_memory_size", fns.retro_get_memory_size, lastError_);

    if (!ok) {
        LOGE("rejecting incomplete core library: %s", lastError_.c_str());
        dlclose(handle);
        return false;
    }

    unsigned api_version = fns.retro_api_version();
    if (api_version != RETRO_API_VERSION) {
        lastError_ = "core API version mismatch: core=" + std::to_string(api_version) +
                     " host=" + std::to_string(RETRO_API_VERSION);
        LOGE("%s", lastError_.c_str());
        dlclose(handle);
        return false;
    }

    handle_ = handle;
    functions_ = fns;
    lastError_.clear();
    return true;
}

void CoreLibrary::unload() {
    if (handle_ == nullptr) return;
    dlclose(handle_);
    handle_ = nullptr;
    functions_ = CoreFunctions{};
}

}  // namespace romm

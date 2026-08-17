// sdl_dynamic_library.cpp — POSIX dynamic-loader implementation of the
// engine's DynamicLibrary seam.
#include "native/player/sdl_dynamic_library.h"

#include <dlfcn.h>

namespace romm::player {

bool SdlDynamicLibrary::open(const std::string& path) {
    // Clear any stale error from a previous operation before we start, so
    // a failure here is attributed to this open() and not to history.
    dlerror();
    handle_ = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle_ == nullptr) {
        // Capture ONCE: dlerror() returns the pending error and clears it.
        const char* error = dlerror();
        lastError_ = error != nullptr ? error : "unknown dlopen failure";
        return false;
    }
    lastError_.clear();
    return true;
}

std::optional<void*> SdlDynamicLibrary::resolve(const std::string& symbol) {
    if (handle_ == nullptr) {
        return std::nullopt;
    }
    // dlsym may legitimately return nullptr for a missing symbol, and
    // nullptr is also its error indicator — the documented disambiguation
    // is to clear dlerror() first and re-check after the lookup.
    dlerror();
    void* address = dlsym(handle_, symbol.c_str());
    const char* error = dlerror();
    if (address == nullptr && error != nullptr) {
        // Capture ONCE (see class comment); a missing symbol is not fatal
        // to the handle, only to this lookup.
        lastError_ = error;
        return std::nullopt;
    }
    return address;
}

void SdlDynamicLibrary::close() {
    if (handle_ != nullptr) {
        dlclose(handle_);
        handle_ = nullptr;
    }
}

std::string SdlDynamicLibrary::lastError() const {
    // Returns the string captured by the most recent failed operation.
    // Never calls dlerror() here: by the time the caller asks, the pending
    // error has long since been consumed, and re-reading would return an
    // empty or unrelated message.
    return lastError_;
}

}  // namespace romm::player

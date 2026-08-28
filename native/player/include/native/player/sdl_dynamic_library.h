// sdl_dynamic_library.h — POSIX dynamic-loader implementation of the
// engine's romm::dynamiclib::DynamicLibrary seam (Phase 8, LINUX_X64.md
// section 12).
//
// The engine tree never includes <dlfcn.h> (LINUX_X64.md section 14); the
// Linux player registers this implementation through
// romm::dynamiclib::setFactory() at startup, and core_library.cpp opens
// Libretro cores and resolves retro_* symbols through it.
#pragma once

#include <native/engine/DynamicLibrary.h>

namespace romm::player {

// dlopen/dlsym/dlclose wrapper.
//
// lastError() captures dlerror() exactly ONCE into a member: dlerror()
// consumes the pending error string, so a second call (e.g. inside a
// "return dlerror() ? dlerror() : ..." expression) would read the wrong
// (empty) message. The captured string is returned verbatim on every
// subsequent call until the next failed operation replaces it.
class SdlDynamicLibrary final : public romm::dynamiclib::DynamicLibrary {
public:
    bool open(const std::string& path) override;
    std::optional<void*> resolve(const std::string& symbol) override;
    void close() override;
    std::string lastError() const override;

private:
    void* handle_ = nullptr;

    // Captured once per failed operation (see class comment). mutable so
    // lastError() can refresh it from a const method.
    mutable std::string lastError_;
};

}  // namespace romm::player

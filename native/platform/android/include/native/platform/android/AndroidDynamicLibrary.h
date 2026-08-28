// AndroidDynamicLibrary.h — DynamicLibrary implementation backed by the
// POSIX dynamic loader.
//
// LINUX_X64.md sections 11/14, Phase 7 Wave 2: the only platform backend in
// Wave 2. It is registered as the engine's active factory by a static
// initializer in AndroidDynamicLibrary.cpp, so core_library.cpp never
// touches the dynamic-loader header directly.
#pragma once

#include <native/engine/DynamicLibrary.h>

namespace romm::android {

// Opens shared libraries with the POSIX loader (eager symbol resolution,
// local scope) and resolves symbols through it, matching the loader's
// historical behavior exactly — including the last-error text surfaced
// through lastError().
class AndroidDynamicLibrary final : public romm::dynamiclib::DynamicLibrary {
public:
    bool open(const std::string& path) override;
    std::optional<void*> resolve(const std::string& symbol) override;
    void close() override;
    std::string lastError() const override;

private:
    void* handle_ = nullptr;
};

}  // namespace romm::android

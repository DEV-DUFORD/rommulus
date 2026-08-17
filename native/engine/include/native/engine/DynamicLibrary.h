// DynamicLibrary.h — platform-neutral shared-library seam for the RomMulus
// native engine.
//
// LINUX_X64.md sections 11/14, Phase 7 Wave 2: the engine tree
// (native/engine/) must never include platform headers — not even the
// POSIX dynamic-loader header, which does not exist on every target.
// Platform code registers a factory for DynamicLibrary implementations at
// startup (Android: the POSIX dynamic-loader implementation in the platform
// tree, installed by a static initializer; a desktop implementation arrives
// in a later wave), and engine code (core_library.cpp) opens Libretro cores
// and resolves retro_* symbols exclusively through this interface.
#pragma once

#include <functional>
#include <memory>
#include <optional>
#include <string>

namespace romm::dynamiclib {

// One loaded shared library. Implementations must be safe to use from the
// thread that loads cores (the main thread in the current host).
class DynamicLibrary {
public:
    virtual ~DynamicLibrary() = default;

    // Opens the shared library at path, resolving all of its symbols
    // eagerly (RTLD_NOW | RTLD_LOCAL on POSIX). Returns false on failure;
    // the platform error text (the loader's last-error string on POSIX) is
    // then available from lastError().
    virtual bool open(const std::string& path) = 0;

    // Resolves one exported symbol. Returns the symbol's address, or
    // nullopt when the lookup failed or the symbol is missing.
    virtual std::optional<void*> resolve(const std::string& symbol) = 0;

    // Closes the library. Safe to call when not open.
    virtual void close() = 0;

    // The platform's last error string for this handle (the loader's
    // last-error query on POSIX); empty when there is no pending error.
    virtual std::string lastError() const = 0;
};

// Replaces the active factory (takes ownership); pass nullptr to clear it.
// Must complete before the first create() — on Android the platform factory
// is installed by a static initializer at library load time, before
// JNI_OnLoad.
void setFactory(std::function<std::unique_ptr<DynamicLibrary>()> factory);

// Creates a fresh handle through the active factory, or nullptr when no
// platform factory is registered.
std::unique_ptr<DynamicLibrary> create();

}  // namespace romm::dynamiclib

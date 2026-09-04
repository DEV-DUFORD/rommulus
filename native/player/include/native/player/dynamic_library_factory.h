// dynamic_library_factory.h — platform-neutral factory for the player's
// romm::dynamiclib::DynamicLibrary implementation (Phase 2,
// plans/WINDOWS_IMPL.md section 5.1).
//
// main.cpp must not reference any platform loader type or header: it asks
// this factory for a fresh DynamicLibrary and registers it with the engine
// via romm::dynamiclib::setFactory(). The implementation is selected per
// platform at CMake configure time by romm_select_platform_sources():
//
//   POSIX:  native/platform/posix/src/posix_dynamic_library.cpp
//           (dlopen/dlsym/dlclose wrapper, see posix_dynamic_library.h)
//   Win32:  native/platform/windows/src/windows_dynamic_library.cpp
//           (LoadLibraryExW wrapper; lands in a later step and must define
//            the same factory function)
#pragma once

#include <memory>

#include "native/engine/DynamicLibrary.h"

namespace romm::player {

// Creates one fresh platform-selected DynamicLibrary handle. The returned
// object owns no loaded library until open() succeeds; callers register it
// with the engine before any core load.
std::unique_ptr<romm::dynamiclib::DynamicLibrary> createPlatformDynamicLibrary();

}  // namespace romm::player

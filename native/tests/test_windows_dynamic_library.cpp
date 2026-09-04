// test_windows_dynamic_library.cpp — Windows-specific native coverage for
// the Win32 DynamicLibrary seam (Phase 2, plans/WINDOWS_IMPL.md section
// 5.2): factory shape and error semantics of windows_dynamic_library.cpp
// without a real core DLL. Verifies that opening an invalid path fails with
// a non-empty, STABLE lastError() (captured once, not re-queried), that
// resolve before open is nullopt, and that close is safe when not open and
// idempotent. Compiled only on WIN32 (see CMakeLists.txt); the POSIX host
// suite keeps its existing selection untouched.
#include <native/player/dynamic_library_factory.h>
#include <native/player/windows_dynamic_library.h>

#include "romm_test.h"

#include <cstdio>
#include <string>

int main() {
    auto lib = romm::player::createPlatformDynamicLibrary();
    if (lib == nullptr) {
        std::fprintf(stderr, "fatal: createPlatformDynamicLibrary returned null\n");
        return 2;
    }

    // A nonexistent canonical path must fail cleanly with a formatted Win32
    // error — never an empty string, and never a crash.
    CHECK(!lib->open("C:\\rommulus_no_such_core_8f3a2b.dll"));
    const std::string error = lib->lastError();
    CHECK(!error.empty());
    // Capture-once semantics: repeated queries return the same stored text,
    // they do not re-query process-wide OS state.
    CHECK(lib->lastError() == error);

    // Resolve on a closed handle: nullopt, no crash.
    CHECK(!lib->resolve("retro_api_version").has_value());

    // Close is safe when not open, and idempotent.
    lib->close();
    lib->close();

    // The handle can be re-opened after close (and fails cleanly again).
    CHECK(!lib->open("C:\\rommulus_no_such_core_8f3a2b.dll"));
    CHECK(!lib->lastError().empty());

    return rommtest::finish("test_windows_dynamic_library");
}

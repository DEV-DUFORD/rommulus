// windows_dynamic_library.h — Win32 dynamic-loader implementation of the
// engine's romm::dynamiclib::DynamicLibrary seam (Phase 2,
// plans/WINDOWS_IMPL.md section 5.2).
//
// Counterpart to posix_dynamic_library.h: the Windows player registers this
// implementation through romm::dynamiclib::setFactory() at startup — via the
// platform-neutral factory in dynamic_library_factory.h, which
// native/platform/windows/src/windows_dynamic_library.cpp defines for WIN32
// builds — and core_library.cpp opens Libretro cores and resolves retro_*
// symbols through it. The engine tree never includes <windows.h>.
#pragma once

#include <native/engine/DynamicLibrary.h>

namespace romm::player {

// LoadLibraryExW/GetProcAddress/FreeLibrary wrapper with safe DLL search
// (plans/WINDOWS_IMPL.md section 5.2). Two complementary mechanisms keep a
// core's transitive dependencies out of the current working directory:
//   1. Process-wide: SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_
//      DIRS) restricts the default directories to application + system
//      directories. open() REFUSES to load (returns false with a captured
//      error) if this cannot be established — it never degrades to a CWD
//      search scheme.
//   2. Per-load: LoadLibraryExW is called with the documented safe-search
//      combination LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR | LOAD_LIBRARY_SEARCH_
//      DEFAULT_DIRS, so the core's imports resolve against the DLL load dir
//      (the player executable) plus application + System32 — never the CWD.
// Only already-validated canonical core paths (UTF-8, absolute) are accepted;
// the UTF-8 -> UTF-16 boundary conversion is strict
// (native/platform/windows/utf16.h), and invalid input fails before any Win32
// call. open() releases any previously loaded handle before replacing it, so
// a re-open never leaks an HMODULE.
//
// lastError() semantics mirror the POSIX implementation: GetLastError is
// captured IMMEDIATELY after the failing call and formatted exactly ONCE
// (FormatMessageW) into a member; subsequent lastError() calls return that
// stored string until the next failed operation replaces it. Never re-query
// the OS at read time — by then the process-wide error state may be
// clobbered by an unrelated call.
class WindowsDynamicLibrary final : public romm::dynamiclib::DynamicLibrary {
public:
    bool open(const std::string& path) override;
    std::optional<void*> resolve(const std::string& symbol) override;
    void close() override;
    std::string lastError() const override;

private:
    // HMODULE is a typedef of void* on every Windows ABI; stored as void*
    // so this header stays free of <windows.h> (mirrors the POSIX handle).
    void* handle_ = nullptr;

    // Captured once per failed operation (see class comment). mutable so
    // lastError() can refresh it from a const method.
    mutable std::string lastError_;
};

}  // namespace romm::player

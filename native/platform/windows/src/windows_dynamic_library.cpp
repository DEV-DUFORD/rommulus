// windows_dynamic_library.cpp — Win32 dynamic-loader implementation of the
// engine's DynamicLibrary seam (LoadLibraryExW/GetProcAddress/FreeLibrary).
// Phase 2 (plans/WINDOWS_IMPL.md section 5.2): safe DLL search, strict
// UTF-8 -> UTF-16 boundary conversion, immediate GetLastError capture with
// format-once error text, exact export resolution, FreeLibrary teardown.
// Also defines the platform-neutral factory (dynamic_library_factory.h)
// that main.cpp calls; on WIN32 this source is selected by
// romm_select_platform_sources() and provides
// romm::player::createPlatformDynamicLibrary().
#include "native/player/windows_dynamic_library.h"

#include <native/platform/windows/utf16.h>

#define WIN32_LEAN_AND_MEAN
#include <windows.h>

#include <mutex>
#include <string>

namespace romm::player {

std::unique_ptr<romm::dynamiclib::DynamicLibrary> createPlatformDynamicLibrary() {
    return std::make_unique<WindowsDynamicLibrary>();
}

namespace {

// Formats a Win32 error code exactly once (FormatMessageW, system message
// tables), converting the UTF-16 result strictly to UTF-8 for the lastError()
// string. Falls back to a numeric form when the OS cannot supply text
// (unknown code, allocation failure).
std::string winErrorText(DWORD code) {
    wchar_t* message = nullptr;
    const DWORD len = FormatMessageW(
        FORMAT_MESSAGE_ALLOCATE_BUFFER | FORMAT_MESSAGE_FROM_SYSTEM |
            FORMAT_MESSAGE_IGNORE_INSERTS,
        nullptr, code, 0, reinterpret_cast<wchar_t*>(&message), 0, nullptr);
    if (len == 0 || message == nullptr) {
        return "Win32 error " + std::to_string(static_cast<unsigned long>(code));
    }
    const auto wide = romm::win32::utf16ToUtf8(std::u16string(message, message + len));
    LocalFree(message);
    if (!wide) {
        return "Win32 error " + std::to_string(static_cast<unsigned long>(code));
    }
    // FormatMessage leaves trailing CR/LF/whitespace; trim it so the text
    // composes cleanly into log lines.
    const size_t end = wide->find_last_not_of(" \t\r\n");
    return end == std::string::npos ? std::string() : wide->substr(0, end + 1);
}

// Process-wide safe DLL search setup, executed exactly once (plans/
// WINDOWS_IMPL.md section 5.2): restrict the default directories to the
// application directory plus the system directories — never the working
// directory — so that even a load triggered OS-side without our explicit
// per-load flags (e.g. by a core that calls LoadLibrary itself) cannot
// resolve a transitive dependency from the CWD.
//
// Returns an empty string when the safe default is established, or a
// formatted, capture-once description of why it could not be. This is
// FATAL, not a degradation: if the OS refuses to restrict the process-wide
// default directories we cannot guarantee that no DLL in the process ever
// resolves from the CWD, so open() must refuse to load rather than silently
// fall back to a working-directory search scheme. The result (success or the
// captured failure text) is cached for the life of the process.
std::string configureSafeDllSearch() {
    static std::once_flag once;
    static std::string failure = "";
    std::call_once(once, [] {
        if (!SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)) {
            // Capture IMMEDIATELY and format exactly once (see class comment):
            // GetLastError is process-wide and would be clobbered by any later
            // call, so the text is built here, not at read time.
            const DWORD code = GetLastError();
            failure = "failed to establish safe DLL search "
                      "(SetDefaultDllDirectories(LOAD_LIBRARY_SEARCH_DEFAULT_DIRS)): " +
                      winErrorText(code);
        }
    });
    return failure;
}

}  // namespace

bool WindowsDynamicLibrary::open(const std::string& path) {
    // A re-open must never leak the previously loaded module: release any
    // existing handle before we try to replace it (FreeLibrary is a no-op on
    // the null check in close(), so this is safe either way).
    if (handle_ != nullptr) {
        FreeLibrary(static_cast<HMODULE>(handle_));
        handle_ = nullptr;
    }

    // The caller passes an already-validated canonical core path (UTF-8).
    // Convert strictly at the Win32 boundary: invalid UTF-8 fails here,
    // before any OS call — a lossy or partial conversion must never reach
    // LoadLibraryExW.
    const auto widePath = romm::win32::utf8ToUtf16(path);
    if (!widePath) {
        lastError_ = "invalid UTF-8 in core path; cannot convert to a Win32 wide path";
        return false;
    }

    // Establish the process-wide safe DLL search default BEFORE any load.
    // This is fatal (see configureSafeDllSearch): if we cannot guarantee that
    // no transitive dependency resolves from the CWD, refuse to load at all
    // rather than degrade to a working-directory search scheme.
    const std::string searchFailure = configureSafeDllSearch();
    if (!searchFailure.empty()) {
        lastError_ = searchFailure;
        return false;
    }

    // Copy into the Win32 boundary form (see utf16.h): char16_t* is not
    // LPCWSTR.
    const std::wstring wide = romm::win32::toWideString(*widePath);
    // Documented safe-search combination (plans/WINDOWS_IMPL.md section 5.2,
    // LoadLibraryExW): the full validated canonical path resolves the core
    // module itself; these flags govern how its TRANSITIVE dependencies are
    // resolved — the DLL load directory (the player executable's own
    // directory) plus the application and System32 directories. The current
    // working directory is never in that set, so a core's imports can not be
    // hijacked from the CWD. This explicit per-load order complements the
    // process-wide SetDefaultDllDirectories default established above.
    void* module = LoadLibraryExW(wide.c_str(), nullptr,
                                  LOAD_LIBRARY_SEARCH_DLL_LOAD_DIR |
                                      LOAD_LIBRARY_SEARCH_DEFAULT_DIRS);
    if (module == nullptr) {
        // Capture IMMEDIATELY: GetLastError is process-wide and can be
        // clobbered by any intervening call. Format exactly once; lastError()
        // returns this stored string from here on.
        const DWORD code = GetLastError();
        lastError_ = winErrorText(code);
        return false;
    }
    handle_ = module;
    lastError_.clear();
    return true;
}

std::optional<void*> WindowsDynamicLibrary::resolve(const std::string& symbol) {
    if (handle_ == nullptr) {
        // Distinguish "no library is loaded" from "the export is missing": a
        // null handle means open() has not succeeded (or close() ran), so set
        // a stable, self-explanatory error instead of returning nullopt with
        // no reason. No OS call is made — the state is entirely local.
        lastError_ = "resolve(" + symbol + ") called with no library loaded; open() first";
        return std::nullopt;
    }
    // GetProcAddress does not reliably set the last-error code (a missing
    // export may leave whatever value was there), so seed it with the
    // expected failure and read it back — the Win32 counterpart of the
    // POSIX clear-dlerror/re-check disambiguation.
    SetLastError(ERROR_PROC_NOT_FOUND);
    FARPROC proc = GetProcAddress(static_cast<HMODULE>(handle_), symbol.c_str());
    if (proc == nullptr) {
        // Capture once (see class comment); a missing export is not fatal to
        // the handle, only to this lookup.
        lastError_ = winErrorText(GetLastError());
        return std::nullopt;
    }
    return reinterpret_cast<void*>(proc);
}

void WindowsDynamicLibrary::close() {
    if (handle_ != nullptr) {
        FreeLibrary(static_cast<HMODULE>(handle_));
        handle_ = nullptr;
    }
}

std::string WindowsDynamicLibrary::lastError() const {
    // Returns the string captured by the most recent failed operation.
    // Never calls GetLastError()/FormatMessageW here: by the time the caller
    // asks, the process-wide error state may long since be clobbered
    // (mirrors the POSIX implementation's capture-once contract).
    return lastError_;
}

}  // namespace romm::player

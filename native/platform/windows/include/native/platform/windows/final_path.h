// final_path.h — the GetFinalPathNameByHandleW buffer-growth contract,
// extracted as a pure, fakeable helper (Phase 2, plans/WINDOWS_IMPL.md
// section 5.3).
//
// This header is the "fakeable seam" of the handle-based final-path
// resolution: the buffer-growth loop shared by windows_path_security.cpp
// and windows_session_lock.cpp lives here as pure standard C++ over a
// caller-supplied fetch callback, so native/tests can pin the contract on
// EVERY host (POSIX included) with a fake fetch — no Windows headers, no
// OS calls. The fetch callback must honor exactly the documented return
// semantics of GetFinalPathNameByHandleW:
//   - 0               -> hard failure (the caller's GetLastError decides);
//   - written < capacity
//                     -> success: exactly `written` characters were stored,
//                        EXCLUDING the terminating NUL (the buffer held
//                        written + 1);
//   - written >= capacity
//                     -> truncation: `written` is the REQUIRED buffer size,
//                        INCLUDING the terminating NUL. Nothing usable was
//                        stored — building a string of `written` characters
//                        here would read past the end of the buffer.
// (A success can never return written >= capacity: the buffer must hold
// the string PLUS the NUL, so a success returns at most capacity - 1.
// Treating written >= capacity as truncation is therefore exact, not
// merely conservative.)
#pragma once

#include <cstddef>
#include <cstdint>
#include <optional>
#include <string>
#include <vector>

namespace romm::win32 {

// The initial buffer size (characters, including room for the NUL).
constexpr std::uint32_t kFinalPathInitialCapacity = 512;
// The growth cap: Windows' long-path ceiling MAX_LONGPATH (32767)
// characters plus the terminating NUL. A required size beyond this cannot
// name a real path, so the loop fails closed instead of growing unbounded.
constexpr std::uint32_t kFinalPathMaxCapacity = 32768;

// Drives `fetch` through the buffer-growth loop above and returns the
// fetched string (exactly the success length, NUL excluded). nullopt on a
// hard failure or when the required size exceeds kFinalPathMaxCapacity.
// Never constructs a string from a buffer that did not report success —
// the truncation case only grows the buffer, it never reads it.
// `fetch` is forwarded, so an lvalue fake keeps its own call records
// (tests observe the growth sequence through the original object).
template <typename Fetch>
std::optional<std::u16string> fetchFinalPath(Fetch&& fetch) {
    std::vector<wchar_t> buffer(kFinalPathInitialCapacity);
    for (;;) {
        const std::uint32_t capacity = static_cast<std::uint32_t>(buffer.size());
        const std::uint32_t written = fetch(buffer.data(), capacity);
        if (written == 0) return std::nullopt;
        if (written < capacity) {
            // Success: exactly `written` characters, NUL excluded.
            return std::u16string(buffer.begin(), buffer.begin() + written);
        }
        // Truncation: `written` is the required size INCLUDING the NUL —
        // the next buffer must hold at least that many characters. Fail
        // closed beyond the cap; otherwise grow to at least the required
        // size (at least doubling keeps short paths to one extra call).
        if (written > kFinalPathMaxCapacity) return std::nullopt;
        buffer.resize(written > capacity * 2 ? written : capacity * 2);
    }
}

}  // namespace romm::win32

// windows_health_metrics.cpp — Win32 implementation of the player's health
// metrics contract (native/player/include/native/player/health_metrics.h).
//
// Phase 2 (plans/WINDOWS_IMPL.md section 5.1): the GetProcessMemoryInfo
// counterpart of posix_health_metrics.cpp, with the same observable
// contract: a failed OS query yields an unavailable snapshot so the caller
// skips the health line entirely, and on success the peak resident set size
// is reported under maxRssKib — here the process's PEAK WORKING SET in KiB
// (PeakWorkingSetSize is bytes; the POSIX ru_maxrss value is likewise a
// peak-residency figure, so the per-minute health line keeps its meaning).
#include "native/player/health_metrics.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>

#include <climits>
#include <cstdint>

namespace romm::player {

HealthSnapshot processHealth() {
    PROCESS_MEMORY_COUNTERS counters {};
    // GetCurrentProcess() is a pseudo-handle the API accepts directly; there
    // is no real handle to close. GetProcessMemoryInfo lives in Psapi (the
    // player links psapi explicitly).
    if (!GetProcessMemoryInfo(::GetCurrentProcess(), &counters, sizeof(counters))) {
        return HealthSnapshot{};  // query failed: caller skips the health line
    }

    // PeakWorkingSetSize is SIZE_T (64-bit on x86_64) BYTES. Convert to KiB
    // in 64-bit arithmetic — division only, so no overflow is possible at
    // any representable process size.
    const std::uint64_t peakKib = static_cast<std::uint64_t>(counters.PeakWorkingSetSize) / 1024u;

    HealthSnapshot snapshot;
    snapshot.available = true;
    // The contract field is `long`, which is 32-bit on Windows (unlike Linux
    // and macOS, where it is 64-bit): a peak working set above LONG_MAX KiB
    // (~2 TiB) cannot be represented. Saturate instead of casting — an
    // implementation-defined truncation would wrap to negative and corrupt
    // the health line. (No real player comes close to this bound; the
    // saturation keeps the value total and monotonic regardless.)
    snapshot.maxRssKib = peakKib > static_cast<std::uint64_t>(LONG_MAX)
                             ? LONG_MAX
                             : static_cast<long>(peakKib);
    return snapshot;
}

}  // namespace romm::player

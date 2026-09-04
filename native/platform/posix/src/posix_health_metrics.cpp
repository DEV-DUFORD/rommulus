// posix_health_metrics.cpp — POSIX implementation of the player's health
// metrics contract (native/player/include/native/player/health_metrics.h).
//
// Phase 2 step 1 (plans/WINDOWS_IMPL.md section 5.1): moved VERBATIM from
// the per-minute health log in native/player/src/main.cpp —
// getrusage(RUSAGE_SELF) with ru_maxrss reported as-is under the
// max_rss_kib label (kilobytes on Linux, bytes on macOS; the original code
// printed the raw value either way), and a failed query yields an
// unavailable snapshot so the caller skips the health line entirely.
#include "native/player/health_metrics.h"

#include <sys/resource.h>

namespace romm::player {

HealthSnapshot processHealth() {
    struct rusage usage {};
    if (::getrusage(RUSAGE_SELF, &usage) != 0) return HealthSnapshot{};
    HealthSnapshot snapshot;
    snapshot.available = true;
    snapshot.maxRssKib = usage.ru_maxrss;
    return snapshot;
}

}  // namespace romm::player

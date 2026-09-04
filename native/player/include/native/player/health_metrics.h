// health_metrics.h — periodic process health diagnostics for the player's
// stderr log (Phase 2 step 1, plans/WINDOWS_IMPL.md section 5.1).
//
// The main loop logs one health line per minute; only the OS query below is
// platform-specific (POSIX: getrusage(RUSAGE_SELF) and ru_maxrss; Win32
// will report peak working set in a later step). No SDL, no Android, no JNI.
#pragma once

namespace romm::player {

struct HealthSnapshot {
    // False when the OS query failed; callers then skip the health line
    // entirely (matching the original getrusage-failure behavior).
    bool available = false;
    // Peak resident set size as reported by the platform (ru_maxrss on
    // POSIX — kilobytes on Linux, bytes on macOS; printed verbatim under
    // the max_rss_kib label exactly as before this extraction).
    long maxRssKib = 0;
};

HealthSnapshot processHealth();

}  // namespace romm::player

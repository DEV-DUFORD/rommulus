// test_windows_health_metrics.cpp — Windows-specific native coverage for
// the Win32 health-metrics slice (Phase 2, plans/WINDOWS_IMPL.md section
// 5.1): sanity of processHealth() against the live GetProcessMemoryInfo API,
// compiled directly from windows_health_metrics.cpp alongside this test
// (self-contained, like the other WIN32 slice tests — created only on
// WIN32; the POSIX host suite keeps its existing selection untouched).
//
// All arithmetic is 64-bit: PeakWorkingSetSize is SIZE_T bytes on x86_64 and
// must not be squeezed through a 32-bit `long` before comparison.
#include <native/player/health_metrics.h>

#include "romm_test.h"

#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include <psapi.h>

#include <cstdint>

namespace {

// Reads the live counters for this process; CHECK-fails on a broken OS query
// (in which case every subsequent comparison would be meaningless).
PROCESS_MEMORY_COUNTERS readCounters() {
    PROCESS_MEMORY_COUNTERS counters {};
    if (GetProcessMemoryInfo(GetCurrentProcess(), &counters, sizeof(counters)) == 0) {
        std::fprintf(stderr, "fatal: GetProcessMemoryInfo failed (%lu)\n",
                     static_cast<unsigned long>(GetLastError()));
        std::exit(2);
    }
    return counters;
}

}  // namespace

int main() {
    // Baseline BEFORE the call: the peak working set only ever grows, so the
    // baseline peak is a lower bound on what processHealth() can report.
    const PROCESS_MEMORY_COUNTERS before = readCounters();
    const std::uint64_t beforePeakKib = static_cast<std::uint64_t>(before.PeakWorkingSetSize) / 1024u;

    const romm::player::HealthSnapshot snapshot = romm::player::processHealth();

    // The query must succeed for a live process...
    CHECK(snapshot.available);
    // ...and a running process always has a non-zero working set.
    CHECK(snapshot.maxRssKib > 0);
    // Peak is monotonic: never below what we measured moments ago...
    CHECK(static_cast<std::uint64_t>(snapshot.maxRssKib) >= beforePeakKib);
    // ...and never above baseline peak plus generous slack for allocations
    // made between the two queries (1 GiB of headroom is absurd; a sane
    // implementation reports exactly the kernel's peak figure).
    CHECK(static_cast<std::uint64_t>(snapshot.maxRssKib) <= beforePeakKib + 1024u * 1024u);
    // The reported peak must cover the CURRENT working set as measured at the
    // baseline (peak >= current at any instant; the snapshot was taken after).
    // PROCESS_MEMORY_COUNTERS names this field WorkingSetSize (the _EX struct
    // uses CurrentWorkingSetSize — same value, different spelling).
    CHECK(static_cast<std::uint64_t>(snapshot.maxRssKib) * 1024u >=
          static_cast<std::uint64_t>(before.WorkingSetSize));

    return rommtest::finish("test_windows_health_metrics");
}

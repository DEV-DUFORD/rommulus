// romm_test.h — minimal self-contained test harness for the host engine
// unit tests (Phase 7 Wave 9b). No external framework: the CHECK macros
// count checks and record failures to stderr; each test executable's
// main() calls rommtest::finish(), which prints a one-line summary and
// returns the process exit code (nonzero on any failure), which CTest
// reports as a test failure.
#pragma once

#include <cstdio>

namespace rommtest {

inline int& checkCount() {
    static int count = 0;
    return count;
}

inline int& failureCount() {
    static int count = 0;
    return count;
}

inline void recordFailure(const char* expression, const char* file, int line) {
    ++failureCount();
    std::fprintf(stderr, "FAIL %s:%d: %s\n", file, line, expression);
}

// Prints the summary line and returns the process exit code.
inline int finish(const char* testName) {
    if (failureCount() == 0) {
        std::printf("PASS %s (%d checks)\n", testName, checkCount());
        return 0;
    }
    std::printf("FAIL %s (%d of %d checks failed)\n", testName, failureCount(),
                checkCount());
    return 1;
}

}  // namespace rommtest

#define CHECK(cond)                                                     \
    do {                                                                \
        ++rommtest::checkCount();                                       \
        if (!(cond)) rommtest::recordFailure(#cond, __FILE__, __LINE__); \
    } while (0)

#define CHECK_EQ(a, b)                                                      \
    do {                                                                    \
        ++rommtest::checkCount();                                           \
        if (!((a) == (b)))                                                  \
            rommtest::recordFailure(#a " == " #b, __FILE__, __LINE__);      \
    } while (0)

#define CHECK_NE(a, b)                                                      \
    do {                                                                    \
        ++rommtest::checkCount();                                           \
        if ((a) == (b))                                                     \
            rommtest::recordFailure(#a " != " #b, __FILE__, __LINE__);      \
    } while (0)

// test_atomic_file_store.cpp — write-temp/fsync/rename durable writes and
// exact-size reads: round-trip, atomic rename (no .tmp leftover),
// overwrite, size-mismatch rejection, missing files, empty files, failed
// writes leaving the original intact, and multi-byte UTF-8 file names.
#include "atomic_file_store.h"

#include "romm_test.h"

// Cross-platform by construction: the temp-directory, existence/size, and
// cleanup helpers below use only C++17 <filesystem>, so this test is
// source-ready on Win32 (it compiles under the MinGW-w64 UCRT64 toolchain)
// even though it only *runs* once windows_path_security lands — see the
// platform-selection note in native/tests/CMakeLists.txt. On POSIX the same
// <filesystem> calls are byte-exact, so UNIX coverage is unchanged.
#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <string>
#include <vector>

using romm::atomicWriteFile;
using romm::readFileExact;
using romm::readWholeFile;

namespace {

// Creates a fresh, uniquely-named temp directory and returns it as a native
// string. std::filesystem::temp_directory_path() is the portable replacement
// for the hard-coded "/tmp/...XXXXXX" mkdtemp template: on POSIX it resolves
// to $TMPDIR (or /tmp), on Win32 to %TEMP%. The name is ASCII (a steady_clock
// tick plus a process counter) so no wide-path boundary is involved in
// creating the directory itself; the per-test file names — including the
// multi-byte UTF-8 one — are what exercise each platform's path handling.
std::string makeTempDir() {
    namespace fs = std::filesystem;
    static std::atomic<unsigned long> sequence{0};
    const auto ticks = std::chrono::steady_clock::now().time_since_epoch().count();
    const fs::path dir = fs::temp_directory_path() /
        ("romm_engine_test_" + std::to_string(static_cast<long long>(ticks)) + "_" +
         std::to_string(sequence.fetch_add(1)));
    std::error_code ec;
    if (!fs::create_directories(dir, ec) || !fs::is_directory(dir, ec)) {
        std::fprintf(stderr, "fatal: could not create temp dir\n");
        std::exit(2);
    }
    return dir.string();
}

bool fileExists(const std::string& path) {
    std::error_code ec;
    return std::filesystem::exists(path, ec);
}

long fileSize(const std::string& path) {
    std::error_code ec;
    const auto size = std::filesystem::file_size(path, ec);
    if (ec) return -1;  // missing/unreadable: mirror the old stat() == -1 case
    return static_cast<long>(size);
}

std::vector<uint8_t> pattern(size_t size, int seed) {
    std::vector<uint8_t> data(size);
    for (size_t i = 0; i < size; ++i) data[i] = static_cast<uint8_t>(seed + 7 * static_cast<int>(i));
    return data;
}

void testWriteReadRoundTrip(const std::string& dir) {
    const std::string path = dir + "/save.bin";
    const auto data = pattern(256, 1);
    CHECK(atomicWriteFile(path, data.data(), data.size()));
    CHECK(fileExists(path));
    CHECK_EQ(fileSize(path), 256L);
    CHECK(!fileExists(path + ".tmp"));  // rename happened; no temp leftover

    std::vector<uint8_t> back(256, 0);
    CHECK(readFileExact(path, back.data(), back.size()));
    CHECK(back == data);
}

void testSizeMismatchRejection(const std::string& dir) {
    const std::string path = dir + "/sized.bin";
    const auto data = pattern(8, 2);
    CHECK(atomicWriteFile(path, data.data(), data.size()));

    uint8_t buf[16] = {};
    CHECK(!readFileExact(path, buf, 4));   // smaller than the file
    CHECK(!readFileExact(path, buf, 16));  // larger than the file
    CHECK(readFileExact(path, buf, 8));    // exact size still works
    for (int i = 0; i < 8; ++i) CHECK_EQ(buf[i], data[static_cast<size_t>(i)]);
}

void testMissingFile(const std::string& dir) {
    const std::string path = dir + "/does_not_exist.bin";
    uint8_t buf[4] = {};
    CHECK(!readFileExact(path, buf, 4));
    std::vector<uint8_t> whole;
    CHECK(!readWholeFile(path, whole));
}

void testOverwrite(const std::string& dir) {
    const std::string path = dir + "/overwrite.bin";
    const auto v1 = pattern(16, 3);
    const auto v2 = pattern(16, 4);
    CHECK(atomicWriteFile(path, v1.data(), v1.size()));
    CHECK(atomicWriteFile(path, v2.data(), v2.size()));
    std::vector<uint8_t> back(16, 0);
    CHECK(readFileExact(path, back.data(), back.size()));
    CHECK(back == v2);
}

void testFailedWriteLeavesOriginalIntact(const std::string& dir) {
    const std::string path = dir + "/guarded.bin";
    const auto data = pattern(32, 5);
    CHECK(atomicWriteFile(path, data.data(), data.size()));

    // A write into a nonexistent directory must fail and leave both the
    // original file and the directory tree untouched.
    CHECK(!atomicWriteFile(dir + "/no_such_dir/guarded.bin", data.data(), data.size()));
    std::vector<uint8_t> back(32, 0);
    CHECK(readFileExact(path, back.data(), back.size()));
    CHECK(back == data);
    CHECK(!fileExists(dir + "/no_such_dir"));
}

void testEmptyFile(const std::string& dir) {
    const std::string path = dir + "/empty.bin";
    CHECK(atomicWriteFile(path, nullptr, 0));
    CHECK(fileExists(path));
    CHECK_EQ(fileSize(path), 0L);

    std::vector<uint8_t> whole(7, 9);  // pre-dirtied: must be replaced
    CHECK(readWholeFile(path, whole));
    CHECK(whole.empty());

    uint8_t buf[4] = {};
    CHECK(readFileExact(path, buf, 0));
}

void testReadWholeFile(const std::string& dir) {
    const std::string path = dir + "/whole.bin";
    const auto data = pattern(4096, 6);
    CHECK(atomicWriteFile(path, data.data(), data.size()));

    std::vector<uint8_t> whole;
    CHECK(readWholeFile(path, whole));
    CHECK(whole == data);
}

void testUnicodePath(const std::string& dir) {
    // Multi-byte UTF-8 file names ("séve-😀.bin") must round-trip: on POSIX
    // the name passes through as bytes; on Win32 it crosses the strict
    // UTF-8 -> UTF-16 boundary (Phase 2, plans/WINDOWS_IMPL.md section 5.5).
    const std::string path = dir + "/s\xc3\xa9ve-\xF0\x9F\x98\x80.bin";
    const auto data = pattern(64, 7);
    CHECK(atomicWriteFile(path, data.data(), data.size()));
    CHECK(fileExists(path));
    CHECK(!fileExists(path + ".tmp"));  // rename happened; no temp leftover

    std::vector<uint8_t> back(64, 0);
    CHECK(readFileExact(path, back.data(), back.size()));
    CHECK(back == data);

    std::vector<uint8_t> whole;
    CHECK(readWholeFile(path, whole));
    CHECK(whole == data);
}

}  // namespace

int main() {
    const std::string dir = makeTempDir();
    testWriteReadRoundTrip(dir);
    testSizeMismatchRejection(dir);
    testMissingFile(dir);
    testOverwrite(dir);
    testFailedWriteLeavesOriginalIntact(dir);
    testEmptyFile(dir);
    testReadWholeFile(dir);
    testUnicodePath(dir);

    // Best-effort cleanup of the whole temp tree (cross-platform; replaces
    // the former per-file std::remove + directory-remove sequence and also
    // covers the multi-byte UTF-8 file name without spelling it again).
    std::error_code ec;
    std::filesystem::remove_all(dir, ec);

    return rommtest::finish("test_atomic_file_store");
}

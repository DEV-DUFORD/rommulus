// test_atomic_file_store.cpp — write-temp/fsync/rename durable writes and
// exact-size reads: round-trip, atomic rename (no .tmp leftover),
// overwrite, size-mismatch rejection, missing files, empty files, and
// failed writes leaving the original intact.
#include "atomic_file_store.h"

#include "romm_test.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <stdlib.h>  // mkdtemp on Linux/glibc
#include <unistd.h>  // mkdtemp on macOS/BSD
#include <string>
#include <sys/stat.h>
#include <vector>

using romm::atomicWriteFile;
using romm::readFileExact;
using romm::readWholeFile;

namespace {

std::string makeTempDir() {
    char templatePath[] = "/tmp/romm_engine_test_XXXXXX";
    if (mkdtemp(templatePath) == nullptr) {
        std::fprintf(stderr, "fatal: mkdtemp failed\n");
        std::exit(2);
    }
    return templatePath;
}

bool fileExists(const std::string& path) {
    struct stat st {};
    return stat(path.c_str(), &st) == 0;
}

long fileSize(const std::string& path) {
    struct stat st {};
    if (stat(path.c_str(), &st) != 0) return -1;
    return static_cast<long>(st.st_size);
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

    // Best-effort cleanup.
    std::remove((dir + "/save.bin").c_str());
    std::remove((dir + "/sized.bin").c_str());
    std::remove((dir + "/overwrite.bin").c_str());
    std::remove((dir + "/guarded.bin").c_str());
    std::remove((dir + "/empty.bin").c_str());
    std::remove((dir + "/whole.bin").c_str());
    std::remove(dir.c_str());

    return rommtest::finish("test_atomic_file_store");
}

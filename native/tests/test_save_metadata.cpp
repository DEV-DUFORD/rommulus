// test_save_metadata.cpp — SHA-256/size extraction from save-file headers.
// Cross-platform by construction: the temp-file helpers use only C++17
// <filesystem> (the former mkstemp/write/close/unlink sequence was POSIX-
// only and blocked the WIN32 CTest suite), so this test compiles and runs
// on both the POSIX and the MinGW-w64 UCRT64 toolchains with identical
// coverage.
#include "native/player/save_metadata.h"

#include "romm_test.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <string>

namespace {

// A fresh, uniquely-named temp file inside a per-run temp directory
// (std::filesystem::temp_directory_path(): $TMPDIR//tmp on POSIX, %TEMP%
// on Win32). ASCII name — no wide-path boundary in the temp plumbing.
std::string makeTempFile() {
    namespace fs = std::filesystem;
    static std::atomic<unsigned long> sequence{0};
    const auto ticks = std::chrono::steady_clock::now().time_since_epoch().count();
    const fs::path dir = fs::temp_directory_path() /
        ("romm_save_metadata_test_" + std::to_string(static_cast<long long>(ticks)));
    std::error_code ec;
    if (!fs::create_directories(dir, ec)) {
        std::fprintf(stderr, "fatal: could not create temp dir\n");
        std::exit(2);
    }
    return (dir / (std::to_string(sequence.fetch_add(1)) + ".srm")).string();
}

void checkMetadata(const std::string& contents, const std::string& expectedHash) {
    const std::string path = makeTempFile();
    {
        std::ofstream out(path, std::ios::binary | std::ios::trunc);
        if (!out) {
            CHECK(false);
            return;
        }
        out << contents;
        CHECK(static_cast<bool>(out));
    }

    const auto metadata = romm::player::readSaveMetadata(path);
    CHECK(metadata.has_value());
    if (metadata) {
        CHECK_EQ(metadata->size, static_cast<int64_t>(contents.size()));
        CHECK_EQ(metadata->sha256, expectedHash);
    }
    std::error_code ec;
    std::filesystem::remove(path, ec);
}

}  // namespace

int main() {
    checkMetadata(
            "",
            "e3b0c44298fc1c149afbf4c8996fb924"
            "27ae41e4649b934ca495991b7852b855");
    checkMetadata(
            "abc",
            "ba7816bf8f01cfea414140de5dae2223"
            "b00361a396177a9cb410ff61f20015ad");
    // 56 bytes sits exactly on the SHA-256 padding boundary: the finish block needs both a
    // length word and a second block (the >56-byte two-block padding path in finish()).
    checkMetadata(
            std::string(56, 'a'),
            "b35439a4ac6f0948b6d6f9e3c6af0f5f"
            "590ce20f1bde7090ef7970686ec6738a");
    checkMetadata(
            std::string(70'000, 'a'),
            "66915c0872933db504e7578828dd85b7"
            "e74a4e0a061f9756793b89c4151bd4b5");
    CHECK(!romm::player::readSaveMetadata("/definitely/missing/save.srm").has_value());
    return rommtest::finish("test_save_metadata");
}

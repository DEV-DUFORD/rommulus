#include "native/player/save_metadata.h"

#include "romm_test.h"

#include <cstdio>
#include <string>
#include <unistd.h>

namespace {

void checkMetadata(const std::string& contents, const std::string& expectedHash) {
    char path[] = "/tmp/romm-save-metadata-XXXXXX";
    const int fd = mkstemp(path);
    CHECK(fd >= 0);
    if (fd < 0) return;

    const ssize_t written = write(fd, contents.data(), contents.size());
    CHECK_EQ(written, static_cast<ssize_t>(contents.size()));
    CHECK_EQ(close(fd), 0);

    const auto metadata = romm::player::readSaveMetadata(path);
    CHECK(metadata.has_value());
    if (metadata) {
        CHECK_EQ(metadata->size, static_cast<int64_t>(contents.size()));
        CHECK_EQ(metadata->sha256, expectedHash);
    }
    CHECK_EQ(unlink(path), 0);
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

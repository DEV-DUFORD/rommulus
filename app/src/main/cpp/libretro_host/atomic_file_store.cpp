#include "atomic_file_store.h"

#include <android/log.h>
#include <cstdio>
#include <cerrno>
#include <cstring>
#include <unistd.h>
#include <sys/stat.h>

#define LOG_TAG "romm_atomic_file_store"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace romm {

bool atomicWriteFile(const std::string& path, const void* data, size_t size) {
    std::string tmpPath = path + ".tmp";

    FILE* f = fopen(tmpPath.c_str(), "wb");
    if (f == nullptr) {
        LOGE("atomicWriteFile: fopen(%s) failed: %s", tmpPath.c_str(), strerror(errno));
        return false;
    }

    size_t written = fwrite(data, 1, size, f);
    if (written != size) {
        LOGE("atomicWriteFile: fwrite short write (%zu of %zu)", written, size);
        fclose(f);
        remove(tmpPath.c_str());
        return false;
    }

    if (fflush(f) != 0) {
        LOGE("atomicWriteFile: fflush failed: %s", strerror(errno));
        fclose(f);
        remove(tmpPath.c_str());
        return false;
    }

    // fsync the temp file's contents before rename, so a crash between these
    // two steps never leaves a renamed-but-not-durable file.
    if (fsync(fileno(f)) != 0) {
        LOGE("atomicWriteFile: fsync failed: %s", strerror(errno));
        fclose(f);
        remove(tmpPath.c_str());
        return false;
    }

    if (fclose(f) != 0) {
        LOGE("atomicWriteFile: fclose failed: %s", strerror(errno));
        remove(tmpPath.c_str());
        return false;
    }

    if (rename(tmpPath.c_str(), path.c_str()) != 0) {
        LOGE("atomicWriteFile: rename(%s -> %s) failed: %s", tmpPath.c_str(), path.c_str(),
             strerror(errno));
        remove(tmpPath.c_str());
        return false;
    }

    return true;
}

bool readFileExact(const std::string& path, void* data, size_t size) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) {
        return false;  // not an error — "no existing save" is a normal, common case
    }

    struct stat st {};
    if (fstat(fileno(f), &st) != 0 || static_cast<size_t>(st.st_size) != size) {
        LOGE("readFileExact: %s size mismatch (expected %zu, got %lld)", path.c_str(), size,
             static_cast<long long>(st.st_size));
        fclose(f);
        return false;
    }

    size_t readBytes = fread(data, 1, size, f);
    fclose(f);

    if (readBytes != size) {
        LOGE("readFileExact: short read (%zu of %zu) from %s", readBytes, size, path.c_str());
        return false;
    }

    return true;
}

bool readWholeFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (f == nullptr) {
        LOGE("readWholeFile: fopen(%s) failed: %s", path.c_str(), strerror(errno));
        return false;
    }

    struct stat st {};
    if (fstat(fileno(f), &st) != 0 || st.st_size < 0) {
        LOGE("readWholeFile: fstat(%s) failed: %s", path.c_str(), strerror(errno));
        fclose(f);
        return false;
    }

    out.assign(static_cast<size_t>(st.st_size), 0);
    size_t readBytes = out.empty() ? 0 : fread(out.data(), 1, out.size(), f);
    fclose(f);

    if (readBytes != out.size()) {
        LOGE("readWholeFile: short read (%zu of %zu) from %s", readBytes, out.size(), path.c_str());
        out.clear();
        return false;
    }

    return true;
}

}  // namespace romm

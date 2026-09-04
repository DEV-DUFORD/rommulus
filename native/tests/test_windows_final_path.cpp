// test_windows_final_path.cpp — pins the GetFinalPathNameByHandleW
// buffer-growth contract (native/platform/windows/final_path.h) on every
// host through a fake fetch callback with the API's exact return
// semantics, plus — on WIN32 only — a real end-to-end run against the
// live API: a path longer than the initial 512-char buffer must be
// fetched whole (grow, never over-read) when the OS/filesystem permits
// long paths, and SKIPs otherwise (the fake-fetch tests above then carry
// the contract on that host).
#include <native/platform/windows/final_path.h>

#ifdef WIN32
#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#endif

#include "romm_test.h"

#include <cstdint>
#include <string>
#include <vector>

namespace {

using romm::win32::kFinalPathInitialCapacity;
using romm::win32::kFinalPathMaxCapacity;
using romm::win32::fetchFinalPath;

// A fake fetch with GetFinalPathNameByHandleW's exact return semantics:
// for a path of N characters it returns N + 1 (the required size
// INCLUDING the terminating NUL) when the buffer cannot hold it, and N
// (the stored length EXCLUDING the NUL) when it can. Truncated buffers
// are filled with a sentinel so any string built from a truncated call
// (the over-read this test exists to prevent) would carry it. Records
// every capacity offered so the growth sequence is assertable.
struct FakeFetch {
    static constexpr wchar_t kSentinel = static_cast<wchar_t>(0xFFFE);

    std::u16string path;
    std::vector<std::uint32_t> capacities;

    std::uint32_t operator()(wchar_t* buffer, std::uint32_t capacity) {
        capacities.push_back(capacity);
        const std::uint32_t length = static_cast<std::uint32_t>(path.size());
        if (capacity < length + 1) {
            // Truncation: required size INCLUDING the NUL; nothing usable
            // was stored.
            for (std::uint32_t i = 0; i < capacity; ++i) buffer[i] = kSentinel;
            return length + 1;
        }
        for (std::uint32_t i = 0; i < length; ++i) {
            buffer[i] = static_cast<wchar_t>(path[i]);
        }
        buffer[length] = L'\0';
        return length;
    }
};

// Success within the initial buffer: one call, exactly the stored
// characters (NUL excluded), nothing more.
void testSuccessWithinInitialBuffer() {
    FakeFetch fake;
    fake.path = u"C:\\short\\path.txt";
    const auto result = fetchFinalPath(fake);
    CHECK(result.has_value());
    if (result) CHECK(*result == fake.path);
    CHECK_EQ(fake.capacities.size(), 1u);
    if (!fake.capacities.empty()) {
        CHECK_EQ(fake.capacities.front(), kFinalPathInitialCapacity);
    }
}

// Truncation beyond the initial buffer: the helper must grow to at least
// the required size (INCLUDING the NUL), refetch, and return the full
// path — never a string built from the truncated call (no sentinel).
void testTruncationGrowsAndNeverOverReads() {
    FakeFetch fake;
    // 3 ("C:\") + 688 + 9 ("\long.txt") = 700 chars.
    fake.path = u"C:\\" + std::u16string(688, u'x') + u"\\long.txt";
    const auto result = fetchFinalPath(fake);
    CHECK(result.has_value());
    if (result) CHECK(*result == fake.path);
    CHECK(fake.capacities.size() >= 2);
    if (fake.capacities.size() >= 2) {
        CHECK_EQ(fake.capacities[0], kFinalPathInitialCapacity);
        // The grown buffer must hold the required size (700 chars + NUL).
        CHECK(fake.capacities[1] >= 701);
    }
}

// Growth tracks the REQUIRED size, not blind doubling: a 3000-char path
// (required 3001) must be refetched at exactly capacity 3001 — doubling
// to 1024 first would be an extra call the contract does not require.
void testGrowsToRequiredSize() {
    FakeFetch fake;
    // 3 ("C:\") + 2991 + 6 ("\f.txt") = 3000 chars.
    fake.path = u"C:\\" + std::u16string(2991, u'y') + u"\\f.txt";
    const auto result = fetchFinalPath(fake);
    CHECK(result.has_value());
    if (result) CHECK(*result == fake.path);
    CHECK_EQ(fake.capacities.size(), 2u);
    if (fake.capacities.size() == 2) {
        CHECK_EQ(fake.capacities[0], kFinalPathInitialCapacity);
        CHECK_EQ(fake.capacities[1], 3001u);
    }
}

// written == capacity is truncation, not success: a success can never
// return >= capacity (the buffer must hold the string PLUS the NUL), so
// the defensive branch must grow rather than build a string from the
// buffer (which would embed the NUL slot).
void testWrittenEqualToCapacityIsTruncation() {
    struct EqualCapacityFake {
        std::vector<std::uint32_t> capacities;
        std::uint32_t operator()(wchar_t* buffer, std::uint32_t capacity) {
            capacities.push_back(capacity);
            if (capacity == kFinalPathInitialCapacity) {
                for (std::uint32_t i = 0; i < capacity; ++i) {
                    buffer[i] = FakeFetch::kSentinel;
                }
                return kFinalPathInitialCapacity;  // == capacity: truncation
            }
            const std::u16string path(511, u'a');
            for (size_t i = 0; i < path.size(); ++i) {
                buffer[i] = static_cast<wchar_t>(path[i]);
            }
            buffer[path.size()] = L'\0';
            return static_cast<std::uint32_t>(path.size());
        }
    } fake;
    const auto result = fetchFinalPath(fake);
    CHECK(result.has_value());
    if (result) CHECK(*result == std::u16string(511, u'a'));
    CHECK_EQ(fake.capacities.size(), 2u);
}

// A required size beyond the cap (MAX_LONGPATH + NUL) cannot name a real
// path: fail closed, and never offer a buffer beyond the cap.
void testRequiredSizeBeyondCapFailsClosed() {
    struct OverCapFake {
        std::vector<std::uint32_t> capacities;
        std::uint32_t operator()(wchar_t* buffer, std::uint32_t capacity) {
            capacities.push_back(capacity);
            for (std::uint32_t i = 0; i < capacity; ++i) buffer[i] = FakeFetch::kSentinel;
            return 40000;  // always truncated, always beyond the cap
        }
    } fake;
    const auto result = fetchFinalPath(fake);
    CHECK(!result.has_value());
    CHECK(!fake.capacities.empty());
    for (const std::uint32_t capacity : fake.capacities) {
        CHECK(capacity <= kFinalPathMaxCapacity);
    }
}

// A hard failure (0) is nullopt after exactly one call.
void testHardFailure() {
    struct HardFailFake {
        std::vector<std::uint32_t> capacities;
        std::uint32_t operator()(wchar_t*, std::uint32_t capacity) {
            capacities.push_back(capacity);
            return 0;
        }
    } fake;
    const auto result = fetchFinalPath(fake);
    CHECK(!result.has_value());
    CHECK_EQ(fake.capacities.size(), 1u);
}

#ifdef WIN32
// Real end-to-end: a path longer than the initial 512-char buffer,
// fetched through the LIVE GetFinalPathNameByHandleW. The "\\?\" prefix
// makes beyond-MAX_PATH paths reachable from a process that is not
// long-path-aware (this test exe carries no manifest); when the
// OS/filesystem refuses long paths, SKIP — the fake-fetch tests above
// still pin the contract on this host.
void testRealLongPath() {
    wchar_t tempBase[MAX_PATH] {};
    const DWORD tempLen = GetTempPathW(MAX_PATH, tempBase);
    if (tempLen == 0 || tempLen >= MAX_PATH) {
        std::printf("SKIP real long-path test: cannot resolve the temp directory\n");
        return;
    }
    std::wstring base = std::wstring(tempBase) + L"romm-final-path-" +
                        std::to_wstring(static_cast<long long>(GetCurrentProcessId()));
    // Pad a directory component until the final path is well past 512.
    std::wstring dir = base + L"\\d";
    while (dir.size() < 600) dir.push_back(L'x');
    const std::wstring file = dir + L"\\long.txt";
    const std::wstring qdir = L"\\\\?\\" + dir;
    const std::wstring qfile = L"\\\\?\\" + file;
    if (CreateDirectoryW(qdir.c_str(), nullptr) == FALSE &&
        GetLastError() != ERROR_ALREADY_EXISTS) {
        std::printf("SKIP real long-path test: long path not permitted "
                    "(CreateDirectoryW: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }
    const HANDLE write =
        CreateFileW(qfile.c_str(), GENERIC_WRITE, 0, nullptr, CREATE_ALWAYS,
                    FILE_ATTRIBUTE_NORMAL, nullptr);
    if (write == INVALID_HANDLE_VALUE) {
        std::printf("SKIP real long-path test: long path not permitted "
                    "(CreateFileW: %lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }
    DWORD written = 0;
    WriteFile(write, "LONG", 4, &written, nullptr);
    CloseHandle(write);

    const HANDLE handle = CreateFileW(
        qfile.c_str(), GENERIC_READ,
        FILE_SHARE_READ | FILE_SHARE_WRITE | FILE_SHARE_DELETE, nullptr, OPEN_EXISTING, 0,
        nullptr);
    if (handle == INVALID_HANDLE_VALUE) {
        std::printf("SKIP real long-path test: cannot reopen the long path (%lu)\n",
                    static_cast<unsigned long>(GetLastError()));
        return;
    }
    std::vector<std::uint32_t> capacities;
    const auto fetched = fetchFinalPath(
        [&](wchar_t* buffer, std::uint32_t capacity) -> std::uint32_t {
            capacities.push_back(capacity);
            return GetFinalPathNameByHandleW(handle, buffer, capacity, VOLUME_NAME_DOS);
        });
    CloseHandle(handle);

    // The initial 512-char buffer must have been too small: the live API
    // reported a required size and the helper grew before fetching.
    CHECK(fetched.has_value());
    if (fetched) {
        CHECK(capacities.size() >= 2);
        if (!capacities.empty()) CHECK_EQ(capacities.front(), kFinalPathInitialCapacity);
        // The FULL final path came back: \\?\-prefixed, longer than the
        // initial buffer, ending in the file name (nothing dropped,
        // nothing extra).
        CHECK(fetched->size() > kFinalPathInitialCapacity);
        const std::u16string suffix = u"\\long.txt";
        CHECK(fetched->size() >= suffix.size() &&
              fetched->compare(fetched->size() - suffix.size(), suffix.size(), suffix) == 0);
    }
    DeleteFileW(qfile.c_str());
    RemoveDirectoryW(qdir.c_str());
}
#endif

}  // namespace

int main() {
    testSuccessWithinInitialBuffer();
    testTruncationGrowsAndNeverOverReads();
    testGrowsToRequiredSize();
    testWrittenEqualToCapacityIsTruncation();
    testRequiredSizeBeyondCapFailsClosed();
    testHardFailure();
#ifdef WIN32
    testRealLongPath();
#endif
    return rommtest::finish("test_windows_final_path");
}

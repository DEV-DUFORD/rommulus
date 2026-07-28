cmake_minimum_required(VERSION 3.22.1)
project(romm_libretro_host LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Treat warnings seriously in native code that will run in a dedicated,
# crash-isolated process (LIBRETRO_REFACTOR.md section 5).
add_compile_options(-Wall -Wextra)

# ---------------------------------------------------------------------------
# romm_libretro_host: the JNI-facing native library. Phase 2 only implements
# a minimal JNI bridge to prove the NDK/CMake toolchain end-to-end on the
# physical 32-bit device; core loading/environment callbacks land in
# subsequent Phase 2 commits (LIBRETRO_REFACTOR.md section 13, Phase 2).
# ---------------------------------------------------------------------------
add_library(romm_libretro_host SHARED
    libretro_host/jni_bridge.cpp
)

target_include_directories(romm_libretro_host PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

target_link_libraries(romm_libretro_host
    log
)

# ---------------------------------------------------------------------------
# test_core: an app-owned, copyright-safe synthetic Libretro core
# (LIBRETRO_REFACTOR.md section 7.3), added in the next Phase 2 commit once
# the JNI toolchain above is proven on the physical device.
# ---------------------------------------------------------------------------

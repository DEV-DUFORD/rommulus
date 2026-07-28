cmake_minimum_required(VERSION 3.22.1)
project(romm_libretro_host LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Treat warnings seriously in native code that will run in a dedicated,
# crash-isolated process (LIBRETRO_REFACTOR.md section 5). Applied per-target
# below (not via directory-wide add_compile_options) so it does not also
# impose our strictness on vendored third-party sources (third_party/oboe),
# which build with their own upstream-tested warning flags.
set(ROMM_WARNING_FLAGS -Wall -Wextra)

# ---------------------------------------------------------------------------
# Oboe (Apache-2.0): vendored under third_party/oboe/ (include/, src/, its own
# CMakeLists.txt, and LICENSE), pinned to the upstream v1.10.0 tag, commit
# a81bb9f87d4105b84b682685d3bfbb5beca371d1
# (https://github.com/google/oboe/tree/a81bb9f87d4105b84b682685d3bfbb5beca371d1).
# Only include/ and src/ were copied (samples/tests/docs excluded); Oboe's own
# CMakeLists.txt defines the `oboe` static library target we link below.
# ---------------------------------------------------------------------------
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/oboe
                  ${CMAKE_CURRENT_BINARY_DIR}/oboe)

# ---------------------------------------------------------------------------
# romm_libretro_host: the JNI-facing native library. Core loading, environment
# callbacks, the emulation thread, video-to-Surface, and Oboe audio output all
# live here (LIBRETRO_REFACTOR.md section 13, Phase 2).
# ---------------------------------------------------------------------------
add_library(romm_libretro_host SHARED
    libretro_host/jni_bridge.cpp
    libretro_host/core_library.cpp
    libretro_host/environment.cpp
    libretro_host/emulation_session.cpp
    libretro_host/atomic_file_store.cpp
    libretro_host/video_output.cpp
    libretro_host/audio_output.cpp
)

target_include_directories(romm_libretro_host PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

# Marked SYSTEM (independent of Oboe's own PUBLIC include declaration) so
# -Wall -Wextra below only applies to our own code, not to warnings that
# originate from inline definitions in Oboe's public headers.
target_include_directories(romm_libretro_host SYSTEM PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/oboe/include
)

target_link_libraries(romm_libretro_host
    log
    android
    oboe
)

target_compile_options(romm_libretro_host PRIVATE ${ROMM_WARNING_FLAGS})

# ---------------------------------------------------------------------------
# test_core: an app-owned, copyright-safe synthetic Libretro core
# (LIBRETRO_REFACTOR.md section 7.3). Built as its own shared library and
# loaded the same way a real core would be (dlopen + retro_* symbol
# resolution), so the loading path is exercised honestly before any
# third-party core is integrated.
# ---------------------------------------------------------------------------
add_library(test_core SHARED
    test_core/test_core.c
)

target_include_directories(test_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

target_link_libraries(test_core
    log
    m
)

target_compile_options(test_core PRIVATE ${ROMM_WARNING_FLAGS})

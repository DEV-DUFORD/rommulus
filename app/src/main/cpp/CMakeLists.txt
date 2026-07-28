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
# SameBoy (Expat/MIT): vendored under third_party/cores/sameboy/ (Core/,
# libretro/, BootROMs/, LICENSE), pinned to the upstream v1.0.3-libretro tag,
# commit 8230189896a8bb6598574d302ba0ad3658f98ab4
# (https://github.com/LIJI32/SameBoy/tree/v1.0.3-libretro). License review
# recorded in CoreManifest.kt's "sameboy" entry
# (reviewedBy="DEV-DUFORD", reviewedOn="2026-07-28"). See
# third_party/cores/sameboy/VENDORING.md for exactly what was vendored, why,
# and how the checked-in libretro/generated/*_boot.c files were produced.
#
# Source list and preprocessor flags mirror upstream's own
# libretro/Makefile.common (SOURCES_C) and libretro/jni/Android.mk
# (COREFLAGS) exactly, so this build faithfully reproduces the exact same
# core upstream ships for Android, just built through our own CMake/NDK
# pipeline instead of ndk-build.
# ---------------------------------------------------------------------------
set(SAMEBOY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/sameboy)

add_library(sameboy_core SHARED
    ${SAMEBOY_DIR}/Core/gb.c
    ${SAMEBOY_DIR}/Core/sgb.c
    ${SAMEBOY_DIR}/Core/apu.c
    ${SAMEBOY_DIR}/Core/memory.c
    ${SAMEBOY_DIR}/Core/mbc.c
    ${SAMEBOY_DIR}/Core/timing.c
    ${SAMEBOY_DIR}/Core/display.c
    ${SAMEBOY_DIR}/Core/camera.c
    ${SAMEBOY_DIR}/Core/sm83_cpu.c
    ${SAMEBOY_DIR}/Core/joypad.c
    ${SAMEBOY_DIR}/Core/save_state.c
    ${SAMEBOY_DIR}/Core/random.c
    ${SAMEBOY_DIR}/Core/rumble.c
    ${SAMEBOY_DIR}/libretro/generated/agb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/cgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/cgb0_boot.c
    ${SAMEBOY_DIR}/libretro/generated/mgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/dmg_boot.c
    ${SAMEBOY_DIR}/libretro/generated/sgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/sgb2_boot.c
    ${SAMEBOY_DIR}/libretro/libretro.c
)

# Upstream's own libretro/jni/Android.mk builds this core with -std=c99
# specifically; match it exactly rather than relying on this project's own
# (newer) default C_STANDARD.
set_target_properties(sameboy_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
)

target_include_directories(sameboy_core SYSTEM PRIVATE
    ${SAMEBOY_DIR}
)

target_compile_definitions(sameboy_core PRIVATE
    # Matches upstream libretro/Makefile.common exactly (disables the
    # debugger/cheats/rewind/timekeeping subsystems this build never uses —
    # see VENDORING.md for why their sources are still vendored but unused).
    GB_DISABLE_TIMEKEEPING
    GB_DISABLE_REWIND
    GB_DISABLE_DEBUGGER
    GB_DISABLE_CHEATS
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    INLINE=inline
    __LIBRETRO__
    GB_INTERNAL
    GB_VERSION="1.0.3"
    ANDROID
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches how third_party/oboe/ is treated above) and linked with upstream's
# own version script so only the standard retro_* Libretro ABI is exported —
# never SameBoy's internal GB_*/GB-prefixed symbols.
target_link_options(sameboy_core PRIVATE
    "-Wl,--version-script=${SAMEBOY_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(sameboy_core
    log
    m
)

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

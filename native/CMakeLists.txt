cmake_minimum_required(VERSION 3.22.1)
project(romm_libretro_host LANGUAGES C CXX ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# NDK r27 does not enable flexible 16 KiB ELF alignment by default. Apply the
# Android TV requirement to every shared library, including vendored cores.
if(ANDROID)
    add_link_options(
        "-Wl,-z,max-page-size=16384"
        "-Wl,-z,common-page-size=16384"
    )
endif()

# Treat warnings seriously in native code that will run in a dedicated,
# crash-isolated process (LIBRETRO_REFACTOR.md section 5). Applied per-target
# below (not via directory-wide add_compile_options) so it does not also
# impose our strictness on vendored third-party sources (third_party/oboe),
# which build with their own upstream-tested warning flags.
set(ROMM_WARNING_FLAGS -Wall -Wextra)

# Anchor paths for the fragments included below. ROMM_NATIVE_ROOT is this
# directory (native/); ROMM_REPO_ROOT is the repository root (native/'s
# parent); ROMM_APP_CPP_DIR is the legacy Android CMake entry directory
# (app/src/main/cpp). Fragments reference third-party sources through
# ROMM_APP_CPP_DIR with the `../../../../` prefix the former monolith used,
# NOT through normalized ROMM_REPO_ROOT paths: clang records -I/-isystem
# directory strings verbatim in DWARF, and Wave 8's gate is byte-identical
# core .so output, so the compiler-visible path spellings must match the
# monolith's exactly (Wave 9+ may normalize once baselines reset).
get_filename_component(ROMM_NATIVE_ROOT ${CMAKE_CURRENT_LIST_DIR} ABSOLUTE)
get_filename_component(ROMM_REPO_ROOT ${CMAKE_CURRENT_LIST_DIR}/.. ABSOLUTE)
set(ROMM_APP_CPP_DIR ${ROMM_REPO_ROOT}/app/src/main/cpp)

# ROMM_WIN32_SOFTWARE_ONLY is a Windows-player-only boundary; declare and
# validate it here too so passing it to this (Android) build is clearly
# ignored with a warning instead of silently dropped.
include(${ROMM_NATIVE_ROOT}/cmake/romm-windows-software-only.cmake)

# ---------------------------------------------------------------------------
# Oboe (Apache-2.0): vendored under third_party/oboe/ (include/, src/, its own
# CMakeLists.txt, and LICENSE), pinned to the upstream v1.10.0 tag, commit
# a81bb9f87d4105b84b682685d3bfbb5beca371d1
# (https://github.com/google/oboe/tree/a81bb9f87d4105b84b682685d3bfbb5beca371d1).
# Only include/ and src/ were copied (samples/tests/docs excluded); Oboe's own
# CMakeLists.txt defines the `oboe` static library target we link below.
# ---------------------------------------------------------------------------
add_subdirectory(${ROMM_REPO_ROOT}/third_party/oboe
                 ${CMAKE_CURRENT_BINARY_DIR}/oboe)

# ---------------------------------------------------------------------------
# romm_libretro_host: the JNI-facing native library (engine + platform
# adapters + bridges).
# ---------------------------------------------------------------------------
include(${ROMM_NATIVE_ROOT}/cmake/engine.cmake)

# ---------------------------------------------------------------------------
# Vendored Libretro cores: one fragment per core under cmake/cores/, each a
# pure move of the former add_library block from the monolithic
# app/src/main/cpp/CMakeLists.txt (LINUX_X64.md section 7 layout).
# ---------------------------------------------------------------------------
include(${ROMM_NATIVE_ROOT}/cmake/cores/sameboy.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/genesis_plus_gx.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/pcsx_rearmed.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/snes9x.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/fceumm.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mgba.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/stella.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/gambatte.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/beetle_pce_fast.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_ngp.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_wswan.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/handy.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/prosystem.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/test_core.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mupen64plus_next.cmake)

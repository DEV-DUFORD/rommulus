# native/player/CMakeLists.txt — standalone build for rommulus-player, the
# Linux SDL3 player executable (Phase 8, LINUX_X64.md section 12).
#
# Mirrors native/tests/CMakeLists.txt: a standalone host project that
# compiles the platform-neutral engine sources directly into the player
# executable plus the SDL3 platform adapters. It also builds the synthetic
# test_core and all 13 vendored cores (via the cmake/cores/*-linux.cmake
# fragments) so the player can load them, but nothing Android.
#
# SDL3 is found via its installed CMake package (brew on macOS, vendored
# source build on Linux CI):
#   cmake -S native/player -B build/player
#   cmake --build build/player
#   ./build/player/rommulus-player --request <request.json>
cmake_minimum_required(VERSION 3.22.1)
project(rommulus_player LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(ROMM_NATIVE_ROOT ${CMAKE_CURRENT_LIST_DIR}/.. ABSOLUTE)
get_filename_component(ROMM_REPO_ROOT ${ROMM_NATIVE_ROOT}/.. ABSOLUTE)

set(ROMM_ENGINE_SRC ${ROMM_NATIVE_ROOT}/engine/src)
set(ROMM_ENGINE_INCLUDE ${ROMM_NATIVE_ROOT}/engine/include)
set(ROMM_PLAYER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(ROMM_PLAYER_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/include)
set(ROMM_LIBRETRO_INCLUDE ${ROMM_REPO_ROOT}/third_party/libretro)

# Anchor for the vendored Gambatte core fragment (cmake/cores/gambatte-linux.cmake),
# which expects both GAMBATTE_DIR and ROMM_LIBRETRO_INCLUDE defined by the
# including project (the Android build derives them from ROMM_APP_CPP_DIR).
set(GAMBATTE_DIR ${ROMM_REPO_ROOT}/third_party/cores/gambatte)
set(FCEUMM_DIR ${ROMM_REPO_ROOT}/third_party/cores/fceumm)
set(PROSYSTEM_DIR ${ROMM_REPO_ROOT}/third_party/cores/prosystem)
set(HANDY_DIR ${ROMM_REPO_ROOT}/third_party/cores/handy)

# Anchors for the 7 new Linux cores (stella, mednafen_wswan, mednafen_ngp,
# snes9x, mgba, beetle_pce_fast, genesis_plus_gx). Each android fragment
# (cmake/cores/<core>.cmake) sets the corresponding variable; the Linux
# fragment (cmake/cores/<core>-linux.cmake) consumes it.
set(STELLA_DIR ${ROMM_REPO_ROOT}/third_party/cores/stella)
set(MEDNAFEN_WSWAN_DIR ${ROMM_REPO_ROOT}/third_party/cores/mednafen_wswan)
set(MEDNAFEN_NGP_DIR ${ROMM_REPO_ROOT}/third_party/cores/mednafen_ngp)
set(SNES9X_DIR ${ROMM_REPO_ROOT}/third_party/cores/snes9x)
set(MGBA_DIR ${ROMM_REPO_ROOT}/third_party/cores/mgba)
set(BEETLE_PCE_FAST_DIR ${ROMM_REPO_ROOT}/third_party/cores/beetle_pce_fast)
set(GENESIS_PLUS_GX_DIR ${ROMM_REPO_ROOT}/third_party/cores/genesis_plus_gx)
set(PCSX_REARMED_DIR ${ROMM_REPO_ROOT}/third_party/cores/pcsx_rearmed)
set(MUPEN64_DIR ${ROMM_REPO_ROOT}/third_party/cores/mupen64plus_next)

find_package(SDL3 REQUIRED CONFIG)

add_executable(rommulus_player
    # Platform-neutral engine (compiled in; the engine tree carries no
    # platform headers of its own).
    ${ROMM_ENGINE_SRC}/core_library.cpp
    ${ROMM_ENGINE_SRC}/environment.cpp
    ${ROMM_ENGINE_SRC}/emulation_session.cpp
    ${ROMM_ENGINE_SRC}/atomic_file_store.cpp
    ${ROMM_ENGINE_SRC}/log.cpp
    ${ROMM_ENGINE_SRC}/dynamic_library.cpp
    ${ROMM_ENGINE_SRC}/audio_sink.cpp
    ${ROMM_ENGINE_SRC}/video_sink.cpp
    ${ROMM_ENGINE_SRC}/hardware_context.cpp
    ${ROMM_ENGINE_SRC}/pixel_format.cpp
    # Linux player: protocol/validation (Phase 8 Wave 1) plus the SDL3
    # adapters. main.cpp is owned by a separate worker and is referenced
    # here so the source list is complete.
    ${ROMM_PLAYER_SRC}/protocol.cpp
    ${ROMM_PLAYER_SRC}/save_metadata.cpp
    ${ROMM_PLAYER_SRC}/validation.cpp
    ${ROMM_PLAYER_SRC}/sdl_log_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_dynamic_library.cpp
    ${ROMM_PLAYER_SRC}/sdl_audio_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_video_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_input.cpp
    ${ROMM_PLAYER_SRC}/main.cpp
)
# ${ROMM_REPO_ROOT}/third_party is required by protocol.cpp/validation.cpp
# (vendored header-only nlohmann/json, included as <nlohmann/json.hpp>).
target_include_directories(rommulus_player PRIVATE
    ${ROMM_ENGINE_INCLUDE}
    ${ROMM_ENGINE_SRC}
    ${ROMM_PLAYER_INCLUDE}
    ${ROMM_LIBRETRO_INCLUDE}
    ${ROMM_REPO_ROOT}/third_party
)
target_link_libraries(rommulus_player PRIVATE SDL3::SDL3 m dl pthread)
target_compile_options(rommulus_player PRIVATE -Wall -Wextra)

# Synthetic core the player loads (Phase 8): a minimal software-rendering
# Libretro core used for end-to-end player verification.
add_library(test_core SHARED
    ${ROMM_REPO_ROOT}/app/src/main/cpp/test_core/test_core.c
)
target_include_directories(test_core PRIVATE ${ROMM_LIBRETRO_INCLUDE})
target_link_libraries(test_core PRIVATE m)
target_compile_options(test_core PRIVATE -Wall -Wextra)

# ---------------------------------------------------------------------------
# Gambatte (Game Boy / GBC): Linux x86_64 build of the same vendored sources
# the Android build compiles via cmake/cores/gambatte.cmake. The fragment is
# identical except for the dropped Android-only `log` link library.
# ---------------------------------------------------------------------------
include(${ROMM_NATIVE_ROOT}/cmake/cores/gambatte-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/fceumm-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/prosystem-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/handy-linux.cmake)
# 7 new Linux cores: stella, mednafen_wswan, mednafen_ngp, snes9x, mgba,
# beetle_pce_fast, genesis_plus_gx.
include(${ROMM_NATIVE_ROOT}/cmake/cores/stella-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_wswan-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_ngp-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/snes9x-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mgba-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/beetle_pce_fast-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/genesis_plus_gx-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/pcsx_rearmed-linux.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/cores/mupen64plus_next-linux.cmake)

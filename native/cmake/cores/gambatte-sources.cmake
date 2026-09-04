# ---------------------------------------------------------------------------
# gambatte (Game Boy / Game Boy Color) — SHARED compiled source list.
#
# Single source of truth for the exact 46-source set every platform fragment
# compiles, so the Android, Linux, and Windows candidate builds can never
# drift apart:
#   - cores/gambatte.cmake          (Android app build, native/CMakeLists.txt)
#   - cores/gambatte-linux.cmake    (POSIX player build, native/player)
#   - cores/gambatte-windows.cmake  (Win32 candidate build, native/player)
#
# The list mirrors upstream's libretro/jni/Android.mk + Makefile.common:
# 3 C files from libretro/ (gambatte_log, blipper, cc_resampler), 13 C files
# from libretro-common/, and 30 C++ files (libretro/libretro.cpp + the 29
# src/**/*.cpp engine files) — 46 sources total. Network-disabled: upstream's
# net_serial.cpp is NOT vendored at this pin and HAVE_NETWORK is never
# defined, so no network code can enter any build.
#
# Contract: the including project MUST define GAMBATTE_DIR (the vendored core
# tree) before including this fragment. The Android fragment derives it from
# ROMM_APP_CPP_DIR; the Linux/Windows player builds set it in
# native/player/CMakeLists.txt. This fragment defines ROMM_GAMBATTE_SOURCES
# (absolute paths) and creates no targets.
# ---------------------------------------------------------------------------

if(NOT DEFINED GAMBATTE_DIR OR NOT IS_DIRECTORY ${GAMBATTE_DIR})
    message(FATAL_ERROR
        "gambatte-sources.cmake: GAMBATTE_DIR must be set to the vendored core "
        "tree (third_party/cores/gambatte) before including this fragment")
endif()

set(ROMM_GAMBATTE_SOURCES
    # libretro/ — C sources (SOURCES_C from Makefile.common, minus net_serial.cpp)
    ${GAMBATTE_DIR}/libretro/gambatte_log.c
    ${GAMBATTE_DIR}/libretro/blipper.c
    ${GAMBATTE_DIR}/libretro/cc_resampler.c
    # libretro/ — C++ driver (SOURCES_CXX from Makefile.common)
    ${GAMBATTE_DIR}/libretro/libretro.cpp
    # src/ — core C++ emulation engine (SOURCES_CXX from Makefile.common)
    ${GAMBATTE_DIR}/src/bootloader.cpp
    ${GAMBATTE_DIR}/src/cpu.cpp
    ${GAMBATTE_DIR}/src/gambatte-memory.cpp
    ${GAMBATTE_DIR}/src/gambatte.cpp
    ${GAMBATTE_DIR}/src/initstate.cpp
    ${GAMBATTE_DIR}/src/interrupter.cpp
    ${GAMBATTE_DIR}/src/interruptrequester.cpp
    ${GAMBATTE_DIR}/src/mem/cartridge.cpp
    ${GAMBATTE_DIR}/src/mem/cartridge_libretro.cpp
    ${GAMBATTE_DIR}/src/mem/huc3.cpp
    ${GAMBATTE_DIR}/src/mem/memptrs.cpp
    ${GAMBATTE_DIR}/src/mem/rtc.cpp
    ${GAMBATTE_DIR}/src/sound.cpp
    ${GAMBATTE_DIR}/src/sound/channel1.cpp
    ${GAMBATTE_DIR}/src/sound/channel2.cpp
    ${GAMBATTE_DIR}/src/sound/channel3.cpp
    ${GAMBATTE_DIR}/src/sound/channel4.cpp
    ${GAMBATTE_DIR}/src/sound/duty_unit.cpp
    ${GAMBATTE_DIR}/src/sound/envelope_unit.cpp
    ${GAMBATTE_DIR}/src/sound/length_counter.cpp
    ${GAMBATTE_DIR}/src/statesaver.cpp
    ${GAMBATTE_DIR}/src/tima.cpp
    ${GAMBATTE_DIR}/src/video.cpp
    ${GAMBATTE_DIR}/src/video_libretro.cpp
    ${GAMBATTE_DIR}/src/video/ly_counter.cpp
    ${GAMBATTE_DIR}/src/video/lyc_irq.cpp
    ${GAMBATTE_DIR}/src/video/next_m0_time.cpp
    ${GAMBATTE_DIR}/src/video/ppu.cpp
    ${GAMBATTE_DIR}/src/video/sprite_mapper.cpp
    # libretro-common/ — C helper utilities (13 files)
    ${GAMBATTE_DIR}/libretro-common/compat/compat_posix_string.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_snprintf.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_strcasestr.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_strl.c
    ${GAMBATTE_DIR}/libretro-common/compat/fopen_utf8.c
    ${GAMBATTE_DIR}/libretro-common/encodings/encoding_utf.c
    ${GAMBATTE_DIR}/libretro-common/file/file_path.c
    ${GAMBATTE_DIR}/libretro-common/file/file_path_io.c
    ${GAMBATTE_DIR}/libretro-common/streams/file_stream.c
    ${GAMBATTE_DIR}/libretro-common/streams/file_stream_transforms.c
    ${GAMBATTE_DIR}/libretro-common/string/stdstring.c
    ${GAMBATTE_DIR}/libretro-common/time/rtime.c
    ${GAMBATTE_DIR}/libretro-common/vfs/vfs_implementation.c
)

# Mechanical guards: the list must stay exactly the curated 46-source set and
# every entry must exist in the vendored tree. A drifted or deleted source
# fails configure loudly instead of silently changing what gets compiled.
list(LENGTH ROMM_GAMBATTE_SOURCES ROMM_GAMBATTE_SOURCE_COUNT)
if(NOT ROMM_GAMBATTE_SOURCE_COUNT EQUAL 46)
    message(FATAL_ERROR
        "gambatte-sources.cmake: expected exactly 46 sources, found "
        "${ROMM_GAMBATTE_SOURCE_COUNT} — the shared source list drifted")
endif()
foreach(_romm_gambatte_src IN LISTS ROMM_GAMBATTE_SOURCES)
    if(NOT EXISTS ${_romm_gambatte_src})
        message(FATAL_ERROR
            "gambatte-sources.cmake: missing vendored source: ${_romm_gambatte_src}")
    endif()
endforeach()

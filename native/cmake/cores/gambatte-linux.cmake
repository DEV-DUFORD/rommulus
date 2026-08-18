# ---------------------------------------------------------------------------
# gambatte (Game Boy / Game Boy Color) — Linux x86_64 variant of
# cores/gambatte.cmake, for the standalone player build (native/player).
# Mirrors the Android fragment exactly, with two differences:
#   - Path anchors are provided by the including CMakeLists.txt rather than
#     derived from ROMM_APP_CPP_DIR: GAMBATTE_DIR (the vendored core tree)
#     and ROMM_LIBRETRO_INCLUDE (third_party/libretro).
#   - The Android-only `log` link library is dropped; `m` is kept.
# Vendored under third_party/cores/gambatte/, pinned to upstream commit
# 9617436 (libretro/gambatte-libretro master HEAD). See
# third_party/cores/gambatte/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (network code, CI, docs, intl scripts).
#
# Source list mirrors upstream's libretro/jni/Android.mk + Makefile.common exactly:
# 3 C files from libretro/ (gambatte_log, blipper, cc_resampler), 15 C files from
# libretro-common/, and 27 C++ files (libretro/libretro.cpp + src/**/*.cpp).
# Mixed C/C++ target; -Wno-c++11-narrowing applies to C++ sources.
# ---------------------------------------------------------------------------

add_library(gambatte_core SHARED
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
    # libretro-common/ — C helper utilities (15 files)
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

set_target_properties(gambatte_core PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(gambatte_core SYSTEM PRIVATE
    ${GAMBATTE_DIR}/src
    ${GAMBATTE_DIR}/include
    ${GAMBATTE_DIR}/common
    ${GAMBATTE_DIR}/libretro
    ${GAMBATTE_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(gambatte_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    INLINE=inline
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    __LIBRETRO__
    VIDEO_RGB565
    CC_RESAMPLER_NO_HIGHPASS
)

# Vendored third-party source: not held to this project's own -Wall -Wextra.
# -Wno-c++11-narrowing suppresses narrowing-conversion warnings in C++ sources;
# harmless on C compilations. -O2 -DNDEBUG for release-quality bytecode.
target_compile_options(gambatte_core PRIVATE
    -Wno-c++11-narrowing
    -O2
    -DNDEBUG
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never gambatte's internal symbols.
target_link_options(gambatte_core PRIVATE
    "-Wl,--version-script=${GAMBATTE_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(gambatte_core
    m
)

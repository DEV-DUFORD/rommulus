# ---------------------------------------------------------------------------
# gambatte (Game Boy / Game Boy Color): vendored under third_party/cores/gambatte/,
# pinned to upstream commit 9617436 (libretro/gambatte-libretro master HEAD).
# See third_party/cores/gambatte/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (network code, CI, docs, intl scripts).
#
# Compiled source list: the shared fragment cmake/cores/gambatte-sources.cmake —
# the single source of truth consumed by the Android, Linux, and Windows
# candidate fragments so no platform can drift. 46 sources exactly: 3 C files
# from libretro/ (gambatte_log, blipper, cc_resampler), 13 C files from
# libretro-common/, and 30 C++ files (libretro/libretro.cpp + src/**/*.cpp).
# Mixed C/C++ target; -Wno-c++11-narrowing applies to C++ sources.
# ---------------------------------------------------------------------------
set(GAMBATTE_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/gambatte)

include(${CMAKE_CURRENT_LIST_DIR}/gambatte-sources.cmake)

add_library(gambatte_core SHARED
    # The exact curated 46-source set from the shared fragment (network-
    # disabled: net_serial.cpp is not vendored and HAVE_NETWORK is off).
    ${ROMM_GAMBATTE_SOURCES}
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
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
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
    log
    m
)

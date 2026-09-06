# ---------------------------------------------------------------------------
# handy (Atari Lynx, "Handy"): vendored under third_party/cores/handy/,
# pinned to upstream commit bc55d46 (libretro/libretro-handy master HEAD; the
# repo has no release tags). BIOS-free HLE (lynxboot.img only used if found).
# Mixed C/C++ target (9 C++ lynx/ units + 2 blip/ C++ units + libretro.cpp,
# plus 13 libretro-common/ C units) — the project builds dynamic, so the
# STATIC_LINKING-gated C sources ARE required.
# ---------------------------------------------------------------------------

set(ROMM_HANDY_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/handy.cmake)
unset(ROMM_HANDY_SOURCES_ONLY)
add_library(handy_core SHARED ${ROMM_HANDY_SOURCES})

# Upstream's own Makefile.common builds with -std=gnu++11; match it exactly.
set_target_properties(handy_core PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(handy_core SYSTEM PRIVATE
    ${HANDY_DIR}/lynx
    ${HANDY_DIR}/libretro
    ${HANDY_DIR}
    ${HANDY_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(handy_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    __LIBRETRO__
    HAVE_STRINGS_H
    HAVE_STDINT_H
    WANT_CRC32
    GIT_VERSION=\"bc55d46\"
    FRONTEND_SUPPORTS_RGB565
    # INLINE omitted: handy's compiled units (lynx/, blip/, libretro.cpp)
    # contain no INLINE macros; only libretro_core_options.h uses retro_inline's
    # self-defined INLINE, which needs no -D.
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches all prior core targets). Linked with upstream's own version script
# so only the Libretro ABI and the three save-memory extensions are exported.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(handy_core PRIVATE
    "-Wl,--version-script=${HANDY_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(handy_core
    m
)

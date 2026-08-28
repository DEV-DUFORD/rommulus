# ---------------------------------------------------------------------------
# handy (Atari Lynx, "Handy"): vendored under third_party/cores/handy/,
# pinned to upstream commit bc55d46 (libretro/libretro-handy master HEAD; the
# repo has no release tags). BIOS-free HLE (lynxboot.img only used if found).
# Mixed C/C++ target (9 C++ lynx/ units + 2 blip/ C++ units + libretro.cpp,
# plus 13 libretro-common/ C units) — the project builds dynamic, so the
# STATIC_LINKING-gated C sources ARE required.
# ---------------------------------------------------------------------------
set(HANDY_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/handy)

add_library(handy_core SHARED
    # libretro/ — Libretro driver + options
    ${HANDY_DIR}/libretro/libretro.cpp
    # lynx/ — core emulation (SOURCES_CXX from Makefile.common)
    ${HANDY_DIR}/lynx/lynxdec.cpp
    ${HANDY_DIR}/lynx/cart.cpp
    ${HANDY_DIR}/lynx/memmap.cpp
    ${HANDY_DIR}/lynx/mikie.cpp
    ${HANDY_DIR}/lynx/ram.cpp
    ${HANDY_DIR}/lynx/rom.cpp
    ${HANDY_DIR}/lynx/susie.cpp
    ${HANDY_DIR}/lynx/system.cpp
    ${HANDY_DIR}/lynx/eeprom.cpp
    # blip/ — audio resampling (SOURCES_CXX from Makefile.common)
    ${HANDY_DIR}/blip/Blip_Buffer.cpp
    ${HANDY_DIR}/blip/Stereo_Buffer.cpp
    # libretro-common/ — C helper utilities (SOURCES_C from Makefile.common)
    ${HANDY_DIR}/libretro-common/compat/compat_posix_string.c
    ${HANDY_DIR}/libretro-common/compat/compat_snprintf.c
    ${HANDY_DIR}/libretro-common/compat/compat_strcasestr.c
    ${HANDY_DIR}/libretro-common/compat/compat_strl.c
    ${HANDY_DIR}/libretro-common/compat/fopen_utf8.c
    ${HANDY_DIR}/libretro-common/encodings/encoding_utf.c
    ${HANDY_DIR}/libretro-common/file/file_path.c
    ${HANDY_DIR}/libretro-common/file/file_path_io.c
    ${HANDY_DIR}/libretro-common/streams/file_stream.c
    ${HANDY_DIR}/libretro-common/streams/file_stream_transforms.c
    ${HANDY_DIR}/libretro-common/string/stdstring.c
    ${HANDY_DIR}/libretro-common/time/rtime.c
    ${HANDY_DIR}/libretro-common/vfs/vfs_implementation.c
)

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
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
)

target_compile_definitions(handy_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    ANDROID
    __LIBRETRO__
    HAVE_STRINGS_H
    HAVE_STDINT_H
    WANT_CRC32
    GIT_VERSION=\"bc55d46\"
    # INLINE omitted: handy's compiled units (lynx/, blip/, libretro.cpp)
    # contain no INLINE macros; only libretro_core_options.h uses retro_inline's
    # self-defined INLINE, which needs no -D.
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches all prior core targets). Linked with upstream's own version script
# so only the standard retro_* Libretro ABI is exported.
target_link_options(handy_core PRIVATE
    "-Wl,--version-script=${HANDY_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(handy_core
    log
    m
)

# ---------------------------------------------------------------------------
# mednafen_wswan (WonderSwan / WonderSwan Color, "Beetle WonderSwan"):
# vendored under third_party/cores/mednafen_wswan/, pinned to upstream commit
# 4b01295838ea89e3f1355bbe4cb5cf98aa6108cd (libretro/beetle-wswan-libretro).
# Pure C target; no -fexceptions needed.
# ---------------------------------------------------------------------------

# Compiled source list: the shared fragment cmake/cores/mednafen_wswan-
# sources.cmake — the single source of truth consumed by the Android, Linux,
# and Windows candidate fragments so no platform can drift. 16 sources
# exactly (pure C; see VENDORING.md). MEDNAFEN_WSWAN_DIR is set in
# native/player/CMakeLists.txt before this fragment is included.
include(${CMAKE_CURRENT_LIST_DIR}/mednafen_wswan-sources.cmake)

add_library(mednafen_wswan_core SHARED
    # The exact curated 16-source set from the shared fragment (identical to
    # Android and the Windows candidate; pure C — no C++ translation units at
    # this pin).
    ${ROMM_MEDNAFEN_WSWAN_SOURCES}
)

target_include_directories(mednafen_wswan_core SYSTEM PRIVATE
    ${MEDNAFEN_WSWAN_DIR}
    ${MEDNAFEN_WSWAN_DIR}/mednafen
    ${MEDNAFEN_WSWAN_DIR}/mednafen/include
    ${MEDNAFEN_WSWAN_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(mednafen_wswan_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS.
    __LIBRETRO__
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION_NUMERIC=926
    WANT_16BPP
    WANT_STEREO_SOUND
    SIZEOF_DOUBLE=8
    MPC_FIXED_POINT
    STDC_HEADERS
    __STDC_LIMIT_MACROS
    _LOW_ACCURACY_
    NDEBUG
    INLINE=inline
    GIT_VERSION=\"4b01295\"
    # ANDROID_ARM omitted: upstream defines it for armeabi-v7a only to guard
    # inline asm in v30mz.c; the pinned commit contains zero inline asm, so
    # it is vestigial. No ABI-conditional blocks are used by existing targets.
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Beetle WonderSwan's internal symbols.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(mednafen_wswan_core PRIVATE
    "-Wl,--version-script=${MEDNAFEN_WSWAN_DIR}/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(mednafen_wswan_core
    m
)

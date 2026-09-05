# ---------------------------------------------------------------------------
# Stella (Atari 2600): vendored under third_party/cores/stella/, pinned to the
# upstream release tag `7.0`, commit d55b1ae
# (https://github.com/stella-emu/stella/tree/d55b1ae). See
# third_party/cores/stella/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (desktop GUIs, tools, docs, tests).
#
# Source list mirrors upstream's own libretro Android build
# (src/os/libretro/jni/Android.mk + src/os/libretro/Makefile.common) exactly:
# pure C++ (SOURCES_C is empty), -std=c++20, upstream defines. nanojpeg.c
# is compiled inline via #include from a header wrapper (nanojpeg_lib.hxx).
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/stella-sources.cmake)

add_library(stella_core SHARED
    ${ROMM_STELLA_SOURCES}
)

# Upstream's own libretro/jni/Android.mk builds with -std=c++20 (tag 7.0);
# master HEAD uses -std=c++23 but tag 7.0 is preferred for provenance.
set_target_properties(stella_core PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(stella_core SYSTEM PRIVATE
    ${STELLA_DIR}/os/libretro
    ${STELLA_DIR}
    ${STELLA_DIR}/emucore
    ${STELLA_DIR}/emucore/elf
    ${STELLA_DIR}/emucore/tia
    ${STELLA_DIR}/common
    ${STELLA_DIR}/common/audio
    ${STELLA_DIR}/common/tv_filters
    ${STELLA_DIR}/common/sdl_blitter
    ${STELLA_DIR}/common/repository/sqlite
    ${STELLA_DIR}/lib/json
    ${STELLA_DIR}/lib/nanojpeg
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(stella_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    __LIB_RETRO__
    HAVE_STRINGS_H
    SOUND_SUPPORT
    GIT_VERSION=\"d55b1ae\"
)

# Upstream's own libretro/jni/Application.mk sets APP_CPPFLAGS := -fexceptions;
# match that exactly. Vendored third-party source: not held to this project's
# own -Wall -Wextra (matches all prior core targets).
target_compile_options(stella_core PRIVATE -fexceptions)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Stella's internal symbols.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(stella_core PRIVATE
    "-Wl,--version-script=${STELLA_DIR}/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(stella_core
    m
)

# Stella (Atari 2600) Windows x86_64 candidate.  The target is staged only
# under cores-candidate/ and is never advertised by a production manifest.
include(${CMAKE_CURRENT_LIST_DIR}/stella-sources.cmake)

add_library(stella_core SHARED
    ${ROMM_STELLA_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/stella-windows.def
)

set_target_properties(stella_core PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
    PREFIX ""
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

# Match Stella 7.0's libretro build apart from its Android-only define.
# RETRO_API is empty because the shared header defaults to dllexport under
# MinGW; the .def file is the sole, exact PE export boundary.
target_compile_definitions(stella_core PRIVATE
    __LIB_RETRO__
    HAVE_STRINGS_H
    SOUND_SUPPORT
    GIT_VERSION=\"d55b1ae\"
    RETRO_API=
)

target_compile_options(stella_core PRIVATE
    -fexceptions
    -O2
    -DNDEBUG
)

# GNU ld supports this MinGW PE link check. The global toolchain owns static
# libgcc/libstdc++; this fragment adds no runtime libraries.
target_link_options(stella_core PRIVATE "-Wl,--no-undefined")

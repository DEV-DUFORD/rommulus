# mGBA (GBA) Windows x86_64 candidate. This target remains isolated under
# cores-candidate/ until physical Windows qualification is complete.
set(MGBA_DIR ${ROMM_REPO_ROOT}/third_party/cores/mgba)
set(ROMM_MGBA_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/mgba.cmake)
unset(ROMM_MGBA_SOURCES_ONLY)

add_library(mgba_core SHARED
    ${ROMM_MGBA_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/mgba-windows.def
)

set_target_properties(mgba_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
    PREFIX ""
)

target_include_directories(mgba_core SYSTEM PRIVATE
    ${MGBA_DIR}/src
    ${MGBA_DIR}/src/arm
    ${MGBA_DIR}/include
    ${MGBA_DIR}/src/platform/libretro
)

# These match upstream's mingw_x86_64 profile. In particular, do not inherit
# Linux's locale/localtime declarations or its fd-backed VFS configuration.
target_compile_definitions(mgba_core PRIVATE
    DISABLE_THREADING
    MINIMAL_CORE=2
    __LIBRETRO__
    M_CORE_GBA
    M_CORE_GB
    ENABLE_VFS
    ENABLE_DIRECTORIES
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    INLINE=inline
    COLOR_16_BIT
    RESAMPLE_LIBRARY=2
    M_PI=3.14159265358979323846
    MGBA_STANDALONE
    PATH_MAX=4096
    NDEBUG
    COLOR_5_6_5
    GIT_VERSION="32de792"
    RETRO_API=
)

target_compile_options(mgba_core PRIVATE
    -O2
    -DNDEBUG
)

target_link_options(mgba_core PRIVATE
    "-Wl,--no-undefined"
)

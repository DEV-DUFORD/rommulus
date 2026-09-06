# Handy (Atari Lynx), MinGW x86_64. Use the same emulation sources as Android.
set(HANDY_DIR ${ROMM_REPO_ROOT}/third_party/cores/handy)
set(ROMM_HANDY_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/handy.cmake)
unset(ROMM_HANDY_SOURCES_ONLY)

add_library(handy_core SHARED
    ${ROMM_HANDY_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/handy-windows.def
)
set_target_properties(handy_core PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    PREFIX ""
)
target_include_directories(handy_core SYSTEM PRIVATE
    ${HANDY_DIR}/lynx
    ${HANDY_DIR}/libretro
    ${HANDY_DIR}
    ${HANDY_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)
target_compile_definitions(handy_core PRIVATE
    __LIBRETRO__
    HAVE_STRINGS_H
    HAVE_STDINT_H
    WANT_CRC32
    GIT_VERSION="bc55d46"
    FRONTEND_SUPPORTS_RGB565
    RETRO_API=
)
target_compile_options(handy_core PRIVATE -O2 -DNDEBUG)
target_link_options(handy_core PRIVATE "-Wl,--no-undefined"
    -static-libgcc -static-libstdc++)

# Windows x86_64 candidate build for Beetle PCE Fast. This target is staged
# only under cores-candidate/ and does not advertise Windows support.
include(${CMAKE_CURRENT_LIST_DIR}/beetle_pce_fast-sources.cmake)

add_library(beetle_pce_fast_core SHARED
    ${ROMM_BEETLE_PCE_FAST_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/beetle_pce_fast-windows.def
)

set_target_properties(beetle_pce_fast_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    PREFIX ""
)

target_include_directories(beetle_pce_fast_core SYSTEM PRIVATE
    ${BEETLE_PCE_FAST_DIR}
    ${BEETLE_PCE_FAST_DIR}/mednafen
    ${BEETLE_PCE_FAST_DIR}/mednafen/include
    ${BEETLE_PCE_FAST_DIR}/mednafen/hw_misc
    ${BEETLE_PCE_FAST_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(beetle_pce_fast_core PRIVATE
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION="0.9.26"
    MEDNAFEN_VERSION_NUMERIC=926
    __LIBRETRO__
    _LOW_ACCURACY_
    INLINE=inline
    WANT_PCE_FAST_EMU
    NEED_CD
    NEED_TREMOR
    RETRO_API=
)

target_compile_options(beetle_pce_fast_core PRIVATE -O2 -DNDEBUG)
target_link_options(beetle_pce_fast_core PRIVATE "-Wl,--no-undefined")

# Beetle NeoPop (Neo Geo Pocket/Color), MinGW x86_64.
set(MEDNAFEN_NGP_DIR ${ROMM_REPO_ROOT}/third_party/cores/mednafen_ngp)
set(ROMM_MEDNAFEN_NGP_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/mednafen_ngp.cmake)
unset(ROMM_MEDNAFEN_NGP_SOURCES_ONLY)

add_library(mednafen_ngp_core SHARED
    ${ROMM_MEDNAFEN_NGP_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/mednafen_ngp-windows.def
)
set_target_properties(mednafen_ngp_core PROPERTIES PREFIX "")
target_include_directories(mednafen_ngp_core SYSTEM PRIVATE
    ${MEDNAFEN_NGP_DIR}
    ${MEDNAFEN_NGP_DIR}/mednafen
    ${MEDNAFEN_NGP_DIR}/mednafen/include
    ${MEDNAFEN_NGP_DIR}/mednafen/hw_cpu
    ${MEDNAFEN_NGP_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)
target_compile_definitions(mednafen_ngp_core PRIVATE
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION_NUMERIC=926
    WANT_16BPP
    __LIBRETRO__
    WANT_NGP_EMU
    LOAD_FROM_MEMORY=1
    INLINE=inline
    GIT_VERSION="a50d5ac"
    RETRO_API=
)
target_compile_options(mednafen_ngp_core PRIVATE -O2 -DNDEBUG -fexceptions)
target_link_options(mednafen_ngp_core PRIVATE "-Wl,--no-undefined"
    -static-libgcc -static-libstdc++)

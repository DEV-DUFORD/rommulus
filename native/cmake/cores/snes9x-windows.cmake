# Snes9x Windows x86_64 candidate. This target is staged only under
# cores-candidate/ and must not be advertised by a production manifest.
include(${CMAKE_CURRENT_LIST_DIR}/snes9x-sources.cmake)

add_library(snes9x_core SHARED
    ${ROMM_SNES9X_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/snes9x-windows.def
)

set_target_properties(snes9x_core PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
    PREFIX ""
)

target_include_directories(snes9x_core SYSTEM PRIVATE
    ${SNES9X_DIR}/core
    ${SNES9X_DIR}/core/apu
    ${SNES9X_DIR}/core/apu/bapu
    ${SNES9X_DIR}/libretro
    ${SNES9X_DIR}/libretro/libretro-common/include
)

# Matches upstream's MinGW profile. RETRO_API is deliberately empty so the
# .def file is the sole authority over the exact Libretro export boundary.
target_compile_definitions(snes9x_core PRIVATE
    __LIBRETRO__
    HAVE_STRINGS_H
    RIGHTSHIFT_IS_SAR
    HAVE_STDINT_H
    _WIN32
    RETRO_API=
)

target_compile_options(snes9x_core PRIVATE -O2 -DNDEBUG)
target_link_options(snes9x_core PRIVATE "-Wl,--no-undefined")

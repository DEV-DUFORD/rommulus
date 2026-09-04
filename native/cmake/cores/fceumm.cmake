# ---------------------------------------------------------------------------
# FCEUmm (GPL-2.0): vendored under third_party/cores/fceumm/, pinned to
# upstream commit b5e3566
# (https://github.com/libretro/FCEUmm/tree/b5e3566). See
# third_party/cores/fceumm/VENDORING.md for exactly what was vendored.
#
# Source list: the shared mechanically-guarded fragment
# cores/fceumm-sources.cmake — the exact 505-source set (mirroring upstream's
# libretro/Makefile.common SOURCES_C), identical to the Linux build and the
# Windows candidate build.
# ---------------------------------------------------------------------------
set(FCEUMM_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/fceumm)

include(${CMAKE_CURRENT_LIST_DIR}/fceumm-sources.cmake)

add_library(fceumm_core SHARED
    ${ROMM_FCEUMM_SOURCES}
)

target_include_directories(fceumm_core SYSTEM PRIVATE
    ${FCEUMM_DIR}/src/drivers/libretro
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/include
    ${FCEUMM_DIR}/src
    ${FCEUMM_DIR}/src/input
    ${FCEUMM_DIR}/src/boards
    ${FCEUMM_DIR}/src/ntsc
)

target_compile_definitions(fceumm_core PRIVATE
    __LIBRETRO__
    PATH_MAX=1024
    FCEU_VERSION_NUMERIC=9900
    FRONTEND_SUPPORTS_RGB565
    HAVE_NTSC_FILTER
    HAVE_HDPACK
    PSS_STYLE=1
    GIT_VERSION=\"b5e3566\"
)

target_compile_options(fceumm_core PRIVATE -Wno-write-strings -Wsign-compare -Wundef -Wmissing-prototypes)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
target_link_options(fceumm_core PRIVATE
    "-Wl,--version-script=${FCEUMM_DIR}/src/drivers/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(fceumm_core
    log
    m
)

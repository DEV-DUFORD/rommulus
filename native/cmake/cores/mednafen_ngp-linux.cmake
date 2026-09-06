# ---------------------------------------------------------------------------
# mednafen_ngp (Neo Geo Pocket / Neo Geo Pocket Color, "Beetle NeoPop"):
# vendored under third_party/cores/mednafen_ngp/, pinned to upstream commit
# a50d5ac288a81f2104ddf43195a4efdd15c72227 (libretro/beetle-ngp-libretro
# master HEAD). Cartridge-only scope; HLE BIOS used (no firmware required).
# Mixed C/C++ target: 32 .c + 5 .cpp compiled; 5 additional opcode-table
# .c files are #included into z80_ops.c's translation unit, not standalone.
# ---------------------------------------------------------------------------

set(ROMM_MEDNAFEN_NGP_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/mednafen_ngp.cmake)
unset(ROMM_MEDNAFEN_NGP_SOURCES_ONLY)
add_library(mednafen_ngp_core SHARED ${ROMM_MEDNAFEN_NGP_SOURCES})

# Upstream's own jni/Application.mk sets LOCAL_CPP_FEATURES := exceptions;
# match that exactly. Vendored third-party source: not held to this project's
# own -Wall -Wextra (matches all prior core targets).
target_compile_options(mednafen_ngp_core PRIVATE -fexceptions)

target_include_directories(mednafen_ngp_core SYSTEM PRIVATE
    ${MEDNAFEN_NGP_DIR}
    ${MEDNAFEN_NGP_DIR}/mednafen
    ${MEDNAFEN_NGP_DIR}/mednafen/include
    ${MEDNAFEN_NGP_DIR}/mednafen/hw_cpu
    ${MEDNAFEN_NGP_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(mednafen_ngp_core PRIVATE
    # Matches upstream jni/Android.mk's COREFLAGS exactly.
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION_NUMERIC=926
    WANT_16BPP
    __LIBRETRO__
    WANT_NGP_EMU
    LOAD_FROM_MEMORY=1
    INLINE=inline
    GIT_VERSION=\"a50d5ac\"
)

# Export the Libretro ABI and the three save-memory extensions, never
# Beetle NeoPop's internal symbols.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(mednafen_ngp_core PRIVATE
    "-Wl,--version-script=${MEDNAFEN_NGP_DIR}/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(mednafen_ngp_core
    m
)

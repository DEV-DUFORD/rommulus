# ---------------------------------------------------------------------------
# mednafen_ngp (Neo Geo Pocket / Neo Geo Pocket Color, "Beetle NeoPop"):
# vendored under third_party/cores/mednafen_ngp/, pinned to upstream commit
# a50d5ac288a81f2104ddf43195a4efdd15c72227 (libretro/beetle-ngp-libretro
# master HEAD). Cartridge-only scope; HLE BIOS used (no firmware required).
# Mixed C/C++ target: 32 .c + 5 .cpp compiled; 5 additional opcode-table
# .c files are #included into z80_ops.c's translation unit, not standalone.
# ---------------------------------------------------------------------------
if(NOT DEFINED MEDNAFEN_NGP_DIR)
    set(MEDNAFEN_NGP_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/mednafen_ngp)
endif()

set(ROMM_MEDNAFEN_NGP_SOURCES
    # libretro/ — main entry point
    ${MEDNAFEN_NGP_DIR}/libretro.c
    # mednafen/ — core utilities (2 .c + 1 .cpp)
    ${MEDNAFEN_NGP_DIR}/mednafen/state.c
    ${MEDNAFEN_NGP_DIR}/mednafen/settings.c
    ${MEDNAFEN_NGP_DIR}/mednafen/mempatcher.cpp
    # mednafen/ngp/ — Neo Geo Pocket emulation (11 .c + 2 .cpp)
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/system.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/Z80_interface.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/bios.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/biosHLE.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/dma.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/flash.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/gfx.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/interrupt.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/mem.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/rom.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/rtc.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/sound.cpp
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/T6W28_Apu.cpp
    # mednafen/ngp/TLCS-900h/ — TLCS-900h RISC CPU emulator (6 .c)
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_interpret.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_interpret_dst.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_interpret_reg.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_interpret_single.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_interpret_src.c
    ${MEDNAFEN_NGP_DIR}/mednafen/ngp/TLCS-900h/TLCS900h_registers.c
    # mednafen/hw_cpu/z80-fuse/ — Z80 co-processor (2 .c compiled; 5 opcode
    # tables are #included into z80_ops.c, not standalone compilation units)
    ${MEDNAFEN_NGP_DIR}/mednafen/hw_cpu/z80-fuse/z80.c
    ${MEDNAFEN_NGP_DIR}/mednafen/hw_cpu/z80-fuse/z80_ops.c
    # mednafen/sound/ — Blip_Buffer audio resampler (2 .cpp)
    ${MEDNAFEN_NGP_DIR}/mednafen/sound/Blip_Buffer.cpp
    ${MEDNAFEN_NGP_DIR}/mednafen/sound/Stereo_Buffer.cpp
    # libretro-common/ — C helper utilities (10 files)
    ${MEDNAFEN_NGP_DIR}/libretro-common/compat/compat_posix_string.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/compat/compat_snprintf.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/compat/compat_strl.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/compat/fopen_utf8.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/encodings/encoding_utf.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/file/file_path.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/streams/file_stream.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/string/stdstring.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/time/rtime.c
    ${MEDNAFEN_NGP_DIR}/libretro-common/vfs/vfs_implementation.c
)

if(ROMM_MEDNAFEN_NGP_SOURCES_ONLY)
    return()
endif()

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
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
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
target_link_options(mednafen_ngp_core PRIVATE
    "-Wl,--version-script=${MEDNAFEN_NGP_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(mednafen_ngp_core
    log
    m
)

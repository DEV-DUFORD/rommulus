# Windows x86_64 candidate build for Genesis Plus GX. This target is staged
# only under cores-candidate/ and does not advertise Windows support.
set(ROMM_GENESIS_PLUS_GX_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/genesis_plus_gx.cmake)
unset(ROMM_GENESIS_PLUS_GX_SOURCES_ONLY)

add_library(genesis_plus_gx_core SHARED
    ${ROMM_GENESIS_PLUS_GX_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/genesis_plus_gx-windows.def
)

set_target_properties(genesis_plus_gx_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    PREFIX ""
)

target_include_directories(genesis_plus_gx_core SYSTEM PRIVATE
    ${GENESIS_PLUS_GX_DIR}/core
    ${GENESIS_PLUS_GX_DIR}/core/z80
    ${GENESIS_PLUS_GX_DIR}/core/m68k
    ${GENESIS_PLUS_GX_DIR}/core/ntsc
    ${GENESIS_PLUS_GX_DIR}/core/sound
    ${GENESIS_PLUS_GX_DIR}/core/sound/minimp3
    ${GENESIS_PLUS_GX_DIR}/core/input_hw
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/svp
    ${GENESIS_PLUS_GX_DIR}/libretro
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/include
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/include
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/include
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(genesis_plus_gx_core PRIVATE
    WANT_CRC32=1
    USE_PER_SOUND_CHANNELS_CONFIG
    LSB_FIRST
    BYTE_ORDER=LITTLE_ENDIAN
    __LIBRETRO__
    FRONTEND_SUPPORTS_RGB565
    ALIGN_LONG
    ALIGN_WORD
    M68K_OVERCLOCK_SHIFT=20
    Z80_OVERCLOCK_SHIFT=20
    HAVE_YM3438_CORE
    HAVE_OPLL_CORE
    USE_LIBTREMOR
    USE_16BPP_RENDERING
    USE_LIBRETRO_VFS
    USE_LIBCHDR
    _7ZIP_ST
    ZSTD_DISABLE_ASM
    MAXROMSIZE=16777216
    INLINE=static\ inline
    RETRO_API=
)

target_compile_options(genesis_plus_gx_core PRIVATE -O2 -DNDEBUG -ffast-math -funroll-loops)
target_link_options(genesis_plus_gx_core PRIVATE
    "-Wl,--no-undefined"
    "-static-libgcc"
)

# ---------------------------------------------------------------------------
# Genesis Plus GX (custom non-commercial redistribution license; see
# CoreManifest.kt's "genesis_plus_gx" entry and the owner risk-acceptance
# decision recorded there and in HANDOFF.md's Phase 7 section): vendored
# under third_party/cores/genesis_plus_gx/, pinned to the upstream `master`
# HEAD commit ca93fec870378f3bff65931bcd828d5e756cce75
# (https://github.com/libretro/Genesis-Plus-GX/tree/ca93fec870378f3bff65931bcd828d5e756cce75;
# upstream carries no release tags). See
# third_party/cores/genesis_plus_gx/VENDORING.md for exactly what was
# vendored, including upstream's pinned libchdr/LZMA/zstd dependency sources
# for Sega CD CHD support.
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS), including USE_LIBCHDR.
# ---------------------------------------------------------------------------
if(NOT DEFINED GENESIS_PLUS_GX_DIR)
    set(GENESIS_PLUS_GX_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/genesis_plus_gx)
endif()
set(GENESIS_PLUS_GX_DEPS_DIR ${GENESIS_PLUS_GX_DIR}/libretro/deps)

set(ROMM_GENESIS_PLUS_GX_SOURCES
    ${GENESIS_PLUS_GX_DIR}/core/genesis.c
    ${GENESIS_PLUS_GX_DIR}/core/io_ctrl.c
    ${GENESIS_PLUS_GX_DIR}/core/loadrom.c
    ${GENESIS_PLUS_GX_DIR}/core/mem68k.c
    ${GENESIS_PLUS_GX_DIR}/core/membnk.c
    ${GENESIS_PLUS_GX_DIR}/core/memz80.c
    ${GENESIS_PLUS_GX_DIR}/core/state.c
    ${GENESIS_PLUS_GX_DIR}/core/system.c
    ${GENESIS_PLUS_GX_DIR}/core/vdp_ctrl.c
    ${GENESIS_PLUS_GX_DIR}/core/vdp_render.c
    ${GENESIS_PLUS_GX_DIR}/core/z80/z80.c
    ${GENESIS_PLUS_GX_DIR}/core/m68k/m68kcpu.c
    ${GENESIS_PLUS_GX_DIR}/core/m68k/s68kcpu.c
    ${GENESIS_PLUS_GX_DIR}/core/ntsc/md_ntsc.c
    ${GENESIS_PLUS_GX_DIR}/core/ntsc/sms_ntsc.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/blip_buf.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/eq.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/opll.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/psg.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/sound.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/ym2413.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/ym2612.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/ym3438.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/bitwise.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/block.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/codebook.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/floor0.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/floor1.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/framing.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/info.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/mapping0.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/mdct.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/registry.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/res012.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/sharedbook.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/synthesis.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/vorbisfile.c
    ${GENESIS_PLUS_GX_DIR}/core/sound/tremor/window.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/activator.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/gamepad.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/graphic_board.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/input.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/lightgun.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/mouse.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/paddle.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/smash.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/sportspad.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/teamplayer.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/terebi_oekaki.c
    ${GENESIS_PLUS_GX_DIR}/core/input_hw/xe_1ap.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/cd_cart.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/cdc.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/cdd.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/gfx.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/pcm.c
    ${GENESIS_PLUS_GX_DIR}/core/cd_hw/scd.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/areplay.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/eeprom_93c.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/eeprom_i2c.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/eeprom_spi.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/flash_cfi.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/ggenie.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/md_cart.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/megasd.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/sms_cart.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/sram.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/yx5200.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/svp/ssp16.c
    ${GENESIS_PLUS_GX_DIR}/core/cart_hw/svp/svp.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/compat/compat_posix_string.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/compat/compat_snprintf.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/compat/compat_strcasestr.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/compat/compat_strl.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/compat/fopen_utf8.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/encodings/encoding_utf.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/file/file_path.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/file/retro_dirent.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/lists/dir_list.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/lists/string_list.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/memmap/memalign.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/streams/file_stream.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/streams/file_stream_transforms.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/string/stdstring.c
    ${GENESIS_PLUS_GX_DIR}/libretro/libretro-common/vfs/vfs_implementation.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/adler32.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/crc32.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/inffast.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/inflate.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/inftrees.c
    ${GENESIS_PLUS_GX_DIR}/libretro/deps/zlib-1.2.11/zutil.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/Alloc.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/Bra86.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/BraIA64.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/CpuArch.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/Delta.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/LzFind.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/Lzma86Dec.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/LzmaDec.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/LzmaEnc.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/lzma-19.00/src/Sort.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/src/libchdr_bitstream.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/src/libchdr_cdrom.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/src/libchdr_chd.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/src/libchdr_flac.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/libchdr/src/libchdr_huffman.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/common/entropy_common.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/common/error_private.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/common/fse_decompress.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/common/zstd_common.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/common/xxhash.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/decompress/huf_decompress.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/decompress/zstd_ddict.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/decompress/zstd_decompress.c
    ${GENESIS_PLUS_GX_DEPS_DIR}/zstd/lib/decompress/zstd_decompress_block.c
)

list(LENGTH ROMM_GENESIS_PLUS_GX_SOURCES ROMM_GENESIS_PLUS_GX_SOURCE_COUNT)
if(NOT ROMM_GENESIS_PLUS_GX_SOURCE_COUNT EQUAL 115)
    message(FATAL_ERROR
        "genesis_plus_gx.cmake: expected 115 sources, found "
        "${ROMM_GENESIS_PLUS_GX_SOURCE_COUNT}")
endif()
foreach(_romm_genesis_plus_gx_src IN LISTS ROMM_GENESIS_PLUS_GX_SOURCES)
    if(NOT EXISTS ${_romm_genesis_plus_gx_src})
        message(FATAL_ERROR
            "genesis_plus_gx.cmake: missing vendored source: "
            "${_romm_genesis_plus_gx_src}")
    endif()
endforeach()

# The Android/Linux target and the Win32 candidate must compile this exact
# curated inventory. The source-only mode lets the candidate reuse it without
# inheriting Android's log link library or ELF version-script flags.
if(ROMM_GENESIS_PLUS_GX_SOURCES_ONLY)
    return()
endif()

add_library(genesis_plus_gx_core SHARED
    ${ROMM_GENESIS_PLUS_GX_SOURCES}
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
)

target_compile_definitions(genesis_plus_gx_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS.
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
)

# Upstream's own libretro/jni/Android.mk only defines _ARM_ASSEM_ (enables
# core/sound/tremor/asm_arm.h's 32-bit-ARM-only inline `smull`/predicated
# assembly) for TARGET_ARCH=arm — never for arm64. Match that exactly per-ABI.
# Upstream's Android.mk also sets `LOCAL_ARM_MODE := arm` for this module:
# the NDK's default armeabi-v7a codegen is Thumb-2, where the predicated
# (`addne`/`movne`/`moveq`/`subeq`) instructions in asm_arm.h are illegal
# outside an explicit IT block. `-marm` forces classic 32-bit ARM encoding
# for this compilation unit, matching upstream exactly, instead of rewriting
# vendored upstream assembly to be IT-block-safe.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(genesis_plus_gx_core PRIVATE _ARM_ASSEM_)
    target_compile_options(genesis_plus_gx_core PRIVATE -marm)
endif()

target_compile_options(genesis_plus_gx_core PRIVATE -ffast-math -funroll-loops)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core), linked with upstream's own version script so only
# the standard retro_* Libretro ABI is exported.
target_link_options(genesis_plus_gx_core PRIVATE
    "-Wl,--version-script=${GENESIS_PLUS_GX_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(genesis_plus_gx_core
    log
    m
)

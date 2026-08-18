# ---------------------------------------------------------------------------
# beetle_pce_fast (TurboGrafx-16 / PC Engine): vendored under
# third_party/cores/beetle_pce_fast/, pinned to upstream commit b211204
# (libretro/beetle-pce-fast-libretro). Cartridge-only scope: CD, Tremor, and
# arcade card sources included; no CHD, 7zip, or Zstd. C-only target.
# ---------------------------------------------------------------------------

add_library(beetle_pce_fast_core SHARED
    # libretro/ — main entry point
    ${BEETLE_PCE_FAST_DIR}/libretro.c
    # mednafen/pce_fast/ — core emulation
    ${BEETLE_PCE_FAST_DIR}/mednafen/hw_misc/arcade_card/arcade_card.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/huc6280.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/input.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/pcecd_drive.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/pcecd.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/psg.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/vdc.c
    # mednafen/sound/ — audio backend
    ${BEETLE_PCE_FAST_DIR}/mednafen/sound/Blip_Buffer.c
    # mednafen/cdrom/ — CD-ROM subsystem
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/CDAccess.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/CDAccess_CCD.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/CDAccess_Image.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/audioreader.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/cdromif.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/CDUtility.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/edc_crc32.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/galois.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/l-ec.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/lec.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdrom/recover-raw.c
    # mednafen/ — shared utilities
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdstream.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/file.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/general.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/mednafen-endian.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/mempatcher.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/okiadpcm.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/settings.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/state.c
    # mednafen/tremor/ — Vorbis decoder (NEED_TREMOR)
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/bitwise.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/block.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/codebook.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/floor0.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/floor1.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/framing.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/info.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/mapping0.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/mdct.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/registry.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/res012.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/sharedbook.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/synthesis.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/vorbisfile.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/tremor/window.c
    # libretro-common/ — C helper utilities (17 files)
    ${BEETLE_PCE_FAST_DIR}/libretro-common/compat/compat_posix_string.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/compat/compat_snprintf.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/compat/compat_strcasestr.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/compat/compat_strl.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/compat/fopen_utf8.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/encodings/encoding_crc32.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/encodings/encoding_utf.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/file/file_path.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/file/retro_dirent.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/lists/dir_list.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/lists/string_list.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/memmap/memalign.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/streams/file_stream.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/streams/file_stream_transforms.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/string/stdstring.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/time/rtime.c
    ${BEETLE_PCE_FAST_DIR}/libretro-common/vfs/vfs_implementation.c
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
    # Matches upstream libretro/jni/Android.mk's COREFLAGS for cartridge-only build.
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION="0.9.26"
    MEDNAFEN_VERSION_NUMERIC=926
    __LIBRETRO__
    _LOW_ACCURACY_
    INLINE=inline
    WANT_PCE_FAST_EMU
    NEED_CD
    NEED_TREMOR
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Beetle PCE Fast's internal symbols.
target_link_options(beetle_pce_fast_core PRIVATE
    "-Wl,--version-script=${BEETLE_PCE_FAST_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(beetle_pce_fast_core
    m
)

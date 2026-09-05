# ---------------------------------------------------------------------------
# beetle_pce_fast (TurboGrafx-16 / PC Engine) — SHARED compiled source list.
#
# Contract: the including project must define BEETLE_PCE_FAST_DIR before
# including this fragment. The curated 60-source cartridge build is shared by
# Android, Linux, and the Windows candidate so platform inventories cannot
# drift independently.
# ---------------------------------------------------------------------------

if(NOT DEFINED BEETLE_PCE_FAST_DIR OR NOT IS_DIRECTORY ${BEETLE_PCE_FAST_DIR})
    message(FATAL_ERROR
        "beetle_pce_fast-sources.cmake: BEETLE_PCE_FAST_DIR must identify "
        "third_party/cores/beetle_pce_fast")
endif()

set(ROMM_BEETLE_PCE_FAST_SOURCES
    ${BEETLE_PCE_FAST_DIR}/libretro.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/hw_misc/arcade_card/arcade_card.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/huc6280.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/input.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/pcecd_drive.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/pcecd.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/psg.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/pce_fast/vdc.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/sound/Blip_Buffer.c
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
    ${BEETLE_PCE_FAST_DIR}/mednafen/cdstream.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/file.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/general.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/mednafen-endian.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/mempatcher.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/okiadpcm.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/settings.c
    ${BEETLE_PCE_FAST_DIR}/mednafen/state.c
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

list(LENGTH ROMM_BEETLE_PCE_FAST_SOURCES ROMM_BEETLE_PCE_FAST_SOURCE_COUNT)
if(NOT ROMM_BEETLE_PCE_FAST_SOURCE_COUNT EQUAL 60)
    message(FATAL_ERROR
        "beetle_pce_fast-sources.cmake: expected 60 sources, found "
        "${ROMM_BEETLE_PCE_FAST_SOURCE_COUNT}")
endif()
foreach(_romm_beetle_pce_fast_src IN LISTS ROMM_BEETLE_PCE_FAST_SOURCES)
    if(NOT EXISTS ${_romm_beetle_pce_fast_src})
        message(FATAL_ERROR
            "beetle_pce_fast-sources.cmake: missing vendored source: "
            "${_romm_beetle_pce_fast_src}")
    endif()
endforeach()

# ---------------------------------------------------------------------------
# ProSystem (Atari 7800, GPL-2.0-or-later): SHARED compiled source list.
#
# Single source of truth for the exact 32-source set every platform fragment
# compiles, so the Android, Linux, and Windows candidate builds can never
# drift apart:
#   - cores/prosystem.cmake          (Android app build, native/CMakeLists.txt)
#   - cores/prosystem-linux.cmake    (POSIX player build, native/player)
#   - cores/prosystem-windows.cmake  (Win32 candidate build, native/player)
#
# The list mirrors upstream's Makefile.common (SOURCES_C) at the pinned
# commit 363b6dfbd3e240762e022c2b4897b4fe55722be3 — see
# third_party/cores/prosystem/VENDORING.md for exactly what was vendored.
# The set is pure C (no C++ units) and network-free by construction: the
# vendored libretro-common subtree carries no networking sources and no
# ProSystem source opens a socket, so all three builds are network-free
# (same posture as the gambatte/fceumm shared fragments).
#
# Contract: the including project MUST define PROSYSTEM_DIR (the vendored
# core tree) before including this fragment. The Android fragment derives it
# from ROMM_APP_CPP_DIR; the Linux/Windows player builds set it in
# native/player/CMakeLists.txt. This fragment defines ROMM_PROSYSTEM_SOURCES
# (absolute paths) and creates no targets.
# ---------------------------------------------------------------------------

if(NOT DEFINED PROSYSTEM_DIR OR NOT IS_DIRECTORY ${PROSYSTEM_DIR})
    message(FATAL_ERROR
        "prosystem-sources.cmake: PROSYSTEM_DIR must be set to the vendored "
        "core tree (third_party/cores/prosystem) before including this "
        "fragment")
endif()

set(ROMM_PROSYSTEM_SOURCES
    # core/ — Libretro driver + emulator engine (SOURCES_C from Makefile.common)
    ${PROSYSTEM_DIR}/core/libretro.c
    ${PROSYSTEM_DIR}/core/Bios.c
    ${PROSYSTEM_DIR}/core/BupChip.c
    ${PROSYSTEM_DIR}/core/Cartridge.c
    ${PROSYSTEM_DIR}/core/Database.c
    ${PROSYSTEM_DIR}/core/Hash.c
    ${PROSYSTEM_DIR}/core/Maria.c
    ${PROSYSTEM_DIR}/core/Memory.c
    ${PROSYSTEM_DIR}/core/Palette.c
    ${PROSYSTEM_DIR}/core/Pokey.c
    ${PROSYSTEM_DIR}/core/ProSystem.c
    ${PROSYSTEM_DIR}/core/Region.c
    ${PROSYSTEM_DIR}/core/Riot.c
    ${PROSYSTEM_DIR}/core/Sally.c
    ${PROSYSTEM_DIR}/core/Tia.c
    # bupboop/coretone/ — zlib audio synthesis (SOURCES_C from Makefile.common)
    ${PROSYSTEM_DIR}/bupboop/coretone/channel.c
    ${PROSYSTEM_DIR}/bupboop/coretone/coretone.c
    ${PROSYSTEM_DIR}/bupboop/coretone/music.c
    ${PROSYSTEM_DIR}/bupboop/coretone/sample.c
    # libretro-common/ — C helper utilities (SOURCES_C from Makefile.common)
    ${PROSYSTEM_DIR}/libretro-common/compat/compat_posix_string.c
    ${PROSYSTEM_DIR}/libretro-common/compat/compat_snprintf.c
    ${PROSYSTEM_DIR}/libretro-common/compat/compat_strcasestr.c
    ${PROSYSTEM_DIR}/libretro-common/compat/compat_strl.c
    ${PROSYSTEM_DIR}/libretro-common/compat/fopen_utf8.c
    ${PROSYSTEM_DIR}/libretro-common/encodings/encoding_utf.c
    ${PROSYSTEM_DIR}/libretro-common/file/file_path.c
    ${PROSYSTEM_DIR}/libretro-common/file/file_path_io.c
    ${PROSYSTEM_DIR}/libretro-common/streams/file_stream.c
    ${PROSYSTEM_DIR}/libretro-common/streams/file_stream_transforms.c
    ${PROSYSTEM_DIR}/libretro-common/string/stdstring.c
    ${PROSYSTEM_DIR}/libretro-common/time/rtime.c
    ${PROSYSTEM_DIR}/libretro-common/vfs/vfs_implementation.c
)

# Guard: the shared list is the single source of truth, so it must contain
# EXACTLY the 32 units upstream's Makefile.common compiles (15 core/ + 4
# bupboop/coretone/ + 13 libretro-common/), and every entry must exist in the
# vendored tree. A drifted or deleted source fails configure loudly instead
# of silently changing what gets compiled (same contract as gambatte- and
# fceumm-sources.cmake).
list(LENGTH ROMM_PROSYSTEM_SOURCES ROMM_PROSYSTEM_SOURCE_COUNT)
if(NOT ROMM_PROSYSTEM_SOURCE_COUNT EQUAL 32)
    message(FATAL_ERROR
        "prosystem-sources.cmake: expected exactly 32 sources, found "
        "${ROMM_PROSYSTEM_SOURCE_COUNT} — the shared source list drifted")
endif()
foreach(_romm_prosystem_src IN LISTS ROMM_PROSYSTEM_SOURCES)
    if(NOT EXISTS ${_romm_prosystem_src})
        message(FATAL_ERROR
            "prosystem-sources.cmake: missing vendored source: "
            "${_romm_prosystem_src}")
    endif()
endforeach()

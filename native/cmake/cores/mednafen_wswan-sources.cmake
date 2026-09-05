# ---------------------------------------------------------------------------
# mednafen_wswan (WonderSwan / WonderSwan Color, "Beetle WonderSwan") —
# SHARED compiled source list.
#
# Single source of truth for the exact 16-source set every platform fragment
# compiles, so the Android, Linux, and Windows candidate builds can never
# drift apart:
#   - cores/mednafen_wswan.cmake          (Android app build)
#   - cores/mednafen_wswan-linux.cmake    (POSIX player build, native/player)
#   - cores/mednafen_wswan-windows.cmake  (Win32 candidate build, native/player)
#
# The list mirrors the vendoring record (third_party/cores/mednafen_wswan/
# VENDORING.md): pure C — this core has NO C++ translation units at pin
# 4b01295838ea89e3f1355bbe4cb5cf98aa6108cd (upstream removed the last one,
# mempatcher.cpp), so no -fexceptions is needed on any platform.
#
# Contract: the including project MUST define MEDNAFEN_WSWAN_DIR (the vendored
# core tree) before including this fragment. The Android fragment derives it
# from ROMM_APP_CPP_DIR; the Linux/Windows player builds set it in
# native/player/CMakeLists.txt. This fragment defines ROMM_MEDNAFEN_WSWAN_
# SOURCES (absolute paths) and creates no targets.
# ---------------------------------------------------------------------------

if(NOT DEFINED MEDNAFEN_WSWAN_DIR OR NOT IS_DIRECTORY ${MEDNAFEN_WSWAN_DIR})
    message(FATAL_ERROR
        "mednafen_wswan-sources.cmake: MEDNAFEN_WSWAN_DIR must be set to the "
        "vendored core tree (third_party/cores/mednafen_wswan) before "
        "including this fragment")
endif()

set(ROMM_MEDNAFEN_WSWAN_SOURCES
    # libretro/ — main entry point
    ${MEDNAFEN_WSWAN_DIR}/libretro.c
    # mednafen/wswan/ — core emulation
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/sound.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/interrupt.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/comm.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/rtc.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/tcache.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/gfx.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/wswan-memory.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/v30mz.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/wswan/eeprom.c
    # mednafen/sound/ — audio backend
    ${MEDNAFEN_WSWAN_DIR}/mednafen/sound/Blip_Buffer.c
    # mednafen/ — shared utilities
    ${MEDNAFEN_WSWAN_DIR}/mednafen/mempatcher.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/state.c
    ${MEDNAFEN_WSWAN_DIR}/mednafen/settings.c
    # libretro-common/ — C helper utilities
    ${MEDNAFEN_WSWAN_DIR}/libretro-common/compat/compat_strl.c
    ${MEDNAFEN_WSWAN_DIR}/libretro-common/compat/compat_snprintf.c
)

# Mechanical guards: the list must stay exactly the curated 16-source set and
# every entry must exist in the vendored tree. A drifted or deleted source
# fails configure loudly instead of silently changing what gets compiled.
list(LENGTH ROMM_MEDNAFEN_WSWAN_SOURCES ROMM_MEDNAFEN_WSWAN_SOURCE_COUNT)
if(NOT ROMM_MEDNAFEN_WSWAN_SOURCE_COUNT EQUAL 16)
    message(FATAL_ERROR
        "mednafen_wswan-sources.cmake: expected exactly 16 sources, found "
        "${ROMM_MEDNAFEN_WSWAN_SOURCE_COUNT} — the shared source list drifted")
endif()
foreach(_romm_mednafen_wswan_src IN LISTS ROMM_MEDNAFEN_WSWAN_SOURCES)
    if(NOT EXISTS ${_romm_mednafen_wswan_src})
        message(FATAL_ERROR
            "mednafen_wswan-sources.cmake: missing vendored source: "
            "${_romm_mednafen_wswan_src}")
    endif()
endforeach()

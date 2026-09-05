# ---------------------------------------------------------------------------
# Snes9x (SNES) — shared compiled source list.
#
# This is the single source of truth for the 57 translation units compiled by
# Android, Linux, and the Windows candidate. It mirrors upstream
# libretro/Makefile.common at tag 1.63 (commit
# 921f9f7b83660eb44ad263022a57a4a029057c37).
# ---------------------------------------------------------------------------

if(NOT DEFINED SNES9X_DIR OR NOT IS_DIRECTORY ${SNES9X_DIR})
    message(FATAL_ERROR
        "snes9x-sources.cmake: SNES9X_DIR must name the vendored core tree")
endif()

set(ROMM_SNES9X_SOURCES
    ${SNES9X_DIR}/core/filter/snes_ntsc.c
    ${SNES9X_DIR}/core/apu/apu.cpp
    ${SNES9X_DIR}/core/apu/bapu/dsp/sdsp.cpp
    ${SNES9X_DIR}/core/apu/bapu/smp/smp.cpp
    ${SNES9X_DIR}/core/apu/bapu/smp/smp_state.cpp
    ${SNES9X_DIR}/core/bsx.cpp
    ${SNES9X_DIR}/core/c4.cpp
    ${SNES9X_DIR}/core/c4emu.cpp
    ${SNES9X_DIR}/core/cheats.cpp
    ${SNES9X_DIR}/core/cheats2.cpp
    ${SNES9X_DIR}/core/clip.cpp
    ${SNES9X_DIR}/core/conffile.cpp
    ${SNES9X_DIR}/core/controls.cpp
    ${SNES9X_DIR}/core/cpu.cpp
    ${SNES9X_DIR}/core/cpuexec.cpp
    ${SNES9X_DIR}/core/cpuops.cpp
    ${SNES9X_DIR}/core/crosshairs.cpp
    ${SNES9X_DIR}/core/dma.cpp
    ${SNES9X_DIR}/core/dsp.cpp
    ${SNES9X_DIR}/core/dsp1.cpp
    ${SNES9X_DIR}/core/dsp2.cpp
    ${SNES9X_DIR}/core/dsp3.cpp
    ${SNES9X_DIR}/core/dsp4.cpp
    ${SNES9X_DIR}/core/fxinst.cpp
    ${SNES9X_DIR}/core/fxemu.cpp
    ${SNES9X_DIR}/core/gfx.cpp
    ${SNES9X_DIR}/core/globals.cpp
    ${SNES9X_DIR}/core/memmap.cpp
    ${SNES9X_DIR}/core/obc1.cpp
    ${SNES9X_DIR}/core/msu1.cpp
    ${SNES9X_DIR}/core/ppu.cpp
    ${SNES9X_DIR}/core/stream.cpp
    ${SNES9X_DIR}/core/sa1.cpp
    ${SNES9X_DIR}/core/sa1cpu.cpp
    ${SNES9X_DIR}/core/screenshot.cpp
    ${SNES9X_DIR}/core/sdd1.cpp
    ${SNES9X_DIR}/core/sdd1emu.cpp
    ${SNES9X_DIR}/core/seta.cpp
    ${SNES9X_DIR}/core/seta010.cpp
    ${SNES9X_DIR}/core/seta011.cpp
    ${SNES9X_DIR}/core/seta018.cpp
    ${SNES9X_DIR}/core/snapshot.cpp
    ${SNES9X_DIR}/core/snes9x.cpp
    ${SNES9X_DIR}/core/spc7110.cpp
    ${SNES9X_DIR}/core/srtc.cpp
    ${SNES9X_DIR}/core/tile.cpp
    ${SNES9X_DIR}/core/tileimpl-n1x1.cpp
    ${SNES9X_DIR}/core/tileimpl-n2x1.cpp
    ${SNES9X_DIR}/core/tileimpl-h2x1.cpp
    ${SNES9X_DIR}/core/sha256.cpp
    ${SNES9X_DIR}/core/bml.cpp
    ${SNES9X_DIR}/core/movie.cpp
    ${SNES9X_DIR}/core/fscompat.cpp
    ${SNES9X_DIR}/libretro/libretro.cpp
)

list(LENGTH ROMM_SNES9X_SOURCES ROMM_SNES9X_SOURCE_COUNT)
if(NOT ROMM_SNES9X_SOURCE_COUNT EQUAL 54)
    message(FATAL_ERROR
        "snes9x-sources.cmake: expected exactly 54 sources, found "
        "${ROMM_SNES9X_SOURCE_COUNT} — the shared source list drifted")
endif()
foreach(_romm_snes9x_src IN LISTS ROMM_SNES9X_SOURCES)
    if(NOT EXISTS ${_romm_snes9x_src})
        message(FATAL_ERROR
            "snes9x-sources.cmake: missing vendored source: ${_romm_snes9x_src}")
    endif()
endforeach()

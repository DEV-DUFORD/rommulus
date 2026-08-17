# ---------------------------------------------------------------------------
# Snes9x (project's own non-commercial redistribution license; see
# CoreManifest.kt's "snes9x" entry and the owner risk-acceptance decision
# recorded there and in HANDOFF.md's Phase 7 section): vendored under
# third_party/cores/snes9x/, pinned to the upstream release tag `1.63`,
# commit 921f9f7b83660eb44ad263022a57a4a029057c37
# (https://github.com/snes9xgit/snes9x/tree/1.63). See
# third_party/cores/snes9x/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (JMA/ZIP ROM archive readers, the
# desktop Vulkan GUI's vendored third-party libraries, netplay, and the
# interactive debugger — none used by this libretro build).
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS) and libretro/Makefile (-std=c++14)
# exactly.
# ---------------------------------------------------------------------------
set(SNES9X_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/snes9x)

add_library(snes9x_core SHARED
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

set_target_properties(snes9x_core PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(snes9x_core SYSTEM PRIVATE
    ${SNES9X_DIR}/core
    ${SNES9X_DIR}/core/apu
    ${SNES9X_DIR}/core/apu/bapu
    ${SNES9X_DIR}/libretro
    ${SNES9X_DIR}/libretro/libretro-common/include
)

target_compile_definitions(snes9x_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    ANDROID
    __LIBRETRO__
    HAVE_STRINGS_H
    RIGHTSHIFT_IS_SAR
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
target_link_options(snes9x_core PRIVATE
    "-Wl,--version-script=${SNES9X_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(snes9x_core
    log
    m
)

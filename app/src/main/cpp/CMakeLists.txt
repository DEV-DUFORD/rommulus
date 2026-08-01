cmake_minimum_required(VERSION 3.22.1)
project(romm_libretro_host LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Treat warnings seriously in native code that will run in a dedicated,
# crash-isolated process (LIBRETRO_REFACTOR.md section 5). Applied per-target
# below (not via directory-wide add_compile_options) so it does not also
# impose our strictness on vendored third-party sources (third_party/oboe),
# which build with their own upstream-tested warning flags.
set(ROMM_WARNING_FLAGS -Wall -Wextra)

# ---------------------------------------------------------------------------
# Oboe (Apache-2.0): vendored under third_party/oboe/ (include/, src/, its own
# CMakeLists.txt, and LICENSE), pinned to the upstream v1.10.0 tag, commit
# a81bb9f87d4105b84b682685d3bfbb5beca371d1
# (https://github.com/google/oboe/tree/a81bb9f87d4105b84b682685d3bfbb5beca371d1).
# Only include/ and src/ were copied (samples/tests/docs excluded); Oboe's own
# CMakeLists.txt defines the `oboe` static library target we link below.
# ---------------------------------------------------------------------------
add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/oboe
                  ${CMAKE_CURRENT_BINARY_DIR}/oboe)

# ---------------------------------------------------------------------------
# romm_libretro_host: the JNI-facing native library. Core loading, environment
# callbacks, the emulation thread, video-to-Surface, and Oboe audio output all
# live here (LIBRETRO_REFACTOR.md section 13, Phase 2).
# ---------------------------------------------------------------------------
add_library(romm_libretro_host SHARED
    libretro_host/jni_bridge.cpp
    libretro_host/core_library.cpp
    libretro_host/environment.cpp
    libretro_host/emulation_session.cpp
    libretro_host/atomic_file_store.cpp
    libretro_host/video_output.cpp
    libretro_host/audio_output.cpp
)

target_include_directories(romm_libretro_host PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

# Marked SYSTEM (independent of Oboe's own PUBLIC include declaration) so
# -Wall -Wextra below only applies to our own code, not to warnings that
# originate from inline definitions in Oboe's public headers.
target_include_directories(romm_libretro_host SYSTEM PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/oboe/include
)

target_link_libraries(romm_libretro_host
    log
    android
    oboe
)

target_compile_options(romm_libretro_host PRIVATE ${ROMM_WARNING_FLAGS})

# ---------------------------------------------------------------------------
# SameBoy (Expat/MIT): vendored under third_party/cores/sameboy/ (Core/,
# libretro/, BootROMs/, LICENSE), pinned to the upstream v1.0.3-libretro tag,
# commit 8230189896a8bb6598574d302ba0ad3658f98ab4
# (https://github.com/LIJI32/SameBoy/tree/v1.0.3-libretro). License review
# recorded in CoreManifest.kt's "sameboy" entry
# (reviewedBy="DEV-DUFORD", reviewedOn="2026-07-28"). See
# third_party/cores/sameboy/VENDORING.md for exactly what was vendored, why,
# and how the checked-in libretro/generated/*_boot.c files were produced.
#
# Source list and baseline preprocessor flags mirror upstream's own
# libretro/Makefile.common (SOURCES_C) and libretro/jni/Android.mk
# (COREFLAGS). ROMM_LIBRETRO_SAMPLE_RATE is the one documented integration
# override: upstream otherwise emits ~2.1MHz PCM and expects a full frontend
# resampler, which is prohibitively expensive on the target Android TV.
# ---------------------------------------------------------------------------
set(SAMEBOY_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/sameboy)

add_library(sameboy_core SHARED
    ${SAMEBOY_DIR}/Core/gb.c
    ${SAMEBOY_DIR}/Core/sgb.c
    ${SAMEBOY_DIR}/Core/apu.c
    ${SAMEBOY_DIR}/Core/memory.c
    ${SAMEBOY_DIR}/Core/mbc.c
    ${SAMEBOY_DIR}/Core/timing.c
    ${SAMEBOY_DIR}/Core/display.c
    ${SAMEBOY_DIR}/Core/camera.c
    ${SAMEBOY_DIR}/Core/sm83_cpu.c
    ${SAMEBOY_DIR}/Core/joypad.c
    ${SAMEBOY_DIR}/Core/save_state.c
    ${SAMEBOY_DIR}/Core/random.c
    ${SAMEBOY_DIR}/Core/rumble.c
    ${SAMEBOY_DIR}/libretro/generated/agb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/cgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/cgb0_boot.c
    ${SAMEBOY_DIR}/libretro/generated/mgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/dmg_boot.c
    ${SAMEBOY_DIR}/libretro/generated/sgb_boot.c
    ${SAMEBOY_DIR}/libretro/generated/sgb2_boot.c
    ${SAMEBOY_DIR}/libretro/libretro.c
)

# Upstream's own libretro/jni/Android.mk builds this core with -std=c99
# specifically; match it exactly rather than relying on this project's own
# (newer) default C_STANDARD.
set_target_properties(sameboy_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
)

target_include_directories(sameboy_core SYSTEM PRIVATE
    ${SAMEBOY_DIR}
)

target_compile_definitions(sameboy_core PRIVATE
    # Matches upstream libretro/Makefile.common exactly (disables the
    # debugger/cheats/rewind/timekeeping subsystems this build never uses —
    # see VENDORING.md for why their sources are still vendored but unused).
    GB_DISABLE_TIMEKEEPING
    GB_DISABLE_REWIND
    GB_DISABLE_DEBUGGER
    GB_DISABLE_CHEATS
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    INLINE=inline
    __LIBRETRO__
    GB_INTERNAL
    GB_VERSION="1.0.3"
    ANDROID
    # SameBoy otherwise uses half the Game Boy clock (~2.1MHz). Producing
    # hardware-rate PCM directly avoids millions of unnecessary samples per
    # second while preserving the core's own APU resampling path.
    ROMM_LIBRETRO_SAMPLE_RATE=48000
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches how third_party/oboe/ is treated above) and linked with upstream's
# own version script so only the standard retro_* Libretro ABI is exported —
# never SameBoy's internal GB_*/GB-prefixed symbols.
target_link_options(sameboy_core PRIVATE
    "-Wl,--version-script=${SAMEBOY_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(sameboy_core
    log
    m
)

# ---------------------------------------------------------------------------
# Genesis Plus GX (custom non-commercial redistribution license; see
# CoreManifest.kt's "genesis_plus_gx" entry and the owner risk-acceptance
# decision recorded there and in HANDOFF.md's Phase 7 section): vendored
# under third_party/cores/genesis_plus_gx/, pinned to the upstream `master`
# HEAD commit ca93fec870378f3bff65931bcd828d5e756cce75
# (https://github.com/libretro/Genesis-Plus-GX/tree/ca93fec870378f3bff65931bcd828d5e756cce75;
# upstream carries no release tags). See
# third_party/cores/genesis_plus_gx/VENDORING.md for exactly what was
# vendored, why, and what was deliberately excluded (CHD/libchdr/lzma/zstd
# Sega CD compressed-image support, out of this core's current
# cartridge-only CoreManifest scope).
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS) minus HAVE_CHD/USE_LIBCHDR (see
# VENDORING.md, "Deliberately excluded").
# ---------------------------------------------------------------------------
set(GENESIS_PLUS_GX_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/genesis_plus_gx)

add_library(genesis_plus_gx_core SHARED
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
)

target_compile_definitions(genesis_plus_gx_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly, minus
    # HAVE_CHD/USE_LIBCHDR/_7ZIP_ST/ZSTD_DISABLE_ASM (see VENDORING.md,
    # "Deliberately excluded" — this core is built cartridge-only).
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
set(SNES9X_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/snes9x)

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

# ---------------------------------------------------------------------------
# FCEUmm (GPL-2.0): vendored under third_party/cores/fceumm/, pinned to
# upstream commit b5e3566
# (https://github.com/libretro/FCEUmm/tree/b5e3566). See
# third_party/cores/fceumm/VENDORING.md for exactly what was vendored.
#
# Source list mirrors upstream's libretro/Makefile.common (SOURCES_C).
# ---------------------------------------------------------------------------
set(FCEUMM_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/fceumm)

add_library(fceumm_core SHARED
    ${FCEUMM_DIR}/src/boards/__dummy_mapper.c
    ${FCEUMM_DIR}/src/boards/__serial.c
    ${FCEUMM_DIR}/src/boards/09-034a.c
    ${FCEUMM_DIR}/src/boards/103.c
    ${FCEUMM_DIR}/src/boards/104.c
    ${FCEUMM_DIR}/src/boards/106.c
    ${FCEUMM_DIR}/src/boards/108.c
    ${FCEUMM_DIR}/src/boards/112.c
    ${FCEUMM_DIR}/src/boards/116.c
    ${FCEUMM_DIR}/src/boards/117.c
    ${FCEUMM_DIR}/src/boards/120.c
    ${FCEUMM_DIR}/src/boards/121.c
    ${FCEUMM_DIR}/src/boards/122.c
    ${FCEUMM_DIR}/src/boards/126_422_534.c
    ${FCEUMM_DIR}/src/boards/128.c
    ${FCEUMM_DIR}/src/boards/12in1.c
    ${FCEUMM_DIR}/src/boards/134.c
    ${FCEUMM_DIR}/src/boards/14.c
    ${FCEUMM_DIR}/src/boards/15.c
    ${FCEUMM_DIR}/src/boards/151.c
    ${FCEUMM_DIR}/src/boards/156.c
    ${FCEUMM_DIR}/src/boards/162.c
    ${FCEUMM_DIR}/src/boards/163.c
    ${FCEUMM_DIR}/src/boards/164.c
    ${FCEUMM_DIR}/src/boards/168.c
    ${FCEUMM_DIR}/src/boards/170.c
    ${FCEUMM_DIR}/src/boards/174.c
    ${FCEUMM_DIR}/src/boards/175.c
    ${FCEUMM_DIR}/src/boards/177.c
    ${FCEUMM_DIR}/src/boards/178.c
    ${FCEUMM_DIR}/src/boards/18.c
    ${FCEUMM_DIR}/src/boards/182.c
    ${FCEUMM_DIR}/src/boards/183.c
    ${FCEUMM_DIR}/src/boards/185.c
    ${FCEUMM_DIR}/src/boards/186.c
    ${FCEUMM_DIR}/src/boards/187.c
    ${FCEUMM_DIR}/src/boards/189.c
    ${FCEUMM_DIR}/src/boards/190.c
    ${FCEUMM_DIR}/src/boards/191.c
    ${FCEUMM_DIR}/src/boards/193.c
    ${FCEUMM_DIR}/src/boards/195.c
    ${FCEUMM_DIR}/src/boards/199.c
    ${FCEUMM_DIR}/src/boards/206_486.c
    ${FCEUMM_DIR}/src/boards/208.c
    ${FCEUMM_DIR}/src/boards/21_22_23_25.c
    ${FCEUMM_DIR}/src/boards/213.c
    ${FCEUMM_DIR}/src/boards/218.c
    ${FCEUMM_DIR}/src/boards/222.c
    ${FCEUMM_DIR}/src/boards/225.c
    ${FCEUMM_DIR}/src/boards/228.c
    ${FCEUMM_DIR}/src/boards/230.c
    ${FCEUMM_DIR}/src/boards/232.c
    ${FCEUMM_DIR}/src/boards/233.c
    ${FCEUMM_DIR}/src/boards/234.c
    ${FCEUMM_DIR}/src/boards/235.c
    ${FCEUMM_DIR}/src/boards/236.c
    ${FCEUMM_DIR}/src/boards/237.c
    ${FCEUMM_DIR}/src/boards/239.c
    ${FCEUMM_DIR}/src/boards/242.c
    ${FCEUMM_DIR}/src/boards/244.c
    ${FCEUMM_DIR}/src/boards/246.c
    ${FCEUMM_DIR}/src/boards/252_253.c
    ${FCEUMM_DIR}/src/boards/265.c
    ${FCEUMM_DIR}/src/boards/266.c
    ${FCEUMM_DIR}/src/boards/267.c
    ${FCEUMM_DIR}/src/boards/268.c
    ${FCEUMM_DIR}/src/boards/269.c
    ${FCEUMM_DIR}/src/boards/272.c
    ${FCEUMM_DIR}/src/boards/273.c
    ${FCEUMM_DIR}/src/boards/277.c
    ${FCEUMM_DIR}/src/boards/28.c
    ${FCEUMM_DIR}/src/boards/280.c
    ${FCEUMM_DIR}/src/boards/283.c
    ${FCEUMM_DIR}/src/boards/285.c
    ${FCEUMM_DIR}/src/boards/291.c
    ${FCEUMM_DIR}/src/boards/293.c
    ${FCEUMM_DIR}/src/boards/294.c
    ${FCEUMM_DIR}/src/boards/298.c
    ${FCEUMM_DIR}/src/boards/301.c
    ${FCEUMM_DIR}/src/boards/308.c
    ${FCEUMM_DIR}/src/boards/31.c
    ${FCEUMM_DIR}/src/boards/310.c
    ${FCEUMM_DIR}/src/boards/319.c
    ${FCEUMM_DIR}/src/boards/32.c
    ${FCEUMM_DIR}/src/boards/321.c
    ${FCEUMM_DIR}/src/boards/326.c
    ${FCEUMM_DIR}/src/boards/33.c
    ${FCEUMM_DIR}/src/boards/330.c
    ${FCEUMM_DIR}/src/boards/334.c
    ${FCEUMM_DIR}/src/boards/34.c
    ${FCEUMM_DIR}/src/boards/340.c
    ${FCEUMM_DIR}/src/boards/341.c
    ${FCEUMM_DIR}/src/boards/343.c
    ${FCEUMM_DIR}/src/boards/351.c
    ${FCEUMM_DIR}/src/boards/352.c
    ${FCEUMM_DIR}/src/boards/353.c
    ${FCEUMM_DIR}/src/boards/354.c
    ${FCEUMM_DIR}/src/boards/355.c
    ${FCEUMM_DIR}/src/boards/356.c
    ${FCEUMM_DIR}/src/boards/357.c
    ${FCEUMM_DIR}/src/boards/359.c
    ${FCEUMM_DIR}/src/boards/360.c
    ${FCEUMM_DIR}/src/boards/361.c
    ${FCEUMM_DIR}/src/boards/362.c
    ${FCEUMM_DIR}/src/boards/363.c
    ${FCEUMM_DIR}/src/boards/364.c
    ${FCEUMM_DIR}/src/boards/366.c
    ${FCEUMM_DIR}/src/boards/368.c
    ${FCEUMM_DIR}/src/boards/369.c
    ${FCEUMM_DIR}/src/boards/370.c
    ${FCEUMM_DIR}/src/boards/372.c
    ${FCEUMM_DIR}/src/boards/375.c
    ${FCEUMM_DIR}/src/boards/376.c
    ${FCEUMM_DIR}/src/boards/377.c
    ${FCEUMM_DIR}/src/boards/378.c
    ${FCEUMM_DIR}/src/boards/379.c
    ${FCEUMM_DIR}/src/boards/380.c
    ${FCEUMM_DIR}/src/boards/382.c
    ${FCEUMM_DIR}/src/boards/383.c
    ${FCEUMM_DIR}/src/boards/384.c
    ${FCEUMM_DIR}/src/boards/389.c
    ${FCEUMM_DIR}/src/boards/390.c
    ${FCEUMM_DIR}/src/boards/391.c
    ${FCEUMM_DIR}/src/boards/393.c
    ${FCEUMM_DIR}/src/boards/395.c
    ${FCEUMM_DIR}/src/boards/396.c
    ${FCEUMM_DIR}/src/boards/398.c
    ${FCEUMM_DIR}/src/boards/399.c
    ${FCEUMM_DIR}/src/boards/40.c
    ${FCEUMM_DIR}/src/boards/400.c
    ${FCEUMM_DIR}/src/boards/401.c
    ${FCEUMM_DIR}/src/boards/403.c
    ${FCEUMM_DIR}/src/boards/406.c
    ${FCEUMM_DIR}/src/boards/41.c
    ${FCEUMM_DIR}/src/boards/410.c
    ${FCEUMM_DIR}/src/boards/411.c
    ${FCEUMM_DIR}/src/boards/411120-c.c
    ${FCEUMM_DIR}/src/boards/412.c
    ${FCEUMM_DIR}/src/boards/413.c
    ${FCEUMM_DIR}/src/boards/414.c
    ${FCEUMM_DIR}/src/boards/416.c
    ${FCEUMM_DIR}/src/boards/417.c
    ${FCEUMM_DIR}/src/boards/418.c
    ${FCEUMM_DIR}/src/boards/42.c
    ${FCEUMM_DIR}/src/boards/420.c
    ${FCEUMM_DIR}/src/boards/428.c
    ${FCEUMM_DIR}/src/boards/43.c
    ${FCEUMM_DIR}/src/boards/430.c
    ${FCEUMM_DIR}/src/boards/431.c
    ${FCEUMM_DIR}/src/boards/432.c
    ${FCEUMM_DIR}/src/boards/433.c
    ${FCEUMM_DIR}/src/boards/434.c
    ${FCEUMM_DIR}/src/boards/437.c
    ${FCEUMM_DIR}/src/boards/438.c
    ${FCEUMM_DIR}/src/boards/439.c
    ${FCEUMM_DIR}/src/boards/441.c
    ${FCEUMM_DIR}/src/boards/443.c
    ${FCEUMM_DIR}/src/boards/444.c
    ${FCEUMM_DIR}/src/boards/445.c
    ${FCEUMM_DIR}/src/boards/446.c
    ${FCEUMM_DIR}/src/boards/447.c
    ${FCEUMM_DIR}/src/boards/448.c
    ${FCEUMM_DIR}/src/boards/449.c
    ${FCEUMM_DIR}/src/boards/450.c
    ${FCEUMM_DIR}/src/boards/451.c
    ${FCEUMM_DIR}/src/boards/452.c
    ${FCEUMM_DIR}/src/boards/453.c
    ${FCEUMM_DIR}/src/boards/454.c
    ${FCEUMM_DIR}/src/boards/455.c
    ${FCEUMM_DIR}/src/boards/456.c
    ${FCEUMM_DIR}/src/boards/457.c
    ${FCEUMM_DIR}/src/boards/458.c
    ${FCEUMM_DIR}/src/boards/46.c
    ${FCEUMM_DIR}/src/boards/460.c
    ${FCEUMM_DIR}/src/boards/461.c
    ${FCEUMM_DIR}/src/boards/463.c
    ${FCEUMM_DIR}/src/boards/465.c
    ${FCEUMM_DIR}/src/boards/466.c
    ${FCEUMM_DIR}/src/boards/467.c
    ${FCEUMM_DIR}/src/boards/468.c
    ${FCEUMM_DIR}/src/boards/471.c
    ${FCEUMM_DIR}/src/boards/473.c
    ${FCEUMM_DIR}/src/boards/474.c
    ${FCEUMM_DIR}/src/boards/475.c
    ${FCEUMM_DIR}/src/boards/476.c
    ${FCEUMM_DIR}/src/boards/478.c
    ${FCEUMM_DIR}/src/boards/480.c
    ${FCEUMM_DIR}/src/boards/482.c
    ${FCEUMM_DIR}/src/boards/483.c
    ${FCEUMM_DIR}/src/boards/484.c
    ${FCEUMM_DIR}/src/boards/485.c
    ${FCEUMM_DIR}/src/boards/487.c
    ${FCEUMM_DIR}/src/boards/489.c
    ${FCEUMM_DIR}/src/boards/490.c
    ${FCEUMM_DIR}/src/boards/491.c
    ${FCEUMM_DIR}/src/boards/492.c
    ${FCEUMM_DIR}/src/boards/493.c
    ${FCEUMM_DIR}/src/boards/494.c
    ${FCEUMM_DIR}/src/boards/495.c
    ${FCEUMM_DIR}/src/boards/498.c
    ${FCEUMM_DIR}/src/boards/499.c
    ${FCEUMM_DIR}/src/boards/50.c
    ${FCEUMM_DIR}/src/boards/500.c
    ${FCEUMM_DIR}/src/boards/501.c
    ${FCEUMM_DIR}/src/boards/502.c
    ${FCEUMM_DIR}/src/boards/503.c
    ${FCEUMM_DIR}/src/boards/504.c
    ${FCEUMM_DIR}/src/boards/505.c
    ${FCEUMM_DIR}/src/boards/506.c
    ${FCEUMM_DIR}/src/boards/507.c
    ${FCEUMM_DIR}/src/boards/508.c
    ${FCEUMM_DIR}/src/boards/509.c
    ${FCEUMM_DIR}/src/boards/51.c
    ${FCEUMM_DIR}/src/boards/510.c
    ${FCEUMM_DIR}/src/boards/511.c
    ${FCEUMM_DIR}/src/boards/512.c
    ${FCEUMM_DIR}/src/boards/514.c
    ${FCEUMM_DIR}/src/boards/516.c
    ${FCEUMM_DIR}/src/boards/518.c
    ${FCEUMM_DIR}/src/boards/520.c
    ${FCEUMM_DIR}/src/boards/524.c
    ${FCEUMM_DIR}/src/boards/525.c
    ${FCEUMM_DIR}/src/boards/527.c
    ${FCEUMM_DIR}/src/boards/528.c
    ${FCEUMM_DIR}/src/boards/529.c
    ${FCEUMM_DIR}/src/boards/530.c
    ${FCEUMM_DIR}/src/boards/533.c
    ${FCEUMM_DIR}/src/boards/536.c
    ${FCEUMM_DIR}/src/boards/537.c
    ${FCEUMM_DIR}/src/boards/539.c
    ${FCEUMM_DIR}/src/boards/542.c
    ${FCEUMM_DIR}/src/boards/544.c
    ${FCEUMM_DIR}/src/boards/545.c
    ${FCEUMM_DIR}/src/boards/546.c
    ${FCEUMM_DIR}/src/boards/548.c
    ${FCEUMM_DIR}/src/boards/549.c
    ${FCEUMM_DIR}/src/boards/554.c
    ${FCEUMM_DIR}/src/boards/556.c
    ${FCEUMM_DIR}/src/boards/557.c
    ${FCEUMM_DIR}/src/boards/558.c
    ${FCEUMM_DIR}/src/boards/559.c
    ${FCEUMM_DIR}/src/boards/563.c
    ${FCEUMM_DIR}/src/boards/564.c
    ${FCEUMM_DIR}/src/boards/565.c
    ${FCEUMM_DIR}/src/boards/566.c
    ${FCEUMM_DIR}/src/boards/567.c
    ${FCEUMM_DIR}/src/boards/568.c
    ${FCEUMM_DIR}/src/boards/569.c
    ${FCEUMM_DIR}/src/boards/57.c
    ${FCEUMM_DIR}/src/boards/570.c
    ${FCEUMM_DIR}/src/boards/571.c
    ${FCEUMM_DIR}/src/boards/572.c
    ${FCEUMM_DIR}/src/boards/573.c
    ${FCEUMM_DIR}/src/boards/574.c
    ${FCEUMM_DIR}/src/boards/575.c
    ${FCEUMM_DIR}/src/boards/576.c
    ${FCEUMM_DIR}/src/boards/577.c
    ${FCEUMM_DIR}/src/boards/578.c
    ${FCEUMM_DIR}/src/boards/579.c
    ${FCEUMM_DIR}/src/boards/580.c
    ${FCEUMM_DIR}/src/boards/581.c
    ${FCEUMM_DIR}/src/boards/582.c
    ${FCEUMM_DIR}/src/boards/583.c
    ${FCEUMM_DIR}/src/boards/584.c
    ${FCEUMM_DIR}/src/boards/585.c
    ${FCEUMM_DIR}/src/boards/586.c
    ${FCEUMM_DIR}/src/boards/587.c
    ${FCEUMM_DIR}/src/boards/588.c
    ${FCEUMM_DIR}/src/boards/589.c
    ${FCEUMM_DIR}/src/boards/590.c
    ${FCEUMM_DIR}/src/boards/591.c
    ${FCEUMM_DIR}/src/boards/592.c
    ${FCEUMM_DIR}/src/boards/594.c
    ${FCEUMM_DIR}/src/boards/595.c
    ${FCEUMM_DIR}/src/boards/596.c
    ${FCEUMM_DIR}/src/boards/597.c
    ${FCEUMM_DIR}/src/boards/598.c
    ${FCEUMM_DIR}/src/boards/599.c
    ${FCEUMM_DIR}/src/boards/6_8_12_17_561_562.c
    ${FCEUMM_DIR}/src/boards/60.c
    ${FCEUMM_DIR}/src/boards/603-5052.c
    ${FCEUMM_DIR}/src/boards/603.c
    ${FCEUMM_DIR}/src/boards/605.c
    ${FCEUMM_DIR}/src/boards/606.c
    ${FCEUMM_DIR}/src/boards/607.c
    ${FCEUMM_DIR}/src/boards/608.c
    ${FCEUMM_DIR}/src/boards/609.c
    ${FCEUMM_DIR}/src/boards/610.c
    ${FCEUMM_DIR}/src/boards/611.c
    ${FCEUMM_DIR}/src/boards/612.c
    ${FCEUMM_DIR}/src/boards/613.c
    ${FCEUMM_DIR}/src/boards/614.c
    ${FCEUMM_DIR}/src/boards/615.c
    ${FCEUMM_DIR}/src/boards/616.c
    ${FCEUMM_DIR}/src/boards/617.c
    ${FCEUMM_DIR}/src/boards/618.c
    ${FCEUMM_DIR}/src/boards/619.c
    ${FCEUMM_DIR}/src/boards/62.c
    ${FCEUMM_DIR}/src/boards/620.c
    ${FCEUMM_DIR}/src/boards/621.c
    ${FCEUMM_DIR}/src/boards/622.c
    ${FCEUMM_DIR}/src/boards/623.c
    ${FCEUMM_DIR}/src/boards/624.c
    ${FCEUMM_DIR}/src/boards/625.c
    ${FCEUMM_DIR}/src/boards/65.c
    ${FCEUMM_DIR}/src/boards/67.c
    ${FCEUMM_DIR}/src/boards/68.c
    ${FCEUMM_DIR}/src/boards/69.c
    ${FCEUMM_DIR}/src/boards/71.c
    ${FCEUMM_DIR}/src/boards/72.c
    ${FCEUMM_DIR}/src/boards/77.c
    ${FCEUMM_DIR}/src/boards/79.c
    ${FCEUMM_DIR}/src/boards/80.c
    ${FCEUMM_DIR}/src/boards/81.c
    ${FCEUMM_DIR}/src/boards/82_552.c
    ${FCEUMM_DIR}/src/boards/8237.c
    ${FCEUMM_DIR}/src/boards/83_264.c
    ${FCEUMM_DIR}/src/boards/830118C.c
    ${FCEUMM_DIR}/src/boards/830134C.c
    ${FCEUMM_DIR}/src/boards/88.c
    ${FCEUMM_DIR}/src/boards/8in1.c
    ${FCEUMM_DIR}/src/boards/91.c
    ${FCEUMM_DIR}/src/boards/96.c
    ${FCEUMM_DIR}/src/boards/99.c
    ${FCEUMM_DIR}/src/boards/a9746.c
    ${FCEUMM_DIR}/src/boards/ac-08.c
    ${FCEUMM_DIR}/src/boards/addrlatch.c
    ${FCEUMM_DIR}/src/boards/asic_fme7.c
    ${FCEUMM_DIR}/src/boards/asic_h3001.c
    ${FCEUMM_DIR}/src/boards/asic_latch.c
    ${FCEUMM_DIR}/src/boards/asic_mmc1.c
    ${FCEUMM_DIR}/src/boards/asic_mmc2and4.c
    ${FCEUMM_DIR}/src/boards/asic_mmc3.c
    ${FCEUMM_DIR}/src/boards/asic_n118.c
    ${FCEUMM_DIR}/src/boards/asic_pt8154.c
    ${FCEUMM_DIR}/src/boards/asic_qj.c
    ${FCEUMM_DIR}/src/boards/asic_tc3294.c
    ${FCEUMM_DIR}/src/boards/asic_vrc1.c
    ${FCEUMM_DIR}/src/boards/asic_vrc2and4.c
    ${FCEUMM_DIR}/src/boards/asic_vrc3.c
    ${FCEUMM_DIR}/src/boards/asic_vrc6.c
    ${FCEUMM_DIR}/src/boards/asic_vrc7.c
    ${FCEUMM_DIR}/src/boards/bandai.c
    ${FCEUMM_DIR}/src/boards/bb.c
    ${FCEUMM_DIR}/src/boards/bj56.c
    ${FCEUMM_DIR}/src/boards/bmc42in1r.c
    ${FCEUMM_DIR}/src/boards/bmc60311c.c
    ${FCEUMM_DIR}/src/boards/bmc64in1nr.c
    ${FCEUMM_DIR}/src/boards/bmc80013b.c
    ${FCEUMM_DIR}/src/boards/bmc830425C4391t.c
    ${FCEUMM_DIR}/src/boards/bmcctc09.c
    ${FCEUMM_DIR}/src/boards/bmcgamecard.c
    ${FCEUMM_DIR}/src/boards/bmck3006.c
    ${FCEUMM_DIR}/src/boards/bmck3033.c
    ${FCEUMM_DIR}/src/boards/bmcl6in1.c
    ${FCEUMM_DIR}/src/boards/BMW8544.c
    ${FCEUMM_DIR}/src/boards/bonza.c
    ${FCEUMM_DIR}/src/boards/bs-5.c
    ${FCEUMM_DIR}/src/boards/cartram.c
    ${FCEUMM_DIR}/src/boards/cheapocabra.c
    ${FCEUMM_DIR}/src/boards/coolgirl.c
    ${FCEUMM_DIR}/src/boards/datalatch.c
    ${FCEUMM_DIR}/src/boards/dream.c
    ${FCEUMM_DIR}/src/boards/edu2000.c
    ${FCEUMM_DIR}/src/boards/eeprom_93Cx6.c
    ${FCEUMM_DIR}/src/boards/eh8813a.c
    ${FCEUMM_DIR}/src/boards/emu2413.c
    ${FCEUMM_DIR}/src/boards/et-100.c
    ${FCEUMM_DIR}/src/boards/et-4320.c
    ${FCEUMM_DIR}/src/boards/f-15.c
    ${FCEUMM_DIR}/src/boards/famicombox.c
    ${FCEUMM_DIR}/src/boards/faridunrom.c
    ${FCEUMM_DIR}/src/boards/fifo.c
    ${FCEUMM_DIR}/src/boards/fk23c.c
    ${FCEUMM_DIR}/src/boards/flashrom.c
    ${FCEUMM_DIR}/src/boards/gn26.c
    ${FCEUMM_DIR}/src/boards/h2288.c
    ${FCEUMM_DIR}/src/boards/hp10xx_hp20xx.c
    ${FCEUMM_DIR}/src/boards/hp898f.c
    ${FCEUMM_DIR}/src/boards/inx007t.c
    ${FCEUMM_DIR}/src/boards/jyasic.c
    ${FCEUMM_DIR}/src/boards/karaoke.c
    ${FCEUMM_DIR}/src/boards/KG256.c
    ${FCEUMM_DIR}/src/boards/kof97.c
    ${FCEUMM_DIR}/src/boards/KS7012.c
    ${FCEUMM_DIR}/src/boards/KS7013.c
    ${FCEUMM_DIR}/src/boards/KS7016.c
    ${FCEUMM_DIR}/src/boards/KS7017.c
    ${FCEUMM_DIR}/src/boards/KS7030.c
    ${FCEUMM_DIR}/src/boards/KS7031.c
    ${FCEUMM_DIR}/src/boards/KS7032.c
    ${FCEUMM_DIR}/src/boards/KS7037.c
    ${FCEUMM_DIR}/src/boards/KS7057.c
    ${FCEUMM_DIR}/src/boards/latch.c
    ${FCEUMM_DIR}/src/boards/le05.c
    ${FCEUMM_DIR}/src/boards/lh32.c
    ${FCEUMM_DIR}/src/boards/lh51.c
    ${FCEUMM_DIR}/src/boards/lh53.c
    ${FCEUMM_DIR}/src/boards/malee.c
    ${FCEUMM_DIR}/src/boards/mihunche.c
    ${FCEUMM_DIR}/src/boards/mmc1.c
    ${FCEUMM_DIR}/src/boards/mmc2and4.c
    ${FCEUMM_DIR}/src/boards/mmc3.c
    ${FCEUMM_DIR}/src/boards/mmc5.c
    ${FCEUMM_DIR}/src/boards/msm6585.c
    ${FCEUMM_DIR}/src/boards/n106.c
    ${FCEUMM_DIR}/src/boards/n625092.c
    ${FCEUMM_DIR}/src/boards/novel.c
    ${FCEUMM_DIR}/src/boards/onebus.c
    ${FCEUMM_DIR}/src/boards/pec-586.c
    ${FCEUMM_DIR}/src/boards/pic16c5x.c
    ${FCEUMM_DIR}/src/boards/resetnromxin1.c
    ${FCEUMM_DIR}/src/boards/resettxrom.c
    ${FCEUMM_DIR}/src/boards/rt-01.c
    ${FCEUMM_DIR}/src/boards/SA-9602B.c
    ${FCEUMM_DIR}/src/boards/sachen.c
    ${FCEUMM_DIR}/src/boards/sheroes.c
    ${FCEUMM_DIR}/src/boards/subor.c
    ${FCEUMM_DIR}/src/boards/super40in1.c
    ${FCEUMM_DIR}/src/boards/supervision.c
    ${FCEUMM_DIR}/src/boards/t-227-1.c
    ${FCEUMM_DIR}/src/boards/t-262.c
    ${FCEUMM_DIR}/src/boards/tengen.c
    ${FCEUMM_DIR}/src/boards/transformer.c
    ${FCEUMM_DIR}/src/boards/txcchip.c
    ${FCEUMM_DIR}/src/boards/unrom512.c
    ${FCEUMM_DIR}/src/boards/vrc1.c
    ${FCEUMM_DIR}/src/boards/vrc3.c
    ${FCEUMM_DIR}/src/boards/vrc6.c
    ${FCEUMM_DIR}/src/boards/vrc7.c
    ${FCEUMM_DIR}/src/boards/vrc7p.c
    ${FCEUMM_DIR}/src/boards/vrcirq.c
    ${FCEUMM_DIR}/src/cart.c
    ${FCEUMM_DIR}/src/cheat.c
    ${FCEUMM_DIR}/src/crc32.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro_dipswitch.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/compat/compat_posix_string.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/compat/compat_snprintf.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/compat/compat_strcasestr.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/compat/compat_strl.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/compat/fopen_utf8.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/encodings/encoding_deflate.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/encodings/encoding_utf.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/file/file_path_io.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/file/file_path.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/dds/rdds.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/png/rpng.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/vorbis/rvorbis.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/vp8/rvp8.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/wav/rwav.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/formats/webp/rwebp.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/file_stream_transforms.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/file_stream.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/memory_stream.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/trans_stream_deflate.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/trans_stream_pipe.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/streams/trans_stream.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/string/stdstring.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/time/rtime.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/vfs/vfs_implementation.c
    ${FCEUMM_DIR}/src/drivers/libretro/libretro.c
    ${FCEUMM_DIR}/src/fceu-endian.c
    ${FCEUMM_DIR}/src/fceu-memory.c
    ${FCEUMM_DIR}/src/fceu.c
    ${FCEUMM_DIR}/src/fds_apu.c
    ${FCEUMM_DIR}/src/fds.c
    ${FCEUMM_DIR}/src/file.c
    ${FCEUMM_DIR}/src/filter.c
    ${FCEUMM_DIR}/src/general.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_audio.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_image.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_load.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_patch.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_ppu.c
    ${FCEUMM_DIR}/src/hdpack/hdpack_render.c
    ${FCEUMM_DIR}/src/ines.c
    ${FCEUMM_DIR}/src/input.c
    ${FCEUMM_DIR}/src/input/arkanoid.c
    ${FCEUMM_DIR}/src/input/bbox.c
    ${FCEUMM_DIR}/src/input/bworld.c
    ${FCEUMM_DIR}/src/input/cursor.c
    ${FCEUMM_DIR}/src/input/fkb.c
    ${FCEUMM_DIR}/src/input/ftrainer.c
    ${FCEUMM_DIR}/src/input/hypershot.c
    ${FCEUMM_DIR}/src/input/mahjong.c
    ${FCEUMM_DIR}/src/input/mouse.c
    ${FCEUMM_DIR}/src/input/oekakids.c
    ${FCEUMM_DIR}/src/input/pec586kb.c
    ${FCEUMM_DIR}/src/input/powerpad.c
    ${FCEUMM_DIR}/src/input/quiz.c
    ${FCEUMM_DIR}/src/input/shadow.c
    ${FCEUMM_DIR}/src/input/suborkb.c
    ${FCEUMM_DIR}/src/input/toprider.c
    ${FCEUMM_DIR}/src/input/zapper.c
    ${FCEUMM_DIR}/src/md5.c
    ${FCEUMM_DIR}/src/nsf.c
    ${FCEUMM_DIR}/src/ntsc/nes_ntsc.c
    ${FCEUMM_DIR}/src/palette.c
    ${FCEUMM_DIR}/src/ppu.c
    ${FCEUMM_DIR}/src/sound.c
    ${FCEUMM_DIR}/src/state.c
    ${FCEUMM_DIR}/src/unif.c
    ${FCEUMM_DIR}/src/video.c
    ${FCEUMM_DIR}/src/vsuni.c
    ${FCEUMM_DIR}/src/x6502.c
)

target_include_directories(fceumm_core SYSTEM PRIVATE
    ${FCEUMM_DIR}/src/drivers/libretro
    ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/include
    ${FCEUMM_DIR}/src
    ${FCEUMM_DIR}/src/input
    ${FCEUMM_DIR}/src/boards
    ${FCEUMM_DIR}/src/ntsc
)

target_compile_definitions(fceumm_core PRIVATE
    __LIBRETRO__
    PATH_MAX=1024
    FCEU_VERSION_NUMERIC=9900
    FRONTEND_SUPPORTS_RGB565
    HAVE_NTSC_FILTER
    HAVE_HDPACK
    PSS_STYLE=1
    GIT_VERSION=\"b5e3566\"
)

target_compile_options(fceumm_core PRIVATE -Wno-write-strings -Wsign-compare -Wundef -Wmissing-prototypes)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
target_link_options(fceumm_core PRIVATE
    "-Wl,--version-script=${FCEUMM_DIR}/src/drivers/libretro/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(fceumm_core
    log
    m
)

# ---------------------------------------------------------------------------
# mGBA (BSD-2-Clause): vendored under third_party/cores/mgba/, pinned to the
# upstream commit 32de792
# (https://github.com/libretro/mgba/tree/32de792). See
# third_party/cores/mgba/VENDORING.md for exactly what was vendored, why, and
# what was deliberately excluded (threading, SDL/Qt GUI, all cores besides
# GBA/GB, and non-libretro platform code).
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS) exactly.
# ---------------------------------------------------------------------------
set(MGBA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/mgba)

add_library(mgba_core SHARED
    ${MGBA_DIR}/src/arm/arm.c
    ${MGBA_DIR}/src/arm/decoder-arm.c
    ${MGBA_DIR}/src/arm/decoder-thumb.c
    ${MGBA_DIR}/src/arm/decoder.c
    ${MGBA_DIR}/src/arm/isa-arm.c
    ${MGBA_DIR}/src/arm/isa-thumb.c
    ${MGBA_DIR}/src/core/bitmap-cache.c
    ${MGBA_DIR}/src/core/cache-set.c
    ${MGBA_DIR}/src/core/cheats.c
    ${MGBA_DIR}/src/core/config.c
    ${MGBA_DIR}/src/core/core-serialize.c
    ${MGBA_DIR}/src/core/core.c
    ${MGBA_DIR}/src/core/interface.c
    ${MGBA_DIR}/src/core/lockstep.c
    ${MGBA_DIR}/src/core/log.c
    ${MGBA_DIR}/src/core/map-cache.c
    ${MGBA_DIR}/src/core/sync.c
    ${MGBA_DIR}/src/core/thread.c
    ${MGBA_DIR}/src/core/tile-cache.c
    ${MGBA_DIR}/src/core/timing.c
    ${MGBA_DIR}/src/gb/audio.c
    ${MGBA_DIR}/src/gb/cheats.c
    ${MGBA_DIR}/src/gb/core.c
    ${MGBA_DIR}/src/gb/gb.c
    ${MGBA_DIR}/src/gb/io.c
    ${MGBA_DIR}/src/gb/mbc.c
    ${MGBA_DIR}/src/gb/mbc/huc-3.c
    ${MGBA_DIR}/src/gb/mbc/licensed.c
    ${MGBA_DIR}/src/gb/mbc/mbc.c
    ${MGBA_DIR}/src/gb/mbc/pocket-cam.c
    ${MGBA_DIR}/src/gb/mbc/tama5.c
    ${MGBA_DIR}/src/gb/mbc/unlicensed.c
    ${MGBA_DIR}/src/gb/memory.c
    ${MGBA_DIR}/src/gb/overrides.c
    ${MGBA_DIR}/src/gb/renderers/cache-set.c
    ${MGBA_DIR}/src/gb/renderers/software.c
    ${MGBA_DIR}/src/gb/serialize.c
    ${MGBA_DIR}/src/gb/sio.c
    ${MGBA_DIR}/src/gb/timer.c
    ${MGBA_DIR}/src/gb/video.c
    ${MGBA_DIR}/src/gba/audio.c
    ${MGBA_DIR}/src/gba/bios.c
    ${MGBA_DIR}/src/gba/cart/ereader.c
    ${MGBA_DIR}/src/gba/cart/gpio.c
    ${MGBA_DIR}/src/gba/cart/matrix.c
    ${MGBA_DIR}/src/gba/cart/unlicensed.c
    ${MGBA_DIR}/src/gba/cart/vfame.c
    ${MGBA_DIR}/src/gba/cheats.c
    ${MGBA_DIR}/src/gba/cheats/codebreaker.c
    ${MGBA_DIR}/src/gba/cheats/gameshark.c
    ${MGBA_DIR}/src/gba/cheats/parv3.c
    ${MGBA_DIR}/src/gba/core.c
    ${MGBA_DIR}/src/gba/dma.c
    ${MGBA_DIR}/src/gba/gba.c
    ${MGBA_DIR}/src/gba/hle-bios.c
    ${MGBA_DIR}/src/gba/input.c
    ${MGBA_DIR}/src/gba/io.c
    ${MGBA_DIR}/src/gba/memory.c
    ${MGBA_DIR}/src/gba/overrides.c
    ${MGBA_DIR}/src/gba/renderers/cache-set.c
    ${MGBA_DIR}/src/gba/renderers/common.c
    ${MGBA_DIR}/src/gba/renderers/software-bg.c
    ${MGBA_DIR}/src/gba/renderers/software-mode0.c
    ${MGBA_DIR}/src/gba/renderers/software-obj.c
    ${MGBA_DIR}/src/gba/renderers/video-software.c
    ${MGBA_DIR}/src/gba/savedata.c
    ${MGBA_DIR}/src/gba/serialize.c
    ${MGBA_DIR}/src/gba/sio.c
    ${MGBA_DIR}/src/gba/sio/gbp.c
    ${MGBA_DIR}/src/gba/timer.c
    ${MGBA_DIR}/src/gba/video.c
    ${MGBA_DIR}/src/platform/libretro/libretro.c
    ${MGBA_DIR}/src/platform/libretro/memory.c
    ${MGBA_DIR}/src/sm83/isa-sm83.c
    ${MGBA_DIR}/src/sm83/sm83.c
    ${MGBA_DIR}/src/third-party/inih/ini.c
    ${MGBA_DIR}/src/util/audio-buffer.c
    ${MGBA_DIR}/src/util/audio-resampler.c
    ${MGBA_DIR}/src/util/circle-buffer.c
    ${MGBA_DIR}/src/util/configuration.c
    ${MGBA_DIR}/src/util/crc32.c
    ${MGBA_DIR}/src/util/formatting.c
    ${MGBA_DIR}/src/util/gbk-table.c
    ${MGBA_DIR}/src/util/geometry.c
    ${MGBA_DIR}/src/util/hash.c
    ${MGBA_DIR}/src/util/image.c
    ${MGBA_DIR}/src/util/interpolator.c
    ${MGBA_DIR}/src/util/md5.c
    ${MGBA_DIR}/src/util/patch-ips.c
    ${MGBA_DIR}/src/util/patch-ups.c
    ${MGBA_DIR}/src/util/patch.c
    ${MGBA_DIR}/src/util/sha1.c
    ${MGBA_DIR}/src/util/string.c
    ${MGBA_DIR}/src/util/table.c
    ${MGBA_DIR}/src/util/vector.c
    ${MGBA_DIR}/src/util/vfs.c
    ${MGBA_DIR}/src/util/vfs/vfs-fd.c
    ${MGBA_DIR}/src/util/vfs/vfs-mem.c
)

# Upstream's own libretro/jni/Android.mk builds this core with -std=c99
# specifically; match it exactly rather than relying on this project's own
# (newer) default C_STANDARD.
set_target_properties(mgba_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
)

target_include_directories(mgba_core SYSTEM PRIVATE
    ${MGBA_DIR}/src
    ${MGBA_DIR}/src/arm
    ${MGBA_DIR}/include
    ${MGBA_DIR}/src/platform/libretro
)

target_compile_definitions(mgba_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    HAVE_XLOCALE
    HAVE_STRTOF_L
    DISABLE_THREADING
    MINIMAL_CORE=2
    __LIBRETRO__
    M_CORE_GBA
    M_CORE_GB
    ENABLE_VFS
    ENABLE_DIRECTORIES
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    INLINE=inline
    COLOR_16_BIT
    RESAMPLE_LIBRARY=2
    M_PI=3.14159265358979323846
    MGBA_STANDALONE
    PATH_MAX=4096
    NDEBUG
    HAVE_LOCALTIME_R
    COLOR_5_6_5
    ENABLE_VFS_FD
    GIT_VERSION="32de792"
)

# Upstream's own libretro/jni/Android.mk only builds for arm (TARGET_ARCH=arm)
# and sets `LOCAL_ARM_MODE := arm` for that module. Match that exactly per-ABI:
# the NDK's default armeabi-v7a codegen is Thumb-2, where certain predicated
# instructions in the ARM assembly paths are illegal outside an explicit IT
# block. `-marm` forces classic 32-bit ARM encoding for this compilation unit,
# matching upstream exactly, instead of rewriting vendored upstream assembly to
# be IT-block-safe.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(mgba_core PRIVATE -marm)
    target_compile_definitions(mgba_core PRIVATE SSIZE_MAX=2147483648)
endif()

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
target_link_options(mgba_core PRIVATE
    "-Wl,--version-script=${MGBA_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(mgba_core
    log
    m
)

# ---------------------------------------------------------------------------
# Stella (Atari 2600): vendored under third_party/cores/stella/, pinned to the
# upstream release tag `7.0`, commit d55b1ae
# (https://github.com/stella-emu/stella/tree/d55b1ae). See
# third_party/cores/stella/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (desktop GUIs, tools, docs, tests).
#
# Source list mirrors upstream's own libretro Android build
# (src/os/libretro/jni/Android.mk + src/os/libretro/Makefile.common) exactly:
# pure C++ (SOURCES_C is empty), -std=c++20, upstream defines. nanojpeg.c
# is compiled inline via #include from a header wrapper (nanojpeg_lib.hxx).
# ---------------------------------------------------------------------------
set(STELLA_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/stella)

add_library(stella_core SHARED
    ${STELLA_DIR}/os/libretro/libretro.cxx
    ${STELLA_DIR}/os/libretro/FSNodeLIBRETRO.cxx
    ${STELLA_DIR}/os/libretro/StellaLIBRETRO.cxx
    ${STELLA_DIR}/common/AudioQueue.cxx
    ${STELLA_DIR}/common/AudioSettings.cxx
    ${STELLA_DIR}/common/Base.cxx
    ${STELLA_DIR}/common/Bezel.cxx
    ${STELLA_DIR}/common/DevSettingsHandler.cxx
    ${STELLA_DIR}/common/FpsMeter.cxx
    ${STELLA_DIR}/common/FSNodeZIP.cxx
    ${STELLA_DIR}/common/JoyMap.cxx
    ${STELLA_DIR}/common/KeyMap.cxx
    ${STELLA_DIR}/common/Logger.cxx
    ${STELLA_DIR}/common/MouseControl.cxx
    ${STELLA_DIR}/common/PaletteHandler.cxx
    ${STELLA_DIR}/common/PhosphorHandler.cxx
    ${STELLA_DIR}/common/PhysicalJoystick.cxx
    ${STELLA_DIR}/common/PJoystickHandler.cxx
    ${STELLA_DIR}/common/PKeyboardHandler.cxx
    ${STELLA_DIR}/common/RewindManager.cxx
    ${STELLA_DIR}/common/StaggeredLogger.cxx
    ${STELLA_DIR}/common/StateManager.cxx
    ${STELLA_DIR}/common/TimerManager.cxx
    ${STELLA_DIR}/common/VideoModeHandler.cxx
    ${STELLA_DIR}/common/tv_filters/AtariNTSC.cxx
    ${STELLA_DIR}/common/tv_filters/NTSCFilter.cxx
    ${STELLA_DIR}/common/repository/CompositeKeyValueRepository.cxx
    ${STELLA_DIR}/common/repository/CompositeKVRJsonAdapter.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryConfigfile.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryJsonFile.cxx
    ${STELLA_DIR}/common/repository/KeyValueRepositoryPropertyFile.cxx
    ${STELLA_DIR}/emucore/AtariVox.cxx
    ${STELLA_DIR}/emucore/Bankswitch.cxx
    ${STELLA_DIR}/emucore/Booster.cxx
    ${STELLA_DIR}/emucore/Cart.cxx
    ${STELLA_DIR}/emucore/CartCreator.cxx
    ${STELLA_DIR}/emucore/CartDetector.cxx
    ${STELLA_DIR}/emucore/CartEnhanced.cxx
    ${STELLA_DIR}/emucore/Cart03E0.cxx
    ${STELLA_DIR}/emucore/Cart0840.cxx
    ${STELLA_DIR}/emucore/Cart0FA0.cxx
    ${STELLA_DIR}/emucore/Cart2K.cxx
    ${STELLA_DIR}/emucore/Cart3E.cxx
    ${STELLA_DIR}/emucore/Cart3EPlus.cxx
    ${STELLA_DIR}/emucore/Cart3EX.cxx
    ${STELLA_DIR}/emucore/Cart3F.cxx
    ${STELLA_DIR}/emucore/Cart4A50.cxx
    ${STELLA_DIR}/emucore/Cart4K.cxx
    ${STELLA_DIR}/emucore/Cart4KSC.cxx
    ${STELLA_DIR}/emucore/CartAR.cxx
    ${STELLA_DIR}/emucore/CartARM.cxx
    ${STELLA_DIR}/emucore/CartBF.cxx
    ${STELLA_DIR}/emucore/CartBFSC.cxx
    ${STELLA_DIR}/emucore/CartBUS.cxx
    ${STELLA_DIR}/emucore/CartCDF.cxx
    ${STELLA_DIR}/emucore/CartCM.cxx
    ${STELLA_DIR}/emucore/CartCTY.cxx
    ${STELLA_DIR}/emucore/CartCV.cxx
    ${STELLA_DIR}/emucore/CartDF.cxx
    ${STELLA_DIR}/emucore/CartDFSC.cxx
    ${STELLA_DIR}/emucore/CartDPC.cxx
    ${STELLA_DIR}/emucore/CartDPCPlus.cxx
    ${STELLA_DIR}/emucore/CartE0.cxx
    ${STELLA_DIR}/emucore/CartE7.cxx
    ${STELLA_DIR}/emucore/CartEF.cxx
    ${STELLA_DIR}/emucore/CartEFSC.cxx
    ${STELLA_DIR}/emucore/CartELF.cxx
    ${STELLA_DIR}/emucore/CartF0.cxx
    ${STELLA_DIR}/emucore/CartF4.cxx
    ${STELLA_DIR}/emucore/CartF4SC.cxx
    ${STELLA_DIR}/emucore/CartF6.cxx
    ${STELLA_DIR}/emucore/CartF6SC.cxx
    ${STELLA_DIR}/emucore/CartF8.cxx
    ${STELLA_DIR}/emucore/CartF8SC.cxx
    ${STELLA_DIR}/emucore/CartFA2.cxx
    ${STELLA_DIR}/emucore/CartFA.cxx
    ${STELLA_DIR}/emucore/CartFC.cxx
    ${STELLA_DIR}/emucore/CartFE.cxx
    ${STELLA_DIR}/emucore/CartGL.cxx
    ${STELLA_DIR}/emucore/CartJANE.cxx
    ${STELLA_DIR}/emucore/CartMDM.cxx
    ${STELLA_DIR}/emucore/CartMVC.cxx
    ${STELLA_DIR}/emucore/CartSB.cxx
    ${STELLA_DIR}/emucore/CartTVBoy.cxx
    ${STELLA_DIR}/emucore/CartUA.cxx
    ${STELLA_DIR}/emucore/CartWD.cxx
    ${STELLA_DIR}/emucore/CartWF8.cxx
    ${STELLA_DIR}/emucore/CartX07.cxx
    ${STELLA_DIR}/emucore/CompuMate.cxx
    ${STELLA_DIR}/emucore/Console.cxx
    ${STELLA_DIR}/emucore/Control.cxx
    ${STELLA_DIR}/emucore/ControllerDetector.cxx
    ${STELLA_DIR}/emucore/CortexM0.cxx
    ${STELLA_DIR}/emucore/DispatchResult.cxx
    ${STELLA_DIR}/emucore/Driving.cxx
    ${STELLA_DIR}/emucore/EmulationTiming.cxx
    ${STELLA_DIR}/emucore/EmulationWorker.cxx
    ${STELLA_DIR}/emucore/EventHandler.cxx
    ${STELLA_DIR}/emucore/FBSurface.cxx
    ${STELLA_DIR}/emucore/FrameBuffer.cxx
    ${STELLA_DIR}/emucore/FSNode.cxx
    ${STELLA_DIR}/emucore/Genesis.cxx
    ${STELLA_DIR}/emucore/GlobalKeyHandler.cxx
    ${STELLA_DIR}/emucore/Joy2BPlus.cxx
    ${STELLA_DIR}/emucore/Joystick.cxx
    ${STELLA_DIR}/emucore/Keyboard.cxx
    ${STELLA_DIR}/emucore/KidVid.cxx
    ${STELLA_DIR}/emucore/Lightgun.cxx
    ${STELLA_DIR}/emucore/M6502.cxx
    ${STELLA_DIR}/emucore/M6532.cxx
    ${STELLA_DIR}/emucore/MD5.cxx
    ${STELLA_DIR}/emucore/MindLink.cxx
    ${STELLA_DIR}/emucore/MT24LC256.cxx
    ${STELLA_DIR}/emucore/OSystem.cxx
    ${STELLA_DIR}/emucore/Paddles.cxx
    ${STELLA_DIR}/emucore/PlusROM.cxx
    ${STELLA_DIR}/emucore/PointingDevice.cxx
    ${STELLA_DIR}/emucore/Props.cxx
    ${STELLA_DIR}/emucore/PropsSet.cxx
    ${STELLA_DIR}/emucore/QuadTari.cxx
    ${STELLA_DIR}/emucore/SaveKey.cxx
    ${STELLA_DIR}/emucore/Serializer.cxx
    ${STELLA_DIR}/emucore/Settings.cxx
    ${STELLA_DIR}/emucore/Switches.cxx
    ${STELLA_DIR}/emucore/System.cxx
    ${STELLA_DIR}/emucore/Thumbulator.cxx
    ${STELLA_DIR}/emucore/elf/BusTransactionQueue.cxx
    ${STELLA_DIR}/emucore/elf/ElfEnvironment.cxx
    ${STELLA_DIR}/emucore/elf/ElfLinker.cxx
    ${STELLA_DIR}/emucore/elf/ElfParser.cxx
    ${STELLA_DIR}/emucore/elf/ElfUtil.cxx
    ${STELLA_DIR}/emucore/elf/VcsLib.cxx
    ${STELLA_DIR}/emucore/tia/AudioChannel.cxx
    ${STELLA_DIR}/emucore/tia/Audio.cxx
    ${STELLA_DIR}/emucore/tia/Background.cxx
    ${STELLA_DIR}/emucore/tia/Ball.cxx
    ${STELLA_DIR}/emucore/tia/DrawCounterDecodes.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/AbstractFrameManager.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/FrameLayoutDetector.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/FrameManager.cxx
    ${STELLA_DIR}/emucore/tia/frame-manager/JitterEmulation.cxx
    ${STELLA_DIR}/emucore/tia/LatchedInput.cxx
    ${STELLA_DIR}/emucore/tia/Missile.cxx
    ${STELLA_DIR}/emucore/tia/AnalogReadout.cxx
    ${STELLA_DIR}/emucore/tia/Player.cxx
    ${STELLA_DIR}/emucore/tia/Playfield.cxx
    ${STELLA_DIR}/emucore/TIASurface.cxx
    ${STELLA_DIR}/emucore/tia/TIA.cxx
)

# Upstream's own libretro/jni/Android.mk builds with -std=c++20 (tag 7.0);
# master HEAD uses -std=c++23 but tag 7.0 is preferred for provenance.
set_target_properties(stella_core PROPERTIES
    CXX_STANDARD 20
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(stella_core SYSTEM PRIVATE
    ${STELLA_DIR}/os/libretro
    ${STELLA_DIR}
    ${STELLA_DIR}/emucore
    ${STELLA_DIR}/emucore/elf
    ${STELLA_DIR}/emucore/tia
    ${STELLA_DIR}/common
    ${STELLA_DIR}/common/audio
    ${STELLA_DIR}/common/tv_filters
    ${STELLA_DIR}/common/sdl_blitter
    ${STELLA_DIR}/common/repository/sqlite
    ${STELLA_DIR}/lib/json
    ${STELLA_DIR}/lib/nanojpeg
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

target_compile_definitions(stella_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    ANDROID
    __LIB_RETRO__
    HAVE_STRINGS_H
    SOUND_SUPPORT
    GIT_VERSION=\"d55b1ae\"
)

# Upstream's own libretro/jni/Application.mk sets APP_CPPFLAGS := -fexceptions;
# match that exactly. Vendored third-party source: not held to this project's
# own -Wall -Wextra (matches all prior core targets).
target_compile_options(stella_core PRIVATE -fexceptions)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Stella's internal symbols.
target_link_options(stella_core PRIVATE
    "-Wl,--version-script=${STELLA_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(stella_core
    log
    m
)

# ---------------------------------------------------------------------------
# gambatte (Game Boy / Game Boy Color): vendored under third_party/cores/gambatte/,
# pinned to upstream commit 9617436 (libretro/gambatte-libretro master HEAD).
# See third_party/cores/gambatte/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (network code, CI, docs, intl scripts).
#
# Source list mirrors upstream's libretro/jni/Android.mk + Makefile.common exactly:
# 3 C files from libretro/ (gambatte_log, blipper, cc_resampler), 15 C files from
# libretro-common/, and 27 C++ files (libretro/libretro.cpp + src/**/*.cpp).
# Mixed C/C++ target; -Wno-c++11-narrowing applies to C++ sources.
# ---------------------------------------------------------------------------
set(GAMBATTE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/gambatte)

add_library(gambatte_core SHARED
    # libretro/ — C sources (SOURCES_C from Makefile.common, minus net_serial.cpp)
    ${GAMBATTE_DIR}/libretro/gambatte_log.c
    ${GAMBATTE_DIR}/libretro/blipper.c
    ${GAMBATTE_DIR}/libretro/cc_resampler.c
    # libretro/ — C++ driver (SOURCES_CXX from Makefile.common)
    ${GAMBATTE_DIR}/libretro/libretro.cpp
    # src/ — core C++ emulation engine (SOURCES_CXX from Makefile.common)
    ${GAMBATTE_DIR}/src/bootloader.cpp
    ${GAMBATTE_DIR}/src/cpu.cpp
    ${GAMBATTE_DIR}/src/gambatte-memory.cpp
    ${GAMBATTE_DIR}/src/gambatte.cpp
    ${GAMBATTE_DIR}/src/initstate.cpp
    ${GAMBATTE_DIR}/src/interrupter.cpp
    ${GAMBATTE_DIR}/src/interruptrequester.cpp
    ${GAMBATTE_DIR}/src/mem/cartridge.cpp
    ${GAMBATTE_DIR}/src/mem/cartridge_libretro.cpp
    ${GAMBATTE_DIR}/src/mem/huc3.cpp
    ${GAMBATTE_DIR}/src/mem/memptrs.cpp
    ${GAMBATTE_DIR}/src/mem/rtc.cpp
    ${GAMBATTE_DIR}/src/sound.cpp
    ${GAMBATTE_DIR}/src/sound/channel1.cpp
    ${GAMBATTE_DIR}/src/sound/channel2.cpp
    ${GAMBATTE_DIR}/src/sound/channel3.cpp
    ${GAMBATTE_DIR}/src/sound/channel4.cpp
    ${GAMBATTE_DIR}/src/sound/duty_unit.cpp
    ${GAMBATTE_DIR}/src/sound/envelope_unit.cpp
    ${GAMBATTE_DIR}/src/sound/length_counter.cpp
    ${GAMBATTE_DIR}/src/statesaver.cpp
    ${GAMBATTE_DIR}/src/tima.cpp
    ${GAMBATTE_DIR}/src/video.cpp
    ${GAMBATTE_DIR}/src/video_libretro.cpp
    ${GAMBATTE_DIR}/src/video/ly_counter.cpp
    ${GAMBATTE_DIR}/src/video/lyc_irq.cpp
    ${GAMBATTE_DIR}/src/video/next_m0_time.cpp
    ${GAMBATTE_DIR}/src/video/ppu.cpp
    ${GAMBATTE_DIR}/src/video/sprite_mapper.cpp
    # libretro-common/ — C helper utilities (15 files)
    ${GAMBATTE_DIR}/libretro-common/compat/compat_posix_string.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_snprintf.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_strcasestr.c
    ${GAMBATTE_DIR}/libretro-common/compat/compat_strl.c
    ${GAMBATTE_DIR}/libretro-common/compat/fopen_utf8.c
    ${GAMBATTE_DIR}/libretro-common/encodings/encoding_utf.c
    ${GAMBATTE_DIR}/libretro-common/file/file_path.c
    ${GAMBATTE_DIR}/libretro-common/file/file_path_io.c
    ${GAMBATTE_DIR}/libretro-common/streams/file_stream.c
    ${GAMBATTE_DIR}/libretro-common/streams/file_stream_transforms.c
    ${GAMBATTE_DIR}/libretro-common/string/stdstring.c
    ${GAMBATTE_DIR}/libretro-common/time/rtime.c
    ${GAMBATTE_DIR}/libretro-common/vfs/vfs_implementation.c
)

set_target_properties(gambatte_core PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(gambatte_core SYSTEM PRIVATE
    ${GAMBATTE_DIR}/src
    ${GAMBATTE_DIR}/include
    ${GAMBATTE_DIR}/common
    ${GAMBATTE_DIR}/libretro
    ${GAMBATTE_DIR}/libretro-common/include
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

target_compile_definitions(gambatte_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    INLINE=inline
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    __LIBRETRO__
    VIDEO_RGB565
    CC_RESAMPLER_NO_HIGHPASS
)

# Vendored third-party source: not held to this project's own -Wall -Wextra.
# -Wno-c++11-narrowing suppresses narrowing-conversion warnings in C++ sources;
# harmless on C compilations. -O2 -DNDEBUG for release-quality bytecode.
target_compile_options(gambatte_core PRIVATE
    -Wno-c++11-narrowing
    -O2
    -DNDEBUG
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never gambatte's internal symbols.
target_link_options(gambatte_core PRIVATE
    "-Wl,--version-script=${GAMBATTE_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(gambatte_core
    log
    m
)

# ---------------------------------------------------------------------------
# beetle_pce_fast (TurboGrafx-16 / PC Engine): vendored under
# third_party/cores/beetle_pce_fast/, pinned to upstream commit b211204
# (libretro/beetle-pce-fast-libretro). Cartridge-only scope: CD, Tremor, and
# arcade card sources included; no CHD, 7zip, or Zstd. C-only target.
# ---------------------------------------------------------------------------
set(BEETLE_PCE_FAST_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/beetle_pce_fast)

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
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
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
    log
    m
)

# ---------------------------------------------------------------------------
# mednafen_ngp (Neo Geo Pocket / Neo Geo Pocket Color, "Beetle NeoPop"):
# vendored under third_party/cores/mednafen_ngp/, pinned to upstream commit
# a50d5ac288a81f2104ddf43195a4efdd15c72227 (libretro/beetle-ngp-libretro
# master HEAD). Cartridge-only scope; HLE BIOS used (no firmware required).
# Mixed C/C++ target: 32 .c + 5 .cpp compiled; 5 additional opcode-table
# .c files are #included into z80_ops.c's translation unit, not standalone.
# ---------------------------------------------------------------------------
set(MEDNAFEN_NGP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/cores/mednafen_ngp)

add_library(mednafen_ngp_core SHARED
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
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
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

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Beetle NeoPop's internal symbols.
target_link_options(mednafen_ngp_core PRIVATE
    "-Wl,--version-script=${MEDNAFEN_NGP_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(mednafen_ngp_core
    log
    m
)

# ---------------------------------------------------------------------------
# test_core: an app-owned, copyright-safe synthetic Libretro core
# (LIBRETRO_REFACTOR.md section 7.3). Built as its own shared library and
# loaded the same way a real core would be (dlopen + retro_* symbol
# resolution), so the loading path is exercised honestly before any
# third-party core is integrated.
# ---------------------------------------------------------------------------
add_library(test_core SHARED
    test_core/test_core.c
)

target_include_directories(test_core PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/libretro
)

target_link_libraries(test_core
    log
    m
)

target_compile_options(test_core PRIVATE ${ROMM_WARNING_FLAGS})

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

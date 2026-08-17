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
set(SAMEBOY_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/sameboy)

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

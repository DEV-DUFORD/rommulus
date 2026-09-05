# ---------------------------------------------------------------------------
# beetle_pce_fast (TurboGrafx-16 / PC Engine): vendored under
# third_party/cores/beetle_pce_fast/, pinned to upstream commit b211204
# (libretro/beetle-pce-fast-libretro). Cartridge-only scope: CD, Tremor, and
# arcade card sources included; no CHD, 7zip, or Zstd. C-only target.
# ---------------------------------------------------------------------------
set(BEETLE_PCE_FAST_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/beetle_pce_fast)

include(${CMAKE_CURRENT_LIST_DIR}/beetle_pce_fast-sources.cmake)

add_library(beetle_pce_fast_core SHARED ${ROMM_BEETLE_PCE_FAST_SOURCES})

target_include_directories(beetle_pce_fast_core SYSTEM PRIVATE
    ${BEETLE_PCE_FAST_DIR}
    ${BEETLE_PCE_FAST_DIR}/mednafen
    ${BEETLE_PCE_FAST_DIR}/mednafen/include
    ${BEETLE_PCE_FAST_DIR}/mednafen/hw_misc
    ${BEETLE_PCE_FAST_DIR}/libretro-common/include
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
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

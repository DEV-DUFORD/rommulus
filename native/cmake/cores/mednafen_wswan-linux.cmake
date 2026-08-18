# ---------------------------------------------------------------------------
# mednafen_wswan (WonderSwan / WonderSwan Color, "Beetle WonderSwan"):
# vendored under third_party/cores/mednafen_wswan/, pinned to upstream commit
# 4b01295838ea89e3f1355bbe4cb5cf98aa6108cd (libretro/beetle-wswan-libretro).
# Pure C target; no -fexceptions needed.
# ---------------------------------------------------------------------------

add_library(mednafen_wswan_core SHARED
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

target_include_directories(mednafen_wswan_core SYSTEM PRIVATE
    ${MEDNAFEN_WSWAN_DIR}
    ${MEDNAFEN_WSWAN_DIR}/mednafen
    ${MEDNAFEN_WSWAN_DIR}/mednafen/include
    ${MEDNAFEN_WSWAN_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(mednafen_wswan_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS.
    __LIBRETRO__
    FRONTEND_SUPPORTS_RGB565=1
    MEDNAFEN_VERSION_NUMERIC=926
    WANT_16BPP
    WANT_STEREO_SOUND
    SIZEOF_DOUBLE=8
    MPC_FIXED_POINT
    STDC_HEADERS
    __STDC_LIMIT_MACROS
    _LOW_ACCURACY_
    NDEBUG
    INLINE=inline
    GIT_VERSION=\"4b01295\"
    # ANDROID_ARM omitted: upstream defines it for armeabi-v7a only to guard
    # inline asm in v30mz.c; the pinned commit contains zero inline asm, so
    # it is vestigial. No ABI-conditional blocks are used by existing targets.
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported — never Beetle WonderSwan's internal symbols.
target_link_options(mednafen_wswan_core PRIVATE
    "-Wl,--version-script=${MEDNAFEN_WSWAN_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(mednafen_wswan_core
    m
)

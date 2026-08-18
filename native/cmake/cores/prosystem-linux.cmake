# ---------------------------------------------------------------------------
# prosystem (Atari 7800, "ProSystem"): vendored under third_party/cores/prosystem/,
# pinned to upstream commit 363b6df (libretro/prosystem-libretro master HEAD; the
# repo has no release tags). BIOS-free (optional 7800 BIOS only used if found).
# Pure C target (15 core/ units + 4 bupboop/coretone/ units + 13 libretro-common/
# units) — all 32 units are the SOURCES_C set from upstream Makefile.common.
# ---------------------------------------------------------------------------

add_library(prosystem_core SHARED
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

# Upstream's Makefile builds C with gnu11; match it exactly.
set_target_properties(prosystem_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
)

# Upstream CFLAGS include -fsigned-char; the project's own C flags do not.
# Suppresses a pre-existing upstream warning at core/ProSystem.c:272
# ("expression result unused") — not introduced by this integration.
target_compile_options(prosystem_core PRIVATE
    -fsigned-char
    -Wno-unused-value
)

target_include_directories(prosystem_core SYSTEM PRIVATE
    ${PROSYSTEM_DIR}/core
    ${PROSYSTEM_DIR}/bupboop/coretone
    ${PROSYSTEM_DIR}/bupboop
    ${PROSYSTEM_DIR}
    ${PROSYSTEM_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(prosystem_core PRIVATE
    # Matches upstream Makefile's -D__LIBRETRO__ plus Android defines.
    __LIBRETRO__
    GIT_VERSION=\"363b6df\"
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches all prior core targets). Linked with upstream's own version script
# so only the standard retro_* Libretro ABI is exported.
target_link_options(prosystem_core PRIVATE
    "-Wl,--version-script=${PROSYSTEM_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(prosystem_core
    m
)

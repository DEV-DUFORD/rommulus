# ---------------------------------------------------------------------------
# prosystem (Atari 7800, "ProSystem"): vendored under third_party/cores/prosystem/,
# pinned to upstream commit 363b6df (libretro/prosystem-libretro master HEAD; the
# repo has no release tags). BIOS-free (optional 7800 BIOS only used if found).
# Pure C target (15 core/ units + 4 bupboop/coretone/ units + 13 libretro-common/
# units) — all 32 units are the SOURCES_C set from upstream Makefile.common.
#
# Compiled source list: the shared fragment cmake/cores/prosystem-sources.cmake
# — the single source of truth consumed by the Android, Linux, and Windows
# candidate fragments so no platform can drift (identical 32-source set).
# ---------------------------------------------------------------------------
set(PROSYSTEM_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/prosystem)

include(${CMAKE_CURRENT_LIST_DIR}/prosystem-sources.cmake)

add_library(prosystem_core SHARED
    # The exact curated 32-source set from the shared fragment (identical to
    # Linux and the Windows candidate; pure C, network-free by construction).
    ${ROMM_PROSYSTEM_SOURCES}
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
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
)

target_compile_definitions(prosystem_core PRIVATE
    # Matches upstream Makefile's -D__LIBRETRO__ plus Android defines.
    ANDROID
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
    log
    m
)

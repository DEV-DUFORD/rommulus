# ---------------------------------------------------------------------------
# ProSystem (Atari 7800) — Windows x86_64 CANDIDATE variant of
# cores/prosystem-linux.cmake, for the standalone player build (native/player).
#
# STATUS: candidate only. This fragment is included by the WIN32 player build
# alongside test_core and the Gambatte/FCEUmm candidates, but windows-x86_64
# is NOT advertised anywhere: no CoreManifest.kt supportedAbis entry, no
# core-manifest.json entry, no docs row, and no player launch path references
# this DLL. CI stages it separately (cores-candidate/) and audits it (PE32+
# machine, recursive import closure, exact 22-symbol export allowlist, repeated
# load/init/deinit smoke) without promoting it. Enabling windows-x86_64 support
# is a later, separate gate (plans/WINDOWS_IMPL.md section 6.4).
#
# Differences from the POSIX fragments:
#   - PE export control via prosystem-windows.def instead of the ELF link.T
#     version script (a .def controls a PE DLL's export table; link.T is a GNU
#     ld/ELF construct with no Windows meaning). Upstream's link.T exports
#     every retro_* symbol (25 in this core); the .def enumerates EXACTLY the
#     22 Libretro exports the player's CoreLibrary resolves
#     (native/engine/src/core_library.cpp) plus any approved RomMulus save
#     extensions actually present in this core — ProSystem defines NO romm_*
#     symbol at this pin, so the allowlist is the 22 standard retro_* exports
#     only. The three upstream extras ProSystem does define
#     (retro_cheat_reset / retro_cheat_set / retro_load_game_special) stay
#     local to the DLL: CoreLibrary never resolves them.
#   - PREFIX "" for the canonical shipped name prosystem_core.dll (CMake's
#     default would emit libprosystem_core.dll; the desktop core-scan contract
#     in NativeArtifactLayout.kt uses the unprefixed name, as for test_core).
#   - No Android `log` link library and no `m`: MinGW-w64 UCRT64 folds the
#     math library into libgcc (statically linked by the toolchain contract),
#     so there is nothing extra to link.
#   - RETRO_API neutralized: the shared third_party/libretro/libretro.h
#     defaults RETRO_API to __attribute__((dllexport)) on Windows+GCC, which
#     would implicitly export every libretro.h-declared function (25 in this
#     core — including the three unused extras above). With RETRO_API empty,
#     the .def file is the SOLE authority over the PE export table: exactly
#     the 22 required exports.
#   - No ANDROID compile definition (POSIX/Windows fragment); everything else
#     matches cores/prosystem-linux.cmake (-fsigned-char from upstream's own
#     CFLAGS, -Wno-unused-value for the pre-existing upstream warning at
#     core/ProSystem.c:272).
#   - -Wl,--no-undefined: GNU ld supports it for PE targets too; it fails the
#     link if any imported symbol is unresolved, matching the POSIX fragments'
#     convention of catching missing definitions at build time.
# Saves: NONE. At this pin retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) returns
# 0 (core/libretro.c only implements RETRO_MEMORY_SYSTEM_RAM), so the player's
# checkpoint path is a no-op that writes no artifact — the E2E gate asserts
# that explicitly instead of pretending SRAM exists.
# Vendored under third_party/cores/prosystem/, pinned to upstream commit
# 363b6dfbd3e240762e022c2b4897b4fe55722be3 (libretro/prosystem-libretro master
# HEAD; the repo has no release tags). See
# third_party/cores/prosystem/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded.
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/prosystem-sources.cmake)

add_library(prosystem_core SHARED
    # The exact curated 32-source set from the shared fragment (identical to
    # Android and Linux; pure C, network-free by construction).
    ${ROMM_PROSYSTEM_SOURCES}
    # PE export table: CMake passes a .def listed in add_library() straight to
    # the MinGW linker. Everything not enumerated stays local to the DLL.
    ${CMAKE_CURRENT_LIST_DIR}/prosystem-windows.def
)

set_target_properties(prosystem_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    # Canonical output name: prosystem_core.dll, never libprosystem_core.dll.
    PREFIX ""
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
    # Matches upstream Makefile's -D__LIBRETRO__ (platform-neutral; no ANDROID
    # define on the Windows fragment).
    __LIBRETRO__
    GIT_VERSION=\"363b6df\"
    # Neutralize RETRO_API (see header): the .def file is the SOLE authority
    # over the PE export table — exactly the 22 required exports.
    RETRO_API=
)

# Upstream CFLAGS include -fsigned-char; the project's own C flags do not.
# -Wno-unused-value suppresses a pre-existing upstream warning at
# core/ProSystem.c:272 ("expression result unused") — not introduced by this
# integration. Release flags for MinGW-w64 UCRT64 (same posture as the other
# Windows candidate fragments). Vendored third-party source: not held to this
# project's own -Wall -Wextra. The toolchain contract already adds
# -static-libgcc/-static-libstdc++ to shared link flags, keeping libgcc out of
# the audited DLL closure.
target_compile_options(prosystem_core PRIVATE
    -fsigned-char
    -Wno-unused-value
    -O2
    -DNDEBUG
)

# GNU ld accepts --no-undefined for PE targets (the local MinGW UCRT64
# cross-build verifies this; the first live windows-2022 run re-verifies it,
# since this candidate has not yet been exercised by hosted CI): any
# unresolved import fails the link.
target_link_options(prosystem_core PRIVATE
    "-Wl,--no-undefined"
)

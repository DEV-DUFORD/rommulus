# ---------------------------------------------------------------------------
# mednafen_wswan (WonderSwan / WonderSwan Color, "Beetle WonderSwan") —
# Windows x86_64 CANDIDATE variant of cores/mednafen_wswan-linux.cmake, for
# the standalone player build (native/player).
#
# STATUS: candidate only. This fragment is included by the WIN32 player build
# alongside test_core and the Gambatte/FCEUmm/ProSystem candidates, but
# windows-x86_64 is NOT advertised anywhere: no CoreManifest.kt supportedAbis
# entry, no core-manifest.json entry, no docs row marked enabled, and no
# player launch path references this DLL. CI stages it separately
# (cores-candidate/) and audits it (PE32+ machine, recursive import closure,
# exact 22-symbol export allowlist, repeated load/init/deinit smoke) without
# promoting it. Enabling windows-x86_64 support is a later, separate gate
# (plans/WINDOWS_IMPL.md section 6.4).
#
# Differences from the POSIX fragments:
#   - PE export control via mednafen_wswan-windows.def instead of the ELF
#     link.T version script (a .def controls a PE DLL's export table; link.T
#     is a GNU ld/ELF construct with no Windows meaning). Upstream's link.T
#     exports every retro_* symbol (25 in this core); the .def enumerates
#     EXACTLY the 22 Libretro exports the player's CoreLibrary resolves
#     (native/engine/src/core_library.cpp) plus any approved RomMulus save
#     extensions actually present in this core — mednafen_wswan defines NO
#     romm_* symbol at this pin, so the allowlist is the 22 standard retro_*
#     exports only. The three upstream extras this core does define
#     (retro_cheat_reset / retro_cheat_set / retro_load_game_special) stay
#     local to the DLL: CoreLibrary never resolves them.
#   - PREFIX "" for the canonical shipped name mednafen_wswan_core.dll
#     (CMake's default would emit libmednafen_wswan_core.dll; the desktop
#     core-scan contract in NativeArtifactLayout.kt uses the unprefixed name,
#     as for test_core).
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
#     matches cores/mednafen_wswan-linux.cmake (pure C — no CXX standard, no
#     exception flags; v30mz.c contains zero inline assembly at this pin, so
#     upstream's armeabi-v7a-only ANDROID_ARM guard is vestigial).
#   - -Wl,--no-undefined: GNU ld supports it for PE targets too; it fails the
#     link if any imported symbol is unresolved, matching the POSIX fragments'
#     convention of catching missing definitions at build time.
# Saves: YES — unlike ProSystem this core exposes a real battery region:
# retro_get_memory_size(RETRO_MEMORY_SAVE_RAM) returns the cartridge SRAM
# size parsed from the cart header (8192 for the E2E ROM's 0x01 code), so the
# player's checkpoint path writes an 8 KiB .srm and the E2E gate asserts the
# deterministic per-frame SRAM oracle across the adoption chain.
# Vendored under third_party/cores/mednafen_wswan/, pinned to upstream commit
# 4b01295838ea89e3f1355bbe4cb5cf98aa6108cd (libretro/beetle-wswan-libretro
# master HEAD; the repo has no release tags). See
# third_party/cores/mednafen_wswan/VENDORING.md for exactly what was
# vendored, why, and what was deliberately excluded.
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/mednafen_wswan-sources.cmake)

add_library(mednafen_wswan_core SHARED
    # The exact curated 16-source set from the shared fragment (identical to
    # Android and Linux; pure C — no C++ translation units at this pin).
    ${ROMM_MEDNAFEN_WSWAN_SOURCES}
    # PE export table: CMake passes a .def listed in add_library() straight to
    # the MinGW linker. Everything not enumerated stays local to the DLL.
    ${CMAKE_CURRENT_LIST_DIR}/mednafen_wswan-windows.def
)

set_target_properties(mednafen_wswan_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    # Canonical output name: mednafen_wswan_core.dll, never
    # libmednafen_wswan_core.dll.
    PREFIX ""
)

target_include_directories(mednafen_wswan_core SYSTEM PRIVATE
    ${MEDNAFEN_WSWAN_DIR}
    ${MEDNAFEN_WSWAN_DIR}/mednafen
    ${MEDNAFEN_WSWAN_DIR}/mednafen/include
    ${MEDNAFEN_WSWAN_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(mednafen_wswan_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS (the defines are
    # platform-neutral; stdint/inttypes exist in UCRT64).
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
    # Neutralize RETRO_API (see header): the .def file is the SOLE authority
    # over the PE export table — exactly the 22 required exports.
    RETRO_API=
)

# Release flags for MinGW-w64 UCRT64 (same posture as the other Windows
# candidate fragments). Vendored third-party source: not held to this
# project's own -Wall -Wextra. The toolchain contract already adds
# -static-libgcc/-static-libstdc++ to shared link flags, keeping libgcc out
# of the audited DLL closure.
target_compile_options(mednafen_wswan_core PRIVATE
    -O2
    -DNDEBUG
)

# GNU ld accepts --no-undefined for PE targets (the local MinGW UCRT64
# cross-build verifies this; the first live windows-2022 run re-verifies it,
# since this candidate has not yet been exercised by hosted CI): any
# unresolved import fails the link.
target_link_options(mednafen_wswan_core PRIVATE
    "-Wl,--no-undefined"
)

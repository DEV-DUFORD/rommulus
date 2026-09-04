# ---------------------------------------------------------------------------
# gambatte (Game Boy / Game Boy Color) — Windows x86_64 CANDIDATE variant of
# cores/gambatte.cmake, for the standalone player build (native/player).
#
# STATUS: candidate only. This fragment is included by the WIN32 player build
# alongside test_core, but windows-x86_64 is NOT advertised anywhere: no
# CoreManifest.kt supportedAbis entry, no core-manifest.json entry, no docs
# row, and no player launch path references this DLL. CI stages it separately
# (cores-candidate/) and audits it (PE32+ machine, recursive import closure,
# exact 22-symbol export allowlist, repeated load/init/deinit smoke) without
# promoting it. Enabling windows-x86_64 support is a later, separate gate
# (plans/WINDOWS_IMPL.md section 6.4).
#
# Differences from the POSIX fragments:
#   - PE export control via gambatte-windows.def instead of the ELF link.T
#     version script (a .def controls a PE DLL's export table; link.T is a
#     GNU ld/ELF construct with no Windows meaning). The .def enumerates
#     EXACTLY the Libretro exports the player's CoreLibrary resolves
#     (native/engine/src/core_library.cpp) plus any approved RomMulus save
#     extensions actually present in this core — gambatte defines NO romm_*
#     symbol, so the allowlist is the 22 standard retro_* exports only.
#   - PREFIX "" for the canonical shipped name gambatte_core.dll (CMake's
#     default would emit libgambatte_core.dll; the desktop core-scan contract
#     in NativeArtifactLayout.kt uses the unprefixed name, as for test_core).
#   - No Android `log` link library and no `m`: MinGW-w64 UCRT64 folds the
#     math library into libgcc (statically linked by the toolchain contract),
#     so there is nothing extra to link.
#   - -Wl,--no-undefined: GNU ld supports it for PE targets too; it fails the
#     link if any imported symbol is unresolved, matching the POSIX fragments'
#     convention of catching missing definitions at build time.
# Vendored under third_party/cores/gambatte/, pinned to upstream commit
# 9617436 (libretro/gambatte-libretro master HEAD). See
# third_party/cores/gambatte/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (network code, CI, docs, intl scripts).
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/gambatte-sources.cmake)

add_library(gambatte_core SHARED
    # The exact curated 46-source set from the shared fragment (identical to
    # Android and Linux; network-disabled: net_serial.cpp is not vendored and
    # HAVE_NETWORK is not defined).
    ${ROMM_GAMBATTE_SOURCES}
    # PE export table: CMake passes a .def listed in add_library() straight to
    # the MinGW linker. Everything not enumerated stays local to the DLL.
    ${CMAKE_CURRENT_LIST_DIR}/gambatte-windows.def
)

set_target_properties(gambatte_core PROPERTIES
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
    # Canonical output name: gambatte_core.dll, never libgambatte_core.dll.
    PREFIX ""
)

target_include_directories(gambatte_core SYSTEM PRIVATE
    ${GAMBATTE_DIR}/src
    ${GAMBATTE_DIR}/include
    ${GAMBATTE_DIR}/common
    ${GAMBATTE_DIR}/libretro
    ${GAMBATTE_DIR}/libretro-common/include
    ${ROMM_LIBRETRO_INCLUDE}
)

target_compile_definitions(gambatte_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly (the
    # defines are platform-neutral; stdint/inttypes exist in UCRT64).
    INLINE=inline
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    __LIBRETRO__
    VIDEO_RGB565
    CC_RESAMPLER_NO_HIGHPASS
    # Neutralize RETRO_API (libretro.h defaults it to
    # __attribute__((dllexport)) on Windows+GCC, which would export ALL 25
    # libretro.h-declared functions — including retro_cheat_reset /
    # retro_cheat_set / retro_load_game_special, none of which the player's
    # CoreLibrary resolves). With RETRO_API empty, the .def file is the SOLE
    # authority over the PE export table: exactly the 22 required exports.
    RETRO_API=
)

# Release flags for MinGW-w64 UCRT64. Vendored third-party source: not held
# to this project's own -Wall -Wextra (same posture as the POSIX fragments).
# -Wno-c++11-narrowing suppresses narrowing-conversion warnings in C++ sources;
# harmless on C compilations. -O2 -DNDEBUG for release-quality bytecode. The
# toolchain contract already adds -static-libgcc/-static-libstdc++ to shared
# link flags, keeping libgcc/libstdc++ out of the audited DLL closure.
target_compile_options(gambatte_core PRIVATE
    -Wno-c++11-narrowing
    -O2
    -DNDEBUG
)

# GNU ld accepts --no-undefined for PE targets (verified by the local MinGW
# cross-build and the Windows CI gate): any unresolved import fails the link.
target_link_options(gambatte_core PRIVATE
    "-Wl,--no-undefined"
)

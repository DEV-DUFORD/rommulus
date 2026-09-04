# ---------------------------------------------------------------------------
# FCEUmm (NES / Family Computer) — Windows x86_64 CANDIDATE variant of
# cores/fceumm-linux.cmake, for the standalone player build (native/player).
#
# STATUS: candidate only. This fragment is included by the WIN32 player build
# alongside test_core and the Gambatte candidate, but windows-x86_64 is NOT
# advertised anywhere: no CoreManifest.kt supportedAbis entry, no
# core-manifest.json entry, no docs enablement row, and no player launch path
# references this DLL. CI stages it separately (cores-candidate/) and audits
# it (PE32+ machine, recursive import closure, exact 22-symbol export
# allowlist, repeated load/init/deinit smoke) without promoting it. Enabling
# windows-x86_64 support is a later, separate gate (plans/WINDOWS_IMPL.md
# section 6.4).
#
# Differences from the POSIX fragments:
#   - PE export control via fceumm-windows.def instead of the ELF link.T
#     version script (a .def controls a PE DLL's export table; link.T is a
#     GNU ld/ELF construct with no Windows meaning). The .def enumerates
#     EXACTLY the Libretro exports the player's CoreLibrary resolves
#     (native/engine/src/core_library.cpp) — FCEUmm defines NO romm_* save
#     extension at this pin, so the allowlist is the 22 standard retro_*
#     exports only. The three upstream extras FCEUmm does define
#     (retro_cheat_reset / retro_cheat_set / retro_load_game_special) stay
#     local to the DLL: CoreLibrary never resolves them.
#   - PREFIX "" for the canonical shipped name fceumm_core.dll (CMake's
#     default would emit libfceumm_core.dll; the desktop core-scan contract
#     in NativeArtifactLayout.kt uses the unprefixed name, as for test_core).
#   - No Android `log` link library and no `m`: MinGW-w64 UCRT64 folds the
#     math library into libgcc (statically linked by the toolchain contract),
#     so there is nothing extra to link.
#   - RETRO_API neutralized: the vendored
#     src/drivers/libretro/libretro-common/include/libretro.h defaults
#     RETRO_API to __attribute__((__dllexport__)) on Windows+GCC, which would
#     implicitly export every libretro.h-declared function (25 in this core —
#     including the three unused extras above). With RETRO_API empty, the
#     .def file is the SOLE authority over the PE export table: exactly the
#     22 required exports.
#   - Network/platform behavior matches the existing Linux build: the same
#     compile definitions as cores/fceumm-linux.cmake (FRONTEND_SUPPORTS_
#     RGB888, HAVE_NTSC_FILTER, HAVE_HDPACK, PSS_STYLE=1). No network code
#     exists in the shared 505-source set and none is added here.
#   - -Wl,--no-undefined: GNU ld supports it for PE targets too; it fails the
#     link if any imported symbol is unresolved, matching the POSIX fragments'
#     convention of catching missing definitions at build time.
# Vendored under third_party/cores/fceumm/, pinned to upstream commit
# b5e3566515c27dc66c9c20572171673126532e06 (identical to the approved
# Android/Linux pin in CoreManifest.kt). See
# third_party/cores/fceumm/VENDORING.md for exactly what was vendored.
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/fceumm-sources.cmake)

add_library(fceumm_core SHARED
    # PE export table: CMake passes a .def listed in add_library() straight to
    # the MinGW linker. Everything not enumerated stays local to the DLL.
    ${CMAKE_CURRENT_LIST_DIR}/fceumm-windows.def
)

set_target_properties(fceumm_core PROPERTIES
    # Canonical output name: fceumm_core.dll, never libfceumm_core.dll.
    PREFIX ""
    LINKER_LANGUAGE C
)

set(ROMM_FCEUMM_ARCHIVES)
list(LENGTH ROMM_FCEUMM_SOURCES ROMM_FCEUMM_SOURCE_COUNT)
set(ROMM_FCEUMM_OFFSET 0)
set(ROMM_FCEUMM_ARCHIVE_INDEX 0)
while(ROMM_FCEUMM_OFFSET LESS ROMM_FCEUMM_SOURCE_COUNT)
    list(SUBLIST ROMM_FCEUMM_SOURCES ${ROMM_FCEUMM_OFFSET} 80 ROMM_FCEUMM_CHUNK)
    set(ROMM_FCEUMM_ARCHIVE "fceumm_core_archive_${ROMM_FCEUMM_ARCHIVE_INDEX}")
    add_library(${ROMM_FCEUMM_ARCHIVE} STATIC ${ROMM_FCEUMM_CHUNK})
    target_include_directories(${ROMM_FCEUMM_ARCHIVE} SYSTEM PRIVATE
        ${FCEUMM_DIR}/src/drivers/libretro
        ${FCEUMM_DIR}/src/drivers/libretro/libretro-common/include
        ${FCEUMM_DIR}/src
        ${FCEUMM_DIR}/src/input
        ${FCEUMM_DIR}/src/boards
        ${FCEUMM_DIR}/src/ntsc
    )
    target_compile_definitions(${ROMM_FCEUMM_ARCHIVE} PRIVATE
        __LIBRETRO__
        PATH_MAX=1024
        FCEU_VERSION_NUMERIC=9900
        FRONTEND_SUPPORTS_RGB888
        HAVE_NTSC_FILTER
        HAVE_HDPACK
        PSS_STYLE=1
        GIT_VERSION=\"b5e3566\"
        RETRO_API=
    )
    target_compile_options(${ROMM_FCEUMM_ARCHIVE} PRIVATE
        -Wno-write-strings -Wsign-compare -Wundef -Wmissing-prototypes
        -O2
        -DNDEBUG
    )
    list(APPEND ROMM_FCEUMM_ARCHIVES ${ROMM_FCEUMM_ARCHIVE})
    math(EXPR ROMM_FCEUMM_OFFSET "${ROMM_FCEUMM_OFFSET} + 80")
    math(EXPR ROMM_FCEUMM_ARCHIVE_INDEX "${ROMM_FCEUMM_ARCHIVE_INDEX} + 1")
endwhile()

# GNU ld accepts --no-undefined for PE targets (verified by the local MinGW
# cross-build and the Windows CI gate): any unresolved import fails the link.
target_link_options(fceumm_core PRIVATE
    "-Wl,--no-undefined"
)

# Bounded archives keep both ar and the final DLL link below MSYS2's command
# limit. Whole-archive preserves every entry point for the PE export table.
target_link_libraries(fceumm_core PRIVATE
    "-Wl,--whole-archive"
    ${ROMM_FCEUMM_ARCHIVES}
    "-Wl,--no-whole-archive"
)

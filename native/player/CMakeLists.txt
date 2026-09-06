# native/player/CMakeLists.txt — standalone build for rommulus-player, the
# SDL3 player executable (Phase 8, LINUX_X64.md section 12; Phase 2 Win32
# CMake split, plans/WINDOWS_IMPL.md section 5.6).
#
# Mirrors native/tests/CMakeLists.txt: a standalone host project that
# compiles the platform-neutral engine sources directly into the player
# executable plus the SDL3 platform adapters. On POSIX hosts it also builds
# the synthetic test_core and all production cores (via the
# cmake/cores/*-linux.cmake fragments) so the player can load them, but
# nothing Android.
#
# Structure (Phase 2): sources, links, and flags are split into an explicit
# COMMON block (platform-neutral engine + SDL adapters), a UNIX block
# (POSIX platform implementations, GLESv2/m/dl/pthread, the Steam Deck
# variant, production cores), and a WIN32 block (Win32 platform
# implementations, ANGLE EGL/GLES import libraries, test_core only). ALL
# Win32 platform sources have landed (Phase 2 complete), so the platform
# source selection below is complete for both platforms; a WIN32 configure
# now proceeds to the SDL3/ANGLE dependency checks (the remaining external
# player dependencies) and must never fall back to POSIX sources. See the
# CMakePresets.json contract in this directory and plans/WINDOWS_IMPL.md
# sections 3.2/5.1/5.6.
#
# POSIX:
#   cmake -S native/player -B build/player
#   cmake --build build/player
#   ./build/player/rommulus_player --request <request.json>
#
# SDL3 is found via its installed CMake package (brew on macOS, vendored
# source build on Linux CI; pinned source release built for MinGW-w64 UCRT64
# on Windows).
cmake_minimum_required(VERSION 3.22.1)
project(rommulus_player LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED ON)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(ROMM_NATIVE_ROOT ${CMAKE_CURRENT_LIST_DIR}/.. ABSOLUTE)
get_filename_component(ROMM_REPO_ROOT ${ROMM_NATIVE_ROOT}/.. ABSOLUTE)

set(ROMM_ENGINE_SRC ${ROMM_NATIVE_ROOT}/engine/src)
set(ROMM_ENGINE_INCLUDE ${ROMM_NATIVE_ROOT}/engine/include)
set(ROMM_PLAYER_SRC ${CMAKE_CURRENT_SOURCE_DIR}/src)
set(ROMM_PLAYER_INCLUDE ${CMAKE_CURRENT_SOURCE_DIR}/include)
set(ROMM_LIBRETRO_INCLUDE ${ROMM_REPO_ROOT}/third_party/libretro)

# ---------------------------------------------------------------------------
# Platform-selected implementations (Phase 2, plans/WINDOWS_IMPL.md section
# 5.1): the engine's atomic file store and the player's dynamic-loading /
# path-security / session-lock / paths / process-control / health-metrics
# contracts are implemented per platform. Selection happens here, at
# configure time, and fails with a clear diagnostic when the selected
# platform tree is missing sources (all seven concerns now have both a
# posix_*.cpp and a windows_*.cpp implementation).
# ---------------------------------------------------------------------------
include(${ROMM_NATIVE_ROOT}/cmake/romm-platform-sources.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/romm-warning-flags.cmake)
# ROMM_WIN32_SOFTWARE_ONLY (default OFF globally; temporary pre-ANGLE Windows
# boundary): declares and validates the option. When ON for WIN32, the
# GLES3/ANGLE hardware-context source, import libraries, and include
# directory are excluded below in favor of a no-op implementation, and the
# player fails closed for hardware-rendering cores. Clearly ignored (warning)
# on non-Windows; Linux/ANGLE-capable behavior is unchanged.
include(${ROMM_NATIVE_ROOT}/cmake/romm-windows-software-only.cmake)

romm_select_platform_sources(ROMM_PLATFORM_SOURCES
    atomic_file_store
    dynamic_library
    path_security
    session_lock
    platform_paths
    process_control
    health_metrics
)

# Anchor for the vendored Gambatte core fragment (cmake/cores/gambatte-linux.cmake),
# which expects both GAMBATTE_DIR and ROMM_LIBRETRO_INCLUDE defined by the
# including project (the Android build derives them from ROMM_APP_CPP_DIR).
set(GAMBATTE_DIR ${ROMM_REPO_ROOT}/third_party/cores/gambatte)
set(FCEUMM_DIR ${ROMM_REPO_ROOT}/third_party/cores/fceumm)
set(PROSYSTEM_DIR ${ROMM_REPO_ROOT}/third_party/cores/prosystem)
set(HANDY_DIR ${ROMM_REPO_ROOT}/third_party/cores/handy)

# Anchors for the 7 new Linux cores (stella, mednafen_wswan, mednafen_ngp,
# snes9x, mgba, beetle_pce_fast, genesis_plus_gx). Each android fragment
# (cmake/cores/<core>.cmake) sets the corresponding variable; the Linux
# fragment (cmake/cores/<core>-linux.cmake) consumes it.
set(STELLA_DIR ${ROMM_REPO_ROOT}/third_party/cores/stella)
set(MEDNAFEN_WSWAN_DIR ${ROMM_REPO_ROOT}/third_party/cores/mednafen_wswan)
set(MEDNAFEN_NGP_DIR ${ROMM_REPO_ROOT}/third_party/cores/mednafen_ngp)
set(SNES9X_DIR ${ROMM_REPO_ROOT}/third_party/cores/snes9x)
set(MGBA_DIR ${ROMM_REPO_ROOT}/third_party/cores/mgba)
set(BEETLE_PCE_FAST_DIR ${ROMM_REPO_ROOT}/third_party/cores/beetle_pce_fast)
set(GENESIS_PLUS_GX_DIR ${ROMM_REPO_ROOT}/third_party/cores/genesis_plus_gx)
set(PCSX_REARMED_DIR ${ROMM_REPO_ROOT}/third_party/cores/pcsx_rearmed)
set(MUPEN64_DIR ${ROMM_REPO_ROOT}/third_party/cores/mupen64plus_next)

# ---------------------------------------------------------------------------
# Dependencies. SDL3 is cross-platform (found via its CMake package on every
# host). Everything else below is expressed in the UNIX/WIN32 blocks: no
# unconditional GLESv2, m, dl, or pthread.
# ---------------------------------------------------------------------------
if(WIN32)
    # Windows SDL3 comes from the pinned source release built for the same
    # MinGW-w64 UCRT64 toolchain (plans/WINDOWS_IMPL.md section 3.2); point
    # SDL3_ROOT at it. A plain REQUIRED find here would emit a generic
    # package-config error instead of explaining what to build.
    find_package(SDL3 CONFIG QUIET)
    if(NOT SDL3_FOUND)
        message(FATAL_ERROR
            "SDL3 was not found for the Windows x86_64 player build. Build the "
            "pinned SDL3 source release for MinGW-w64 UCRT64 (plans/WINDOWS_IMPL.md "
            "section 3.2) and pass -DSDL3_ROOT=<install prefix>.")
    endif()
else()
    find_package(SDL3 REQUIRED CONFIG)
    # Desktop GLESv2 is a UNIX-only player dependency (the SDL hardware
    # context uses the system EGL/GLES); Windows gets ANGLE instead.
    find_library(GLESV2_LIBRARY GLESv2 REQUIRED)
endif()

# ---------------------------------------------------------------------------
# COMMON sources: platform-neutral engine plus the SDL3 adapters and player
# protocol code. The engine tree carries no platform headers of its own and
# no file-system code — the per-platform implementations are selected above.
# ---------------------------------------------------------------------------
set(ROMM_PLAYER_COMMON_SOURCES
    # Platform-neutral engine (compiled in).
    ${ROMM_ENGINE_SRC}/core_library.cpp
    ${ROMM_ENGINE_SRC}/environment.cpp
    ${ROMM_ENGINE_SRC}/emulation_session.cpp
    ${ROMM_ENGINE_SRC}/n64_rom.cpp
    ${ROMM_ENGINE_SRC}/log.cpp
    ${ROMM_ENGINE_SRC}/dynamic_library.cpp
    ${ROMM_ENGINE_SRC}/audio_sink.cpp
    ${ROMM_ENGINE_SRC}/video_sink.cpp
    ${ROMM_ENGINE_SRC}/hardware_context.cpp
    ${ROMM_ENGINE_SRC}/pixel_format.cpp
    # Player: protocol/validation (Phase 8 Wave 1) plus the SDL3 adapters.
    # main.cpp is owned by a separate worker and is referenced here so the
    # source list is complete.
    ${ROMM_PLAYER_SRC}/protocol.cpp
    ${ROMM_PLAYER_SRC}/save_metadata.cpp
    ${ROMM_PLAYER_SRC}/validation.cpp
    # Hardware-rendering core classification (shared with the native tests;
    # also the ROMM_WIN32_SOFTWARE_ONLY fail-closed launch gate in main.cpp).
    ${ROMM_PLAYER_SRC}/hardware_core.cpp
    ${ROMM_PLAYER_SRC}/sdl_log_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_audio_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_video_sink.cpp
    ${ROMM_PLAYER_SRC}/sdl_input.cpp
    ${ROMM_PLAYER_SRC}/binding_sidecar.cpp
    ${ROMM_PLAYER_SRC}/keyboard_binding_sidecar.cpp
    ${ROMM_PLAYER_SRC}/pause_overlay.cpp
    ${ROMM_PLAYER_SRC}/main.cpp
)

# ---------------------------------------------------------------------------
# Deterministic output directories (Phase 2, plans/WINDOWS_IMPL.md section
# 5.6). These directory variables only take effect for targets created
# AFTER they are set, so this block must stay above every add_executable/
# add_library in this file. POSIX keeps the historical layout that
# packaging/build-tarball.sh and CI depend on: every artifact at the build-
# tree root (build/player/rommulus_player, build/player/lib*_core.so).
# WIN32: the player exe lands in bin/; note that CMake places a Windows
# shared library's .dll in the RUNTIME output directory as well, so core
# DLLs (test_core.dll) also land in bin/ next to the player — the Phase 4
# package layout is realized by the install() rules below, not by the build
# tree. No shell-only custom commands are used anywhere in this project.
# ---------------------------------------------------------------------------
if(WIN32)
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/bin)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR}/cores)
else()
    set(CMAKE_RUNTIME_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
endif()

# Hardware-context source selection (ROMM_WIN32_SOFTWARE_ONLY boundary): the
# ANGLE-backed SDL context is the default on every platform; a WIN32
# software-only build compiles the no-op implementation instead, which
# provides the same class with zero GL/ANGLE symbols so the executable links
# without the pinned ANGLE distribution and no unresolved API can exist.
# ROMM_PLAYER_DISABLE_HARDWARE_CONTEXT is a TEST-ONLY seam (default OFF): it
# selects the same no-op context on any host so headless E2E builds can link
# where no GL/ANGLE headers exist (e.g. macOS dev machines — SDL's software
# renderer needs no GL). Production and CI builds never set it; hardware-
# rendering cores simply cannot launch in such a build (the no-op context
# fails closed, exactly like the WIN32 software-only boundary).
option(ROMM_PLAYER_DISABLE_HARDWARE_CONTEXT
    "Test-only: select the no-op hardware render context on any host (headless E2E builds)"
    OFF)
# ROMM_PLAYER_QUALIFICATION (default OFF everywhere — production and the
# normal presets never set it): compiles the player's generic frame-limit
# qualification hook (main.cpp, ROMM_PLAYER_QUALIFICATION=1). With the hook
# compiled in, the ROMM_PLAYER_MAX_FRAMES environment variable bounds a
# launch: once the presented-frame count (the result's `frames` field —
# incremented per presented video frame in the engine's video-refresh
# trampoline) reaches the bound, the player performs a clean,
# player-initiated completed exit through the normal shutdown path
# (checkpoint written BEFORE session stop, exitKind=completed — the core
# never requested it). No new CLI surface: the bound arrives only through
# the environment, and a production build (hook not compiled) ignores the
# variable entirely. The same seam also compiles the mednafen_wswan
# 1:1 presented-frame pin (emulation_session.cpp): qualification builds set
# the core's wswan_60hz_mode to "disabled" so one presented frame == one
# Emulate() and the deterministic SRAM oracle (player_e2e.py) holds on every
# host; ordinary builds keep the core's upstream default ("enabled")
# untouched. Enabled only in the Windows software-only candidate CI build
# (windows-x64.yml passes -DROMM_PLAYER_QUALIFICATION=ON) so the Gambatte
# and mednafen_wswan candidate E2Es can drive bounded deterministic runs.
option(ROMM_PLAYER_QUALIFICATION
    "Qualification-only: compile the ROMM_PLAYER_MAX_FRAMES presented-frame bound hook and pin mednafen_wswan's wswan_60hz_mode to disabled for 1:1 presented frames (CI candidate builds only)"
    OFF)
if(WIN32 AND ROMM_WIN32_SOFTWARE_ONLY)
    set(ROMM_HARDWARE_CONTEXT_SOURCE
        ${ROMM_PLAYER_SRC}/sdl_hardware_context_software_only.cpp)
elseif(ROMM_PLAYER_DISABLE_HARDWARE_CONTEXT)
    message(WARNING
        "ROMM_PLAYER_DISABLE_HARDWARE_CONTEXT=ON: building the no-op hardware "
        "render context (test/headless builds only — hardware-rendering cores "
        "will fail closed in this binary).")
    set(ROMM_HARDWARE_CONTEXT_SOURCE
        ${ROMM_PLAYER_SRC}/sdl_hardware_context_software_only.cpp)
else()
    set(ROMM_HARDWARE_CONTEXT_SOURCE ${ROMM_PLAYER_SRC}/sdl_hardware_context.cpp)
endif()

add_executable(rommulus_player
    ${ROMM_PLAYER_COMMON_SOURCES}
    # Per-platform implementations of the engine/player platform contracts
    # (Phase 2, plans/WINDOWS_IMPL.md section 5.1): atomic file store,
    # dynamic loading, path security, session lock, home/XDG roots +
    # executable path, signals/watchdog/re-exec (POSIX) or their Win32
    # equivalents, and health metrics.
    ${ROMM_PLATFORM_SOURCES}
    ${ROMM_HARDWARE_CONTEXT_SOURCE}
)

# ---------------------------------------------------------------------------
# Deterministic output names (Phase 2, plans/WINDOWS_IMPL.md section 5.6):
# Windows ships rommulus-player.exe; POSIX keeps the historical
# rommulus_player name that packaging/build-tarball.sh and CI depend on.
# test_core is a plain C shared library: libtest_core.so on POSIX (CMake's
# default prefix). On Windows CMake would emit libtest_core.dll — the
# canonical shipped name is test_core.dll (desktop core-scan contract,
# NativeArtifactLayout.kt), so PREFIX "" is set explicitly below in the
# WIN32 block next to the target definition.
# ---------------------------------------------------------------------------
if(WIN32)
    set_target_properties(rommulus_player PROPERTIES
        OUTPUT_NAME "rommulus-player"
    )
endif()

# Steam Deck variant (UNIX-only for now; a Windows deck build is out of
# scope until the Win32 foundation lands).
if(NOT WIN32)
    add_executable(rommulus_player_deck
        ${ROMM_PLAYER_COMMON_SOURCES}
        ${ROMM_PLATFORM_SOURCES}
        ${ROMM_PLAYER_SRC}/sdl_hardware_context_legacy.cpp
    )
    set_target_properties(rommulus_player_deck PROPERTIES
        OUTPUT_NAME "rommulus-player-deck"
    )
endif()

# ${ROMM_REPO_ROOT}/third_party is required by protocol.cpp/validation.cpp
# (vendored header-only nlohmann/json, included as <nlohmann/json.hpp>).
target_include_directories(rommulus_player PRIVATE
    ${ROMM_ENGINE_INCLUDE}
    ${ROMM_ENGINE_SRC}
    ${ROMM_PLAYER_INCLUDE}
    ${ROMM_LIBRETRO_INCLUDE}
    ${ROMM_REPO_ROOT}/third_party
)
if(WIN32)
    # The Win32 platform sources include the tree's pure helpers (strict
    # UTF-8/UTF-16 boundary conversion).
    target_include_directories(rommulus_player PRIVATE
        ${ROMM_NATIVE_ROOT}/platform/windows/include
    )
endif()
if(NOT WIN32)
    target_include_directories(rommulus_player_deck PRIVATE
        ${ROMM_ENGINE_INCLUDE}
        ${ROMM_ENGINE_SRC}
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_LIBRETRO_INCLUDE}
        ${ROMM_REPO_ROOT}/third_party
    )
endif()
target_compile_definitions(rommulus_player PRIVATE
    ROMM_PAUSE_FONT_PATH="${MUPEN64_DIR}/mupen64plus-core/data/font.ttf"
    ROMM_CONTROLLER_ART_PATH="${ROMM_REPO_ROOT}/assets/controllers"
)
if(WIN32 AND ROMM_WIN32_SOFTWARE_ONLY)
    # The fail-closed boundary, visible to main.cpp (launch gate for known
    # hardware-rendering cores) and environment.cpp (SET_HW_RENDER /
    # GET_HW_RENDER_INTERFACE refusal). Never set outside WIN32+ON: the CMake
    # module above forces the option OFF with a warning elsewhere.
    target_compile_definitions(rommulus_player PRIVATE ROMM_WIN32_SOFTWARE_ONLY=1)
endif()
if(ROMM_PLAYER_QUALIFICATION)
    # Qualification-only presented-frame bound (see the option above):
    # main.cpp reads ROMM_PLAYER_MAX_FRAMES and exits cleanly at the bound,
    # and emulation_session.cpp pins mednafen_wswan's wswan_60hz_mode to
    # "disabled" for 1:1 presented/core frames. Deliberately scoped to
    # rommulus_player only — the deck variant and every core target stay
    # hook-free. (emulation_session.cpp is compiled into this executable, so
    # the definition reaches it here; the Android romm_libretro_host library
    # never defines it.)
    target_compile_definitions(rommulus_player PRIVATE ROMM_PLAYER_QUALIFICATION=1)
endif()
if(NOT WIN32)
    target_compile_definitions(rommulus_player_deck PRIVATE
        ROMM_PAUSE_FONT_PATH="${MUPEN64_DIR}/mupen64plus-core/data/font.ttf"
        ROMM_CONTROLLER_ART_PATH="${ROMM_REPO_ROOT}/assets/controllers"
        ROMM_STEAM_DECK_PLAYER=1
        ROMM_FORCE_DEFAULT_FRAMEBUFFER=1
    )
endif()

# ---------------------------------------------------------------------------
# Platform link blocks: no unconditional GLESv2/m/dl/pthread (Phase 2).
# ---------------------------------------------------------------------------
if(WIN32)
    if(NOT ROMM_WIN32_SOFTWARE_ONLY)
        # sdl_hardware_context.cpp renders through the pinned ANGLE EGL/GLES
        # import libraries (plans/WINDOWS_IMPL.md section 5.6); the Windows
        # toolchain file / preset provides them once the pinned ANGLE build
        # lands. Fail closed rather than silently linking a system GL: all
        # three variables are required at configure time, and the include
        # directory must exist so <EGL/egl.h>/<GLES3/gl3.h> resolve from the
        # pinned ANGLE distribution, never from a system SDK.
        if(NOT ROMM_ANGLE_EGL_LIBRARY OR NOT ROMM_ANGLE_GLESV2_LIBRARY
                OR NOT ROMM_ANGLE_INCLUDE_DIR)
            message(FATAL_ERROR
                "ROMM_ANGLE_EGL_LIBRARY, ROMM_ANGLE_GLESV2_LIBRARY and "
                "ROMM_ANGLE_INCLUDE_DIR must be set to the pinned ANGLE EGL/GLES "
                "import libraries and their include directory for the Windows "
                "player build (plans/WINDOWS_IMPL.md section 5.6).")
        endif()
        if(NOT IS_DIRECTORY ${ROMM_ANGLE_INCLUDE_DIR})
            message(FATAL_ERROR
                "ROMM_ANGLE_INCLUDE_DIR does not exist: ${ROMM_ANGLE_INCLUDE_DIR}. "
                "Point it at the include directory of the pinned ANGLE distribution "
                "(plans/WINDOWS_IMPL.md section 5.6).")
        endif()
        if(NOT EXISTS "${ROMM_ANGLE_EGL_LIBRARY}"
                OR NOT EXISTS "${ROMM_ANGLE_GLESV2_LIBRARY}")
            message(FATAL_ERROR
                "The pinned ANGLE import libraries do not exist: "
                "EGL=${ROMM_ANGLE_EGL_LIBRARY}, "
                "GLESv2=${ROMM_ANGLE_GLESV2_LIBRARY}.")
        endif()
        target_link_libraries(rommulus_player PRIVATE
            SDL3::SDL3
            ${ROMM_ANGLE_EGL_LIBRARY}
            ${ROMM_ANGLE_GLESV2_LIBRARY}
            advapi32
            shell32
            ole32
            psapi
        )
        target_include_directories(rommulus_player PRIVATE ${ROMM_ANGLE_INCLUDE_DIR})
    else()
        # ROMM_WIN32_SOFTWARE_ONLY: the no-op hardware context (selected
        # above) uses no GL, so the ANGLE import libraries and include
        # directory are EXCLUDED — SDL3 (video/audio/input) plus the Win32
        # platform sources' import libraries only.
        target_link_libraries(rommulus_player PRIVATE
            SDL3::SDL3
            advapi32
            shell32
            ole32
            psapi
        )
    endif()
    # advapi32 (GetSecurityInfo/EqualSid/GetAce/ConvertStringSidToSidW in
    # windows_path_security.cpp), shell32 (SHGetKnownFolderPath in
    # windows_platform_paths.cpp), ole32 (CoTaskMemFree — the only legal
    # release of the known-folder path), and psapi (GetProcessMemoryInfo in
    # windows_health_metrics.cpp) are the Win32 platform sources' non-default
    # import libraries; kernel32/user32 come from the toolchain's default set
    # (console control, the TerminateProcess watchdog thread, CreateProcessW).
else()
    # Current UNIX dependencies, expressed explicitly: SDL3 (shared), desktop
    # GLESv2 for the main player only, and m/dl/pthread.
    target_link_libraries(rommulus_player PRIVATE
        SDL3::SDL3 ${GLESV2_LIBRARY} m dl pthread
    )
    target_link_libraries(rommulus_player_deck PRIVATE
        SDL3::SDL3 m dl pthread
    )
endif()

romm_apply_warning_flags(rommulus_player)
if(NOT WIN32)
    romm_apply_warning_flags(rommulus_player_deck)
endif()

# Hosted graphics-spike executable. This is intentionally not a Libretro core
# port: it proves the exact SDL3 -> EGL -> pinned ANGLE -> GLES 3.0 path and
# the framebuffer/shader operations used by SdlHardwareContext. Tier 2/3
# candidates remain separate and disabled until their own gates exist.
if(WIN32 AND NOT ROMM_WIN32_SOFTWARE_ONLY)
    add_executable(angle_gles3_smoke
        ${CMAKE_CURRENT_SOURCE_DIR}/tests/angle_gles3_smoke.cpp
    )
    target_include_directories(angle_gles3_smoke PRIVATE ${ROMM_ANGLE_INCLUDE_DIR})
    target_link_libraries(angle_gles3_smoke PRIVATE
        SDL3::SDL3
        ${ROMM_ANGLE_EGL_LIBRARY}
        ${ROMM_ANGLE_GLESV2_LIBRARY}
    )
    romm_apply_warning_flags(angle_gles3_smoke)
endif()

# ---------------------------------------------------------------------------
# test_core: the synthetic core the player loads (Phase 8). A minimal
# software-rendering Libretro core used for end-to-end player verification.
# Built on every platform; on WIN32 it is the only core built until the
# per-core Windows fragments land (Phase 3). Plain C, so no m/log anywhere:
# `m` is a UNIX-only link dependency here.
# ---------------------------------------------------------------------------
add_library(test_core SHARED
    ${ROMM_REPO_ROOT}/app/src/main/cpp/test_core/test_core.c
)
target_include_directories(test_core PRIVATE ${ROMM_LIBRETRO_INCLUDE})
# CI/E2E-only deterministic shutdown hook (test_core.c, ROMM_TEST_CORE_MAX_FRAMES):
# active only when the environment variable is set, so it changes no behavior
# for any non-CI launch. Defined HERE — in the player's test-core build — and
# deliberately NOT in the Android app build (native/cmake/cores/test_core.cmake),
# which keeps the shipped app's copy of the core byte-for-byte hook-free.
target_compile_definitions(test_core PRIVATE ROMM_TEST_CORE_MAX_FRAMES=1)
if(NOT WIN32)
    target_link_libraries(test_core PRIVATE m)
else()
    # CMake's default shared-library prefix would emit libtest_core.dll; the
    # canonical Windows name is test_core.dll (desktop core-scan contract in
    # NativeArtifactLayout.kt), so strip the "lib" prefix explicitly.
    set_target_properties(test_core PROPERTIES PREFIX "")
endif()
romm_apply_warning_flags(test_core)

# ---------------------------------------------------------------------------
# Candidate production cores (WIN32 only — NOT advertised): Windows x86_64
# builds of vendored Libretro cores, built alongside test_core as candidates
# for the per-core gate (plans/WINDOWS_IMPL.md section 6.4). "Candidate" is
# load-bearing: windows-x86_64 appears in NO shared manifest (CoreManifest.kt
# supportedAbis, packaging/share/rommulus/core-manifest.json,
# docs/linux-support-manifest.md — the validator
# packaging/validate-linux-targets.py asserts the no-Windows invariant) and
# no player launch path references these DLLs. CI stages them separately
# under cores-candidate/ and audits them (PE32+ machine, recursive import
# closure, exact export allowlist, repeated load/init/deinit smoke) without
# promoting them. Each core gets its own <core>-windows.cmake fragment; the
# *-linux.cmake fragments below stay POSIX and are never included on WIN32.
# ---------------------------------------------------------------------------
if(WIN32)
    # Gambatte (Game Boy / GBC): first candidate production core for Win32.
    # Same vendored 46-source set as Android/Linux (shared fragment), PE
    # export table via .def, canonical output name gambatte_core.dll.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/gambatte-windows.cmake)
    # FCEUmm (NES / Family Computer): second candidate production core for
    # Win32. Same vendored 505-source set as Android/Linux (shared fragment),
    # PE export table via .def, canonical output name fceumm_core.dll.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/fceumm-windows.cmake)
    # ProSystem (Atari 7800): third candidate production core for Win32.
    # Same vendored 32-source set as Android/Linux (shared fragment), PE
    # export table via .def, canonical output name prosystem_core.dll.
    # Unlike the other two candidates this core exposes NO save RAM region:
    # the E2E gate asserts the no-persistent-save invariants instead.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/prosystem-windows.cmake)
    # mednafen_wswan (WonderSwan / WonderSwan Color): fourth candidate
    # production core for Win32. Same vendored 16-source set as Android/Linux
    # (shared fragment), PE export table via .def, canonical output name
    # mednafen_wswan_core.dll. Like Gambatte/FCEUmm this core exposes a real
    # battery region (8 KiB SRAM for the E2E ROM's cart header code 0x01):
    # the E2E gate asserts the deterministic per-frame SRAM oracle across the
    # adoption chain.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_wswan-windows.cmake)
    # Beetle PCE Fast (PC Engine): fifth candidate, with a shared 60-source
    # inventory and standard 2 KiB battery-backed BRAM.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/beetle_pce_fast-windows.cmake)
    # Genesis Plus GX (Genesis / Mega Drive): sixth candidate. It reuses the
    # guarded 115-source Android/Linux inventory and exports the normal
    # Libretro ABI plus the existing four romm_* save-image extensions.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/genesis_plus_gx-windows.cmake)
    # mGBA (GBA): seventh candidate. Reuses the guarded 98-source inventory
    # and the upstream MinGW-compatible source/define surface.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mgba-windows.cmake)
    # Snes9x (SNES): eighth candidate. Reuses the guarded upstream 54-source
    # C++14 inventory and standard Libretro battery SRAM.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/snes9x-windows.cmake)
    # Stella (Atari 2600): ninth candidate. Reuses the guarded upstream 7.0
    # 148-source C++20 inventory. It exposes SYSTEM_RAM only, so its E2E gate
    # asserts no persistent-save artifacts rather than an SRAM lifecycle.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/stella-windows.cmake)
    # PCSX-ReARMed (PlayStation): tenth candidate. Uses the built-in
    # interpreter and software SSSE3 GPU, with no JIT executable memory or
    # physical CD-ROM access.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/pcsx_rearmed-windows.cmake)
    set(ROMM_WIN32_CANDIDATE_CORE_TARGETS
        gambatte_core
        fceumm_core
        prosystem_core
        mednafen_wswan_core
        beetle_pce_fast_core
        genesis_plus_gx_core
        mgba_core
        snes9x_core
        stella_core
        pcsx_rearmed_core
    )
endif()

# ---------------------------------------------------------------------------
# Production cores (UNIX-only): the Linux x86_64 builds of the vendored
# Libretro cores, one fragment each. The Win32 equivalents are separate
# <core>-windows.cmake fragments created in Phase 3 — these *-linux.cmake
# fragments stay POSIX and are never included on WIN32.
# ---------------------------------------------------------------------------
if(NOT WIN32)
    # Gambatte (Game Boy / GBC): Linux x86_64 build of the same vendored
    # sources the Android build compiles via cmake/cores/gambatte.cmake. The
    # fragment is identical except for the dropped Android-only `log` link
    # library.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/gambatte-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/fceumm-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/prosystem-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/handy-linux.cmake)
    # 7 new Linux cores: stella, mednafen_wswan, mednafen_ngp, snes9x, mgba,
    # beetle_pce_fast, genesis_plus_gx.
    include(${ROMM_NATIVE_ROOT}/cmake/cores/stella-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_wswan-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mednafen_ngp-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/snes9x-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mgba-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/beetle_pce_fast-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/genesis_plus_gx-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/pcsx_rearmed-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/mupen64plus_next-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/dolphin-linux.cmake)
    include(${ROMM_NATIVE_ROOT}/cmake/cores/lrps2-linux.cmake)

    set(ROMM_LINUX_CORE_TARGETS
        gambatte_core
        fceumm_core
        prosystem_core
        handy_core
        stella_core
        mednafen_wswan_core
        mednafen_ngp_core
        snes9x_core
        mgba_core
        beetle_pce_fast_core
        genesis_plus_gx_core
        pcsx_rearmed_core
        mupen64plus_next_core
    )
endif()

# ---------------------------------------------------------------------------
# Install rules (Phase 2, plans/WINDOWS_IMPL.md section 5.6). These cover
# CMake targets only: the player executables, test_core, and the in-tree
# core libraries listed in ROMM_LINUX_CORE_TARGETS. dolphin and lrps2 are
# ExternalProject byproducts (cmake/cores/dolphin-linux.cmake and
# cmake/cores/lrps2-linux.cmake), NOT CMake targets — install() does not
# cover them; packaging/build-tarball.sh stages their .so files directly
# from the build-tree root.
# ---------------------------------------------------------------------------
install(TARGETS rommulus_player RUNTIME DESTINATION bin)
if(NOT WIN32)
    install(TARGETS rommulus_player_deck RUNTIME DESTINATION bin)
    install(TARGETS ${ROMM_LINUX_CORE_TARGETS} LIBRARY DESTINATION lib/cores)
endif()
install(TARGETS test_core LIBRARY DESTINATION lib/cores)
if(WIN32)
    # Candidate cores install to their own destination so a future Windows
    # package step can never confuse them with enabled cores (packaging is
    # not wired for Windows yet — this rule only fixes the intended layout).
    # PE DLLs are CMake RUNTIME artifacts; LIBRARY alone would silently send
    # them to the default bin destination.
    install(TARGETS ${ROMM_WIN32_CANDIDATE_CORE_TARGETS}
        RUNTIME DESTINATION lib/cores-candidate
        LIBRARY DESTINATION lib/cores-candidate
    )
endif()

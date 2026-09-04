# native/tests/CMakeLists.txt — standalone host CTest project for the
# platform-neutral engine (Phase 7 Wave 9b, LINUX_X64.md section 14,
# Phase 7 item 11).
#
# Builds the neutral engine sources under test plus one executable per test
# file and registers each with CTest. It deliberately does NOT build the
# vendored cores, the Android platform adapters, or jni_bridge.cpp — the
# engine units under test are platform-neutral and compile with the host
# compiler (macOS clang today, Linux CI later). No external test framework:
# each test executable uses the minimal CHECK-macro harness in romm_test.h
# and returns nonzero on failure, which CTest reports as a failure.
#
# Platform selection (Phase 2, plans/WINDOWS_IMPL.md section 5.1) mirrors
# the standalone player build: the atomic file store and path-security
# contracts are implemented per platform and selected at configure time.
# On POSIX hosts this compiles the native/platform/posix/src/ sources; on
# WIN32 it requires the matching native/platform/windows/src/ sources. ALL
# Win32 platform sources have now landed (Phase 2 complete), so a WIN32
# configure of THIS test project succeeds for the selected concerns and the
# standalone PLAYER build's platform source selection is complete too — its
# WIN32 configure proceeds to the SDL3/ANGLE dependency checks (the remaining
# external player dependencies). Neither project ever falls back to POSIX
# sources.
#
# Run:
#   cmake -S native/tests -B build/engine-tests
#   cmake --build build/engine-tests
#   ctest --test-dir build/engine-tests --output-on-failure
cmake_minimum_required(VERSION 3.22.1)
project(romm_engine_tests LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

enable_testing()

get_filename_component(ROMM_NATIVE_ROOT ${CMAKE_CURRENT_LIST_DIR}/.. ABSOLUTE)
get_filename_component(ROMM_REPO_ROOT ${ROMM_NATIVE_ROOT}/.. ABSOLUTE)

set(ROMM_ENGINE_SRC ${ROMM_NATIVE_ROOT}/engine/src)
set(ROMM_ENGINE_INCLUDE ${ROMM_NATIVE_ROOT}/engine/include)
set(ROMM_PLAYER_SRC ${ROMM_NATIVE_ROOT}/player/src)
set(ROMM_PLAYER_INCLUDE ${ROMM_NATIVE_ROOT}/player/include)

# Platform-selected implementations (Phase 2, plans/WINDOWS_IMPL.md section
# 5.1): mirrors the standalone player's selection — this host test suite
# compiles the per-platform sources for the atomic file store contract and
# the player path-security contract. Both Win32 implementations have landed
# (windows_atomic_file_store.cpp, windows_path_security.cpp), so a WIN32
# configure of this project now succeeds; romm_select_platform_sources()
# still fails fast with a clear diagnostic if either is ever removed.
include(${ROMM_NATIVE_ROOT}/cmake/romm-platform-sources.cmake)
include(${ROMM_NATIVE_ROOT}/cmake/romm-warning-flags.cmake)
# ROMM_WIN32_SOFTWARE_ONLY (default OFF; temporary pre-ANGLE Windows
# boundary): declared and validated here so passing it to the test project is
# clearly ignored (warning) on non-Windows hosts. The EnvironmentHandler
# refusal tests below pin the compile-definition behavior on every host.
include(${ROMM_NATIVE_ROOT}/cmake/romm-windows-software-only.cmake)

romm_select_platform_sources(ROMM_ATOMIC_FILE_STORE_SRC atomic_file_store)
romm_select_platform_sources(ROMM_PATH_SECURITY_SRC path_security)

# The Win32 platform tree's pure helpers (strict UTF-8/UTF-16 boundary
# conversion, native/platform/windows/include) are header-only and
# platform-neutral; tests include them directly so the strict decoding is
# exercised on every host, not only in Windows CI.
set(ROMM_WINDOWS_PLATFORM_INCLUDE ${ROMM_NATIVE_ROOT}/platform/windows/include)

# The neutral engine sources under test. The header-only units
# (audio_ring_buffer.h, frame_scheduler.h, input_state.h, pixel_format.h)
# are consumed directly by the test executables through the include paths
# below. log.cpp/dynamic_library.cpp provide the registries the other units
# link against; core_library.cpp is exercised through a fake
# romm::dynamiclib::DynamicLibrary (the engine's default factory returns
# nullptr, so a real core .so is never loaded here).
add_library(romm_engine_under_test STATIC
    # Per-platform implementation of the atomic file store contract
    # (Phase 2); selected here exactly as in the player build.
    ${ROMM_ATOMIC_FILE_STORE_SRC}
    ${ROMM_ENGINE_SRC}/core_library.cpp
    ${ROMM_ENGINE_SRC}/dynamic_library.cpp
    ${ROMM_ENGINE_SRC}/log.cpp
    ${ROMM_ENGINE_SRC}/n64_rom.cpp
    ${ROMM_ENGINE_SRC}/pixel_format.cpp
)
target_include_directories(romm_engine_under_test PUBLIC
    ${ROMM_ENGINE_SRC}
    ${ROMM_ENGINE_INCLUDE}
    ${ROMM_REPO_ROOT}/third_party/libretro
)
if(WIN32)
    # windows_atomic_file_store.cpp (selected above) includes the Win32
    # platform's pure conversion header.
    target_include_directories(romm_engine_under_test PUBLIC
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    # windows_atomic_file_store.cpp's security step (section 5.5 step 5)
    # calls advapi32 (GetSecurityInfo/SetFileSecurityW/SDDL converters);
    # PUBLIC so the test executables linking this library get the import
    # library too.
    target_link_libraries(romm_engine_under_test PUBLIC advapi32)
endif()
romm_apply_warning_flags(romm_engine_under_test)

# The platform-neutral player protocol (Phase 8 Wave 1, LINUX_X64.md
# section 12): versioned request/result file schema plus trusted-root
# path validation. JSON parsing uses the vendored header-only
# nlohmann/json (MIT, third_party/nlohmann/).
add_library(romm_player_under_test STATIC
    ${ROMM_PLAYER_SRC}/protocol.cpp
    ${ROMM_PLAYER_SRC}/save_metadata.cpp
    ${ROMM_PLAYER_SRC}/validation.cpp
    # Hardware-rendering core classification (shared with the player's launch
    # gate; exercised by test_hardware_core_classification).
    ${ROMM_PLAYER_SRC}/hardware_core.cpp
    # Per-platform implementation of the path-security contract that
    # validation.cpp's policy calls into (Phase 2).
    ${ROMM_PATH_SECURITY_SRC}
    # The binding sidecar (Phase 9): versioned JSON persistence of the
    # RetroPad BindingTable. It links against romm_engine_under_test for
    # romm::atomicWriteFile (write-temp, fsync, rename).
    ${ROMM_PLAYER_SRC}/binding_sidecar.cpp
    ${ROMM_PLAYER_SRC}/keyboard_binding_sidecar.cpp
)
target_include_directories(romm_player_under_test PUBLIC
    ${ROMM_ENGINE_SRC}  # atomic_file_store.h (used by binding_sidecar.cpp)
    ${ROMM_PLAYER_INCLUDE}
    ${ROMM_REPO_ROOT}/third_party
)
if(WIN32)
    # windows_path_security.cpp (selected above) includes the Win32
    # platform's pure helpers (path_rules.h, utf16.h).
    target_include_directories(romm_player_under_test PUBLIC
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
endif()
romm_apply_warning_flags(romm_player_under_test)

function(romm_add_engine_test name)
    add_executable(${name} ${name}.cpp)
    target_link_libraries(${name} PRIVATE romm_engine_under_test)
    romm_apply_warning_flags(${name})
    add_test(NAME ${name} COMMAND ${name})
endfunction()

romm_add_engine_test(test_audio_ring_buffer)
romm_add_engine_test(test_frame_scheduler)
romm_add_engine_test(test_input_state)
romm_add_engine_test(test_atomic_file_store)
# Strict UTF-8/UTF-16 boundary conversion (platform-neutral; guards the
# Win32 path crossing on every host).
romm_add_engine_test(test_utf16_conversion)
target_include_directories(test_utf16_conversion PRIVATE
    ${ROMM_WINDOWS_PLATFORM_INCLUDE}
)
# Pure Win32 path normalization/containment rules (the fakeable seam of the
# path-security slice; runs on every host, like the UTF-16 test above).
romm_add_engine_test(test_windows_path_rules)
target_include_directories(test_windows_path_rules PRIVATE
    ${ROMM_WINDOWS_PLATFORM_INCLUDE}
)
# The GetFinalPathNameByHandleW buffer-growth contract (final_path.h, the
# fakeable seam of the handle-based final-path resolution): fake-fetch
# coverage on every host (like the rules/UTF-16 tests above), plus a real
# long-path run against the live API in the test's WIN32 block.
romm_add_engine_test(test_windows_final_path)
target_include_directories(test_windows_final_path PRIVATE
    ${ROMM_WINDOWS_PLATFORM_INCLUDE}
)
if(WIN32)
    target_link_libraries(test_windows_final_path PRIVATE kernel32)
endif()
romm_add_engine_test(test_pixel_format)
romm_add_engine_test(test_core_library)
romm_add_engine_test(test_n64_rom)

function(romm_add_player_test name)
    add_executable(${name} ${name}.cpp)
    # romm_engine_under_test supplies romm::atomicWriteFile, which the
    # binding sidecar (part of romm_player_under_test) links against.
    target_link_libraries(${name} PRIVATE romm_player_under_test romm_engine_under_test)
    romm_apply_warning_flags(${name})
    add_test(NAME ${name} COMMAND ${name})
endfunction()

romm_add_player_test(test_player_protocol)
romm_add_player_test(test_save_metadata)
# test_player_validation is POSIX-shaped by construction: it exercises
# mkdtemp/mkdir/symlink/chmod semantics (symlinked request files, world-
# writable mode bits, /etc/hosts escapes) that have no 1:1 Win32 equivalent
# (junctions instead of symlinks, DACLs instead of mode bits). It stays in
# the UNIX suite unchanged; the Win32 counterpart of its security concerns
# is test_windows_path_security (WIN32 block below), which drives the same
# shared validation policy through the real NTFS owner/DACL classification.
# Gating here keeps the WIN32 CTest suite green without losing UNIX coverage.
if(NOT WIN32)
    romm_add_player_test(test_player_validation)
endif()
romm_add_player_test(test_pause_menu)
# The binding editor's SDL-free units (Phase 9): the capture state machine
# (binding_capture.h, ported Android ControllerBindingCaptureCoordinator
# semantics) and the RetroPad BindingTable (binding_table.h). Both are
# header-only, so they compile straight into the test executables through
# the include paths above — no SDL involved.
romm_add_player_test(test_controller_capture)
romm_add_player_test(test_controller_slot_match)
romm_add_player_test(test_binding_table)
romm_add_player_test(test_keyboard_binding_table)
romm_add_player_test(test_display_geometry)
romm_add_player_test(test_pause_chord)
romm_add_player_test(test_presentation_dirty_state)
romm_add_player_test(test_overlay_pixels)
# Hardware-rendering core classification (the single source of truth behind
# the player's GL-path selection and the ROMM_WIN32_SOFTWARE_ONLY fail-closed
# launch gate).
romm_add_player_test(test_hardware_core_classification)

# ---------------------------------------------------------------------------
# EnvironmentHandler hardware-rendering semantics. Both targets compile
# environment.cpp directly (plus the log/hardware-context registries it links
# against); the software-only target additionally carries the
# ROMM_WIN32_SOFTWARE_ONLY compile definition that pins the fail-closed
# refusal on every host — the definition, not the platform, is the unit under
# test.
# ---------------------------------------------------------------------------
function(romm_add_environment_test name)
    add_executable(${name} ${name}.cpp
        ${ROMM_ENGINE_SRC}/environment.cpp
        ${ROMM_ENGINE_SRC}/log.cpp
        ${ROMM_ENGINE_SRC}/hardware_context.cpp
    )
    target_include_directories(${name} PRIVATE
        ${ROMM_ENGINE_SRC}
        ${ROMM_ENGINE_INCLUDE}
        ${ROMM_REPO_ROOT}/third_party/libretro
    )
    romm_apply_warning_flags(${name})
    add_test(NAME ${name} COMMAND ${name})
endfunction()

romm_add_environment_test(test_environment_hw_render)
romm_add_environment_test(test_environment_hw_render_software_only)
target_compile_definitions(test_environment_hw_render_software_only PRIVATE
    ROMM_WIN32_SOFTWARE_ONLY=1
)

# ---------------------------------------------------------------------------
# Windows-specific native coverage (Phase 2, plans/WINDOWS_IMPL.md section
# 5.2): error semantics of the Win32 DynamicLibrary seam, compiled directly
# from the platform source alongside the test. Created only on WIN32 — the
# POSIX host suite keeps its existing selection untouched. The full
# end-to-end coverage (loading test_core.dll) arrives with the Windows
# player build; this unit pins the factory shape and capture-once error
# semantics now.
# ---------------------------------------------------------------------------
if(WIN32)
    add_executable(test_windows_dynamic_library
        test_windows_dynamic_library.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_dynamic_library.cpp
    )
    target_include_directories(test_windows_dynamic_library PRIVATE
        ${ROMM_ENGINE_SRC}
        ${ROMM_ENGINE_INCLUDE}
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    # Self-contained: the seam no longer logs through the engine log registry
    # (a failed safe-DLL-search setup now fails open() with a captured error
    # instead of emitting a degradation warning), so it needs only its own
    # source plus the kernel32 import library — not romm_engine_under_test.
    romm_apply_warning_flags(test_windows_dynamic_library)
    add_test(NAME test_windows_dynamic_library COMMAND test_windows_dynamic_library)

    # -----------------------------------------------------------------------
    # Win32 path-security slice (Phase 2, plans/WINDOWS_IMPL.md section 5.3):
    # real-handle canonicalization, reparse/owner/ACL classification, the
    # two-process LockFileEx session lock, and the LocalAppData default roots.
    # Each compiles its platform source directly (self-contained, like the
    # dynamic-library test above — not romm_player_under_test, whose shared
    # validation suite is POSIX-shaped until the full Windows player build).
    # -----------------------------------------------------------------------
    add_executable(test_windows_path_security
        test_windows_path_security.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_path_security.cpp
    )
    target_include_directories(test_windows_path_security PRIVATE
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    target_link_libraries(test_windows_path_security PRIVATE kernel32 advapi32)
    romm_apply_warning_flags(test_windows_path_security)
    add_test(NAME test_windows_path_security COMMAND test_windows_path_security)

    # -----------------------------------------------------------------------
    # Win32 atomic-file-store security step (Phase 2, plans/WINDOWS_IMPL.md
    # section 5.5 step 5): the destination DACL is preserved across
    # ReplaceFileW writes, an absent destination receives the safe
    # current-user + SYSTEM DACL before exposure, and security failures are
    # fail-closed with temp cleanup. Self-contained like the path-security
    # test above: compiles the platform source directly (plus the engine log
    # registry it logs through).
    # -----------------------------------------------------------------------
    add_executable(test_windows_atomic_file_store
        test_windows_atomic_file_store.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_atomic_file_store.cpp
        ${ROMM_ENGINE_SRC}/log.cpp
    )
    target_include_directories(test_windows_atomic_file_store PRIVATE
        ${ROMM_ENGINE_SRC}
        ${ROMM_ENGINE_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    # advapi32: GetSecurityInfo/SetFileSecurityW/ConvertSidToStringSidW/
    # ConvertStringSecurityDescriptorToSecurityDescriptorW (kernel32 is in
    # the toolchain's default set).
    target_link_libraries(test_windows_atomic_file_store PRIVATE kernel32 advapi32)
    romm_apply_warning_flags(test_windows_atomic_file_store)
    add_test(NAME test_windows_atomic_file_store COMMAND test_windows_atomic_file_store)

    add_executable(test_windows_session_lock
        test_windows_session_lock.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_session_lock.cpp
    )
    target_include_directories(test_windows_session_lock PRIVATE
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    target_link_libraries(test_windows_session_lock PRIVATE kernel32)
    romm_apply_warning_flags(test_windows_session_lock)
    add_test(NAME test_windows_session_lock COMMAND test_windows_session_lock)

    add_executable(test_windows_platform_paths
        test_windows_platform_paths.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_platform_paths.cpp
    )
    target_include_directories(test_windows_platform_paths PRIVATE
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    # shell32 for SHGetKnownFolderPath (LocalAppData known folder) and ole32
    # for CoTaskMemFree (the only legal release of the known-folder path).
    target_link_libraries(test_windows_platform_paths PRIVATE kernel32 shell32 ole32)
    romm_apply_warning_flags(test_windows_platform_paths)
    add_test(NAME test_windows_platform_paths COMMAND test_windows_platform_paths)

    # -----------------------------------------------------------------------
    # Win32 process-control slice (Phase 2, plans/WINDOWS_IMPL.md section
    # 5.1): the console-control-handler seam (pure classification + live
    # GenerateConsoleCtrlEvent to the test's own PID when a console is
    # attached) and the teardown watchdog (disarm-before-timeout, repeated
    # arm/disarm, and forced TerminateProcess of a deadlocked CHILD process —
    # sentinel-based so a misfire can never masquerade as a pass). All waits
    # are capped; total runtime is a few seconds.
    # -----------------------------------------------------------------------
    add_executable(test_windows_process_control
        test_windows_process_control.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_process_control.cpp
    )
    target_include_directories(test_windows_process_control PRIVATE
        ${ROMM_PLAYER_INCLUDE}
        ${ROMM_WINDOWS_PLATFORM_INCLUDE}
    )
    # kernel32: SetConsoleCtrlHandler, CreateThread/WaitForSingleObject/Set-
    # Event, TerminateProcess, CreateProcessW/GetExitCodeProcess.
    target_link_libraries(test_windows_process_control PRIVATE kernel32)
    romm_apply_warning_flags(test_windows_process_control)
    add_test(NAME test_windows_process_control COMMAND test_windows_process_control)

    # Win32 health-metrics slice (Phase 2, plans/WINDOWS_IMPL.md section
    # 5.1): processHealth() sanity against the live GetProcessMemoryInfo API
    # (peak working set in KiB, monotonicity, current-set coverage).
    add_executable(test_windows_health_metrics
        test_windows_health_metrics.cpp
        ${ROMM_NATIVE_ROOT}/platform/windows/src/windows_health_metrics.cpp
    )
    target_include_directories(test_windows_health_metrics PRIVATE
        ${ROMM_PLAYER_INCLUDE}
    )
    # psapi: GetProcessMemoryInfo (kernel32 for GetCurrentProcess is in the
    # default set; psapi is not).
    target_link_libraries(test_windows_health_metrics PRIVATE kernel32 psapi)
    romm_apply_warning_flags(test_windows_health_metrics)
    add_test(NAME test_windows_health_metrics COMMAND test_windows_health_metrics)
endif()

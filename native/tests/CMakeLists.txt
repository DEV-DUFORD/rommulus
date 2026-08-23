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

# The neutral engine sources under test. The header-only units
# (audio_ring_buffer.h, frame_scheduler.h, input_state.h, pixel_format.h)
# are consumed directly by the test executables through the include paths
# below. log.cpp/dynamic_library.cpp provide the registries the other units
# link against; core_library.cpp is exercised through a fake
# romm::dynamiclib::DynamicLibrary (the engine's default factory returns
# nullptr, so a real core .so is never loaded here).
add_library(romm_engine_under_test STATIC
    ${ROMM_ENGINE_SRC}/atomic_file_store.cpp
    ${ROMM_ENGINE_SRC}/core_library.cpp
    ${ROMM_ENGINE_SRC}/dynamic_library.cpp
    ${ROMM_ENGINE_SRC}/log.cpp
    ${ROMM_ENGINE_SRC}/pixel_format.cpp
)
target_include_directories(romm_engine_under_test PUBLIC
    ${ROMM_ENGINE_SRC}
    ${ROMM_ENGINE_INCLUDE}
    ${ROMM_REPO_ROOT}/third_party/libretro
)
target_compile_options(romm_engine_under_test PRIVATE -Wall -Wextra)

# The platform-neutral player protocol (Phase 8 Wave 1, LINUX_X64.md
# section 12): versioned request/result file schema plus trusted-root
# path validation. JSON parsing uses the vendored header-only
# nlohmann/json (MIT, third_party/nlohmann/).
add_library(romm_player_under_test STATIC
    ${ROMM_PLAYER_SRC}/protocol.cpp
    ${ROMM_PLAYER_SRC}/save_metadata.cpp
    ${ROMM_PLAYER_SRC}/validation.cpp
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
target_compile_options(romm_player_under_test PRIVATE -Wall -Wextra)

function(romm_add_engine_test name)
    add_executable(${name} ${name}.cpp)
    target_link_libraries(${name} PRIVATE romm_engine_under_test)
    target_compile_options(${name} PRIVATE -Wall -Wextra)
    add_test(NAME ${name} COMMAND ${name})
endfunction()

romm_add_engine_test(test_audio_ring_buffer)
romm_add_engine_test(test_frame_scheduler)
romm_add_engine_test(test_input_state)
romm_add_engine_test(test_atomic_file_store)
romm_add_engine_test(test_pixel_format)
romm_add_engine_test(test_core_library)

function(romm_add_player_test name)
    add_executable(${name} ${name}.cpp)
    # romm_engine_under_test supplies romm::atomicWriteFile, which the
    # binding sidecar (part of romm_player_under_test) links against.
    target_link_libraries(${name} PRIVATE romm_player_under_test romm_engine_under_test)
    target_compile_options(${name} PRIVATE -Wall -Wextra)
    add_test(NAME ${name} COMMAND ${name})
endfunction()

romm_add_player_test(test_player_protocol)
romm_add_player_test(test_save_metadata)
romm_add_player_test(test_player_validation)
romm_add_player_test(test_pause_menu)
# The binding editor's SDL-free units (Phase 9): the capture state machine
# (binding_capture.h, ported Android ControllerBindingCaptureCoordinator
# semantics) and the RetroPad BindingTable (binding_table.h). Both are
# header-only, so they compile straight into the test executables through
# the include paths above — no SDL involved.
romm_add_player_test(test_controller_capture)
romm_add_player_test(test_binding_table)
romm_add_player_test(test_keyboard_binding_table)
romm_add_player_test(test_pause_chord)
romm_add_player_test(test_presentation_dirty_state)

# Dolphin (GameCube), Linux x86_64 only.
#
# Dolphin is intentionally built as an isolated CMake project: its upstream
# build uses CMAKE_SOURCE_DIR to locate its own Externals tree, so it cannot be
# safely included with add_subdirectory() under native/player.
include(ExternalProject)

set(DOLPHIN_DIR ${ROMM_REPO_ROOT}/third_party/cores/dolphin)
if(NOT EXISTS "${DOLPHIN_DIR}/CMakeLists.txt")
    message(FATAL_ERROR
        "Dolphin source is missing. Run: git submodule update --init --recursive")
endif()
if(NOT EXISTS "${DOLPHIN_DIR}/Externals/fmt/fmt/CMakeLists.txt")
    message(FATAL_ERROR
        "Dolphin dependencies are missing. Run: git submodule update --init --recursive")
endif()

set(DOLPHIN_BUILD_PARALLEL_JOBS "2" CACHE STRING
    "Parallel job count for the Dolphin libretro core build. Kept low by \
default (see BUILD_COMMAND below for why); override with \
-DDOLPHIN_BUILD_PARALLEL_JOBS=<N> on machines with more cores/RAM \
(e.g. CI runners) to speed up this step.")

set(DOLPHIN_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/dolphin-build)
set(DOLPHIN_CORE_OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/libdolphin_core.so)
set(DOLPHIN_ASSET_OUTPUT
    ${CMAKE_CURRENT_BINARY_DIR}/share/rommulus/dolphin-emu/Sys)
find_program(DOLPHIN_C_COMPILER gcc REQUIRED)
find_program(DOLPHIN_CXX_COMPILER g++ REQUIRED)
find_program(DOLPHIN_GIT git REQUIRED)

ExternalProject_Add(dolphin_core
    SOURCE_DIR ${DOLPHIN_DIR}
    BINARY_DIR ${DOLPHIN_BUILD_DIR}
    PATCH_COMMAND
        ${CMAKE_COMMAND}
        -DGIT_EXECUTABLE=${DOLPHIN_GIT}
        -DSOURCE_DIR=${DOLPHIN_DIR}
        -DPATCH_FILE=${ROMM_REPO_ROOT}/native/cmake/patches/dolphin-save-memory.patch
        -P ${ROMM_REPO_ROOT}/native/cmake/apply-git-patch.cmake
    CMAKE_ARGS
        -DCMAKE_BUILD_TYPE=Release
        # GCC supplies the C++23 std::expected implementation used by current
        # Dolphin; Clang 18 with Ubuntu 24.04's libstdc++ does not expose it.
        -DCMAKE_C_COMPILER=${DOLPHIN_C_COMPILER}
        -DCMAKE_CXX_COMPILER=${DOLPHIN_CXX_COMPILER}
        -DLIBRETRO=ON
        -DENABLE_VULKAN=OFF
        -DENABLE_EGL=OFF
        -DENABLE_X11=OFF
        -DUSE_SYSTEM_LIBS=AUTO
    # Capped at 2 parallel jobs: Dolphin's VideoCommon/DolphinLibretro
    # translation units are memory-heavy in Release builds (heavy shader-gen
    # templates), and unbounded --parallel on a standard 4-core/16GB CI
    # runner reliably OOM-kills cc1plus once several of them compile at once.
    BUILD_COMMAND
        ${CMAKE_COMMAND} --build <BINARY_DIR> --target dolphin_libretro --parallel ${DOLPHIN_BUILD_PARALLEL_JOBS}
    INSTALL_COMMAND
        ${CMAKE_COMMAND} -E copy_if_different
        <BINARY_DIR>/dolphin_libretro.so
        ${DOLPHIN_CORE_OUTPUT}
    BUILD_BYPRODUCTS ${DOLPHIN_CORE_OUTPUT}
    USES_TERMINAL_CONFIGURE TRUE
    USES_TERMINAL_BUILD TRUE
)

ExternalProject_Add_Step(dolphin_core copy_system_data
    COMMAND ${CMAKE_COMMAND} -E make_directory ${DOLPHIN_ASSET_OUTPUT}
    COMMAND ${CMAKE_COMMAND} -E copy_directory
        ${DOLPHIN_DIR}/Data/Sys
        ${DOLPHIN_ASSET_OUTPUT}
    DEPENDEES install
)

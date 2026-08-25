# lrps2 (PS2, a PCSX2 fork), Linux x86_64 only.
#
# lrps2 is intentionally built as an isolated ExternalProject: it is a
# Makefile-based libretro core (no CMake build for the core itself) that
# compiles in-source inside the submodule tree and links a single
# pcsx2_libretro.so. Driving its Makefile from the player's CMake via
# add_subdirectory() is not possible, so we wrap the plain `make` invocation
# here, mirroring how dolphin-linux.cmake isolates the Dolphin core.
#
# Build identity (from third_party/cores/lrps2/Makefile):
#   - `make platform=unix` is the Linux target; it emits pcsx2_libretro.so in
#     the source root (CORE_DIR := .). All 3rdparty deps (glad, glslang,
#     vulkan-headers, xbyak, vixl, ...) are vendored under 3rdparty/, so the
#     build is self-contained (no network fetch).
#   - C++17, -fno-rtti -fno-exceptions (matches the CMake build).
#   - Default feature set is OpenGL + Vulkan + CHD. We disable Vulkan to match
#     dolphin (ENABLE_VULKAN=OFF) and the player's SDL3-managed OpenGL frontend
#     (sdl_hardware_context.cpp uses SDL_GL_CreateContext, not a Vulkan
#     instance), so the core renders through the same GL path the player offers.
include(ExternalProject)

set(LRPS2_DIR ${ROMM_REPO_ROOT}/third_party/cores/lrps2)
if(NOT EXISTS "${LRPS2_DIR}/Makefile")
    message(FATAL_ERROR
        "lrps2 source is missing. Run: git submodule update --init --recursive")
endif()
if(NOT EXISTS "${LRPS2_DIR}/bin/resources/GameIndex.yaml")
    message(FATAL_ERROR
        "lrps2 GameIndex.yaml is missing. Run: git submodule update --init --recursive")
endif()

set(LRPS2_BUILD_DIR ${CMAKE_CURRENT_BINARY_DIR}/lrps2-build)
set(LRPS2_CORE_OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/liblrps2_core.so)
set(LRPS2_ASSET_OUTPUT
    ${CMAKE_CURRENT_BINARY_DIR}/share/rommulus/lrps2/resources)
find_program(LRPS2_C_COMPILER gcc REQUIRED)
find_program(LRPS2_CXX_COMPILER g++ REQUIRED)
find_program(LRPS2_GIT git REQUIRED)

ExternalProject_Add(lrps2_core
    SOURCE_DIR ${LRPS2_DIR}
    BINARY_DIR ${LRPS2_BUILD_DIR}
    DOWNLOAD_COMMAND ""
    UPDATE_COMMAND ""
    CONFIGURE_COMMAND ""
    # Exports the romm_get_save_memory_size / romm_get_save_memory_data /
    # romm_restore_save_memory symbols backing the engine's generic SRAM
    # checkpoint/restore flow with lrps2's slot-0 memory card image (the
    # core itself exposes no RETRO_MEMORY_SAVE_RAM region). apply-git-patch
    # is idempotent: it applies the patch when clean and skips it when the
    # tree already carries it.
    PATCH_COMMAND
        ${CMAKE_COMMAND}
        -DGIT_EXECUTABLE=${LRPS2_GIT}
        -DSOURCE_DIR=${LRPS2_DIR}
        -DPATCH_FILE=${ROMM_REPO_ROOT}/native/cmake/patches/lrps2-save-memory.patch
        -P ${ROMM_REPO_ROOT}/native/cmake/apply-git-patch.cmake
    # Places the JIT code arena near the loaded module on Linux x86-64 (ASLR
    # otherwise lands it out of RIP-relative reach of module globals, corrupting
    # memory on some first launches) and makes out-of-reach reservations a hard
    # abort instead of silent truncation. apply-git-patch is idempotent.
    PATCH_COMMAND
        ${CMAKE_COMMAND}
        -DGIT_EXECUTABLE=${LRPS2_GIT}
        -DSOURCE_DIR=${LRPS2_DIR}
        -DPATCH_FILE=${ROMM_REPO_ROOT}/native/cmake/patches/lrps2-rip-relative-reach.patch
        -P ${ROMM_REPO_ROOT}/native/cmake/apply-git-patch.cmake
    # PDIVW and PDIVBW are signed packed divides. The pinned core emits CDQ
    # followed by unsigned DIV for both, which raises SIGFPE whenever a
    # negative dividend produces a quotient that does not fit in uint32_t.
    PATCH_COMMAND
        ${CMAKE_COMMAND}
        -DGIT_EXECUTABLE=${LRPS2_GIT}
        -DSOURCE_DIR=${LRPS2_DIR}
        -DPATCH_FILE=${ROMM_REPO_ROOT}/native/cmake/patches/lrps2-pdiv-signed-division.patch
        -P ${ROMM_REPO_ROOT}/native/cmake/apply-git-patch.cmake
    # The core's Makefile builds in-source (objects land in the submodule tree;
    # `make -C ${LRPS2_DIR} clean` removes them). GCC is the PCSX2 reference
    # toolchain and is already required by the dolphin fragment, so no new CI
    # dependency is introduced.
    #
    # Capped at 2 parallel jobs: like Dolphin, lrps2's GS/recompiler
    # translation units are memory-heavy in Release builds, and the multi-ISA
    # path compiles a subset of sources three times (sse4/avx/avx2), so an
    # unbounded -j on a standard 4-core/16GB CI runner reliably OOM-kills
    # cc1plus once several of them compile at once.
    BUILD_COMMAND
        make -C <SOURCE_DIR> platform=unix
        CC=${LRPS2_C_COMPILER}
        CXX=${LRPS2_CXX_COMPILER}
        HAVE_VULKAN=0
        HAVE_OPENGL=1
        HAVE_CHD=1
        -j2
    INSTALL_COMMAND
        ${CMAKE_COMMAND} -E copy_if_different
        <SOURCE_DIR>/pcsx2_libretro.so
        ${LRPS2_CORE_OUTPUT}
    BUILD_BYPRODUCTS ${LRPS2_CORE_OUTPUT}
    USES_TERMINAL_BUILD TRUE
)

ExternalProject_Add_Step(lrps2_core copy_game_index
    COMMAND ${CMAKE_COMMAND} -E make_directory ${LRPS2_ASSET_OUTPUT}
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${LRPS2_DIR}/bin/resources/GameIndex.yaml
        ${LRPS2_ASSET_OUTPUT}/GameIndex.yaml
    DEPENDEES install
)

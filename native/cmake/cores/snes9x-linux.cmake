# ---------------------------------------------------------------------------
# Snes9x (project's own non-commercial redistribution license; see
# CoreManifest.kt's "snes9x" entry and the owner risk-acceptance decision
# recorded there and in HANDOFF.md's Phase 7 section): vendored under
# third_party/cores/snes9x/, pinned to the upstream release tag `1.63`,
# commit 921f9f7b83660eb44ad263022a57a4a029057c37
# (https://github.com/snes9xgit/snes9x/tree/1.63). See
# third_party/cores/snes9x/VENDORING.md for exactly what was vendored, why,
# and what was deliberately excluded (JMA/ZIP ROM archive readers, the
# desktop Vulkan GUI's vendored third-party libraries, netplay, and the
# interactive debugger — none used by this libretro build).
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS) and libretro/Makefile (-std=c++14)
# exactly.
# ---------------------------------------------------------------------------

include(${CMAKE_CURRENT_LIST_DIR}/snes9x-sources.cmake)

add_library(snes9x_core SHARED ${ROMM_SNES9X_SOURCES})

set_target_properties(snes9x_core PROPERTIES
    CXX_STANDARD 14
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(snes9x_core SYSTEM PRIVATE
    ${SNES9X_DIR}/core
    ${SNES9X_DIR}/core/apu
    ${SNES9X_DIR}/core/apu/bapu
    ${SNES9X_DIR}/libretro
    ${SNES9X_DIR}/libretro/libretro-common/include
)

target_compile_definitions(snes9x_core PRIVATE
    # Matches upstream libretro/jni/Android.mk's COREFLAGS exactly.
    __LIBRETRO__
    HAVE_STRINGS_H
    RIGHTSHIFT_IS_SAR
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(snes9x_core PRIVATE
    "-Wl,--version-script=${SNES9X_DIR}/libretro/link.T"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(snes9x_core
    m
)

# ---------------------------------------------------------------------------
# ROMM_WIN32_SOFTWARE_ONLY — explicit temporary Windows software-only player
# boundary (pre-ANGLE).
#
# Option (bool, default OFF globally; Windows CI may enable it):
#   - WIN32 + ON: the player build EXCLUDES the GLES3/ANGLE hardware-context
#     source (sdl_hardware_context.cpp), its ANGLE EGL/GLES import libraries,
#     and the ANGLE include directory, and compiles a compatible no-op
#     hardware-context implementation instead (sdl_hardware_context_
#     software_only.cpp) so no unresolved GL API can exist. The player fails
#     closed with launch_failed for every known-hardware-rendering core (see
#     native/player/include/native/player/hardware_core.h), and the engine's
#     EnvironmentHandler rejects every Libretro SET_HW_RENDER /
#     GET_HW_RENDER_INTERFACE request under the ROMM_WIN32_SOFTWARE_ONLY
#     compile definition — never silently downgrading. Software cores
#     (test_core) keep full video/audio/input functionality. This exists so
#     Windows CI can run the test_core end-to-end suite before the pinned
#     ANGLE distribution lands.
#   - WIN32 + OFF (default): unchanged — the ANGLE EGL/GLES hardware context
#     remains required at configure time exactly as before.
#   - non-WIN32: the option is INVALID and clearly ignored: configure emits a
#     warning and continues with the normal hardware-capable configuration
#     (Linux/ANGLE-capable behavior unchanged).
#
# Included by native/player, native/tests, and the Android host build so the
# option is declared and validated in every entry point.
# ---------------------------------------------------------------------------

option(ROMM_WIN32_SOFTWARE_ONLY
       "Windows player only (temporary pre-ANGLE boundary): exclude the GLES3/ANGLE hardware context, compile a no-op hardware context instead, and fail closed for hardware-rendering cores"
       OFF)

if(NOT ROMM_WIN32_SOFTWARE_ONLY MATCHES "^(ON|OFF|on|off|0|1|TRUE|FALSE|true|false)$")
    message(FATAL_ERROR
        "ROMM_WIN32_SOFTWARE_ONLY must be a boolean (ON/OFF); got: "
        "'${ROMM_WIN32_SOFTWARE_ONLY}'.")
endif()

if(ROMM_WIN32_SOFTWARE_ONLY AND NOT WIN32)
    message(WARNING
        "ROMM_WIN32_SOFTWARE_ONLY=ON is only valid for Windows (WIN32) player "
        "builds. Ignoring it on this platform and continuing with the normal "
        "hardware-capable configuration (Linux/ANGLE behavior unchanged).")
    set(ROMM_WIN32_SOFTWARE_ONLY OFF)
endif()

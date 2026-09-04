# ---------------------------------------------------------------------------
# Per-platform source selection for the engine/player platform contracts
# (Phase 2, plans/WINDOWS_IMPL.md section 5.1).
#
# The atomic file store and the player's dynamic-loading / path-security /
# session-lock / paths / process-control / health-metrics contracts are
# implemented once per platform:
#
#   POSIX:  native/platform/posix/src/posix_<concern>.cpp
#   Win32:  native/platform/windows/src/windows_<concern>.cpp
#
# romm_select_platform_sources(<out-var> <concern> [<concern> ...])
#
#   Appends the selected implementation source for each concern to <out-var>.
#   The selection happens at configure time, and configure FAILS with a
#   diagnostic listing every missing file when the selected platform tree
#   does not provide an implementation. It never falls back to the other
#   platform's sources: compiling posix_*.cpp into a Windows build (or vice
#   versa) is exactly the silent breakage this split exists to prevent.
#
# All seven concerns now have both implementations (Phase 2 complete: atomic
# file store, dynamic library, path security, session lock, platform paths,
# process control, and health metrics). A WIN32 (or POSIX) configure of a
# project that calls this function for a concern missing from the selected
# tree still fails fast with the missing-source diagnostic — that failure is
# intentional and part of the Windows preset contract (see CMakePresets.json
# in native/player and native/tests): it guards against a future source being
# deleted or renamed without its counterpart.
# ---------------------------------------------------------------------------

function(romm_select_platform_sources out_var)
    if(WIN32)
        set(platform_prefix windows)
        set(platform_dir ${ROMM_NATIVE_ROOT}/platform/windows/src)
    else()
        set(platform_prefix posix)
        set(platform_dir ${ROMM_NATIVE_ROOT}/platform/posix/src)
    endif()

    set(selected)
    set(missing)
    foreach(concern IN LISTS ARGN)
        set(src ${platform_dir}/${platform_prefix}_${concern}.cpp)
        if(EXISTS ${src})
            list(APPEND selected ${src})
        else()
            list(APPEND missing ${src})
        endif()
    endforeach()

    if(missing)
        string(JOIN "\n  " missing_list ${missing})
        message(FATAL_ERROR
            "Phase 2 platform sources are missing for this build:\n"
            "  ${missing_list}\n"
            "Each concern needs one implementation per platform "
            "(plans/WINDOWS_IMPL.md section 5.1). The selected tree is "
            "${platform_dir}; CMake will NOT substitute the other "
            "platform's sources.")
    endif()

    set(${out_var} ${selected} PARENT_SCOPE)
endfunction()

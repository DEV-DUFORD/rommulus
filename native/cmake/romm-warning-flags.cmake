# ---------------------------------------------------------------------------
# Compiler-specific warning flags (Phase 2, plans/WINDOWS_IMPL.md section
# 5.6): preserve the project's -Wall -Wextra strictness with compiler-
# specific expressions instead of passing GCC/Clang flags to MSVC.
#
# romm_apply_warning_flags(<target>)
# ---------------------------------------------------------------------------

function(romm_apply_warning_flags target)
    if(MSVC)
        target_compile_options(${target} PRIVATE /W4)
    else()
        target_compile_options(${target} PRIVATE -Wall -Wextra)
    endif()
endfunction()

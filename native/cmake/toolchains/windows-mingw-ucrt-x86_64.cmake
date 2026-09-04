# ---------------------------------------------------------------------------
# windows-mingw-ucrt-x86_64.cmake — Windows x86_64 toolchain contract
# (Phase 2, plans/WINDOWS_IMPL.md section 3.2).
#
# Native build on a pinned `windows-2022` GitHub Actions runner (never the
# moving `windows-latest` label) using MinGW-w64 UCRT64 + Ninja:
#
#   cmake --preset windows-x86_64        # from native/player or native/tests
#   cmake --build --preset windows-x86_64
#
# This file pins the compiler family and runtime strategy; it does not
# vendor or download anything. The UCRT64 toolchain must already be on PATH
# (or ROMM_MINGW_PREFIX must point at its root). There is deliberately no
# fallback to MSVC/Clang: mixing MSVCRT/UCRT or dynamically linked compiler
# runtimes is forbidden without an audited DLL closure (section 3.2).
#
# Contract status: this toolchain becomes buildable once the Win32 platform
# sources (native/platform/windows/src/, Phase 2), the pinned SDL3 source
# release, and the pinned ANGLE import libraries land. Until then, configure
# fails with a clear missing-toolchain / missing-source / missing-dependency
# diagnostic — it must never silently pick up POSIX sources or system GL.
# ---------------------------------------------------------------------------

set(CMAKE_SYSTEM_NAME Windows)
set(CMAKE_SYSTEM_PROCESSOR x86_64)

# Locate the pinned MinGW-w64 UCRT64 toolchain (x86_64-w64-mingw32-*).
if(DEFINED ENV{ROMM_MINGW_PREFIX})
    set(_romm_mingw_hint "$ENV{ROMM_MINGW_PREFIX}/bin")
else()
    set(_romm_mingw_hint "")
endif()

find_program(ROMM_WIN32_C_COMPILER NAMES x86_64-w64-mingw32-gcc HINTS ${_romm_mingw_hint})
find_program(ROMM_WIN32_CXX_COMPILER NAMES x86_64-w64-mingw32-g++ HINTS ${_romm_mingw_hint})
find_program(ROMM_WIN32_RC_COMPILER NAMES x86_64-w64-mingw32-windres HINTS ${_romm_mingw_hint})

if(NOT ROMM_WIN32_C_COMPILER OR NOT ROMM_WIN32_CXX_COMPILER OR NOT ROMM_WIN32_RC_COMPILER)
    message(FATAL_ERROR
        "windows-mingw-ucrt-x86_64 toolchain: MinGW-w64 UCRT64 not found. "
        "Install the pinned x86_64-w64-mingw32 UCRT64 toolchain on PATH (or set "
        "ROMM_MINGW_PREFIX to its root) per plans/WINDOWS_IMPL.md section 3.2. "
        "MSVC and Clang are not acceptable substitutes for the common player/core build.")
endif()

set(CMAKE_C_COMPILER ${ROMM_WIN32_C_COMPILER})
set(CMAKE_CXX_COMPILER ${ROMM_WIN32_CXX_COMPILER})
set(CMAKE_RC_COMPILER ${ROMM_WIN32_RC_COMPILER})

# Static compiler runtimes only (section 3.2): -static-libgcc/-static-libstdc++
# keep libgcc/libstdc++ out of the shipped DLL closure while the exe and core
# DLLs stay dynamically linked — required by the pinned dynamic SDL3 (SDL3.dll)
# and the explicitly packaged core DLLs, whose licensing/runtime boundaries are
# audited separately. Full -static is forbidden: it would force-link the CRT
# into the exe even though SDL3 remains a runtime dependency, breaking the
# audited DLL closure.
set(CMAKE_EXE_LINKER_FLAGS_INIT "-static-libgcc -static-libstdc++")
set(CMAKE_SHARED_LINKER_FLAGS_INIT "-static-libgcc -static-libstdc++")

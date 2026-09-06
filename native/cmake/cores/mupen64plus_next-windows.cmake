# Windows x64 MinGW/UCRT: GLideN64 GLES3 through the player's pinned ANGLE,
# HLE/CXD4 RSP, threaded Angrylion and the upstream Windows x64 new dynarec.
# No Vulkan/paraLLEl closure or desktop WGL context is required.
if(NOT MINGW OR NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
    message(FATAL_ERROR "Mupen64Plus-Next Windows requires MinGW x86_64.")
endif()
if(ROMM_WIN32_SOFTWARE_ONLY OR NOT ROMM_ANGLE_INCLUDE_DIR
        OR NOT EXISTS "${ROMM_ANGLE_EGL_LIBRARY}"
        OR NOT EXISTS "${ROMM_ANGLE_GLESV2_LIBRARY}")
    message(FATAL_ERROR "Mupen64Plus-Next Windows requires the full ANGLE GLES3 player.")
endif()

include(${CMAKE_CURRENT_LIST_DIR}/mupen64plus_next-desktop-sources.cmake)
list(APPEND M64_SOURCES_C
    ${M64_GLIDEN64_DIR}/src/osal/osal_files_win32.c
    ${M64_COMM_DIR}/glsym/glsym_es3.c
    ${MUPEN64_DIR}/mupen64plus-rsp-paraLLEl/win32/mman/sys/mman.c
)
set(M64_WINDOWS_INCLUDE_DIRS
    ${ROMM_ANGLE_INCLUDE_DIR}
    ${M64_DESKTOP_INCLUDE_DIRS}
    ${MUPEN64_DIR}/mupen64plus-rsp-paraLLEl/win32/mman
)
set(M64_WINDOWS_DEFINES
    __STDC_CONSTANT_MACROS __STDC_LIMIT_MACROS __LIBRETRO__
    OS_WINDOWS MINGW WIN64 UNICODE _CRT_RAND_S
    USE_FILE32API M64P_PLUGIN_API M64P_CORE_PROTOTYPES _ENDUSER_RELEASE
    SINC_LOWER_QUALITY MUPENPLUSAPI TXFILTER_LIB __VEC4_OPT
    HAVE_LLE HAVE_THR_AL ARCH_MIN_SSE2 NEW_DYNAREC=2 DYNAREC
    EGL HAVE_OPENGLES HAVE_OPENGLES3 GLES3
    GIT_VERSION=" 98c1b0d"
    RETRO_API=
    # Embedded Mupen/GLideN64/RSP plugin headers hard-code dllexport.
    # MinGW's __declspec(x) becomes __attribute__((x)); erase only the
    # export annotation, retaining dllimport/alignment and all calling ABIs.
    # The .def file is then the sole public DLL export contract.
    dllexport=
)
set(M64_WINDOWS_OPTIONS
    -O3 -fcommon -fsigned-char -ffast-math -fno-strict-aliasing
    -fomit-frame-pointer -msse -msse2
)

add_library(mupen64plus_next_asm_defines OBJECT
    ${M64_CORE_DIR}/src/asm_defines/asm_defines.c
)
set_target_properties(mupen64plus_next_asm_defines PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED ON INTERPROCEDURAL_OPTIMIZATION OFF
)
target_include_directories(mupen64plus_next_asm_defines SYSTEM PRIVATE
    ${M64_WINDOWS_INCLUDE_DIRS})
target_compile_definitions(mupen64plus_next_asm_defines PRIVATE ${M64_WINDOWS_DEFINES})
target_compile_options(mupen64plus_next_asm_defines PRIVATE ${M64_WINDOWS_OPTIONS} -fno-lto)

find_program(M64_NASM_EXECUTABLE nasm REQUIRED)
set(M64_ASM_DIR ${CMAKE_CURRENT_BINARY_DIR}/mupen64plus_next_asm)
set(M64_DYNAREC_OBJECT ${M64_ASM_DIR}/linkage_x64.obj)
add_custom_command(
    OUTPUT ${M64_DYNAREC_OBJECT}
    BYPRODUCTS ${M64_ASM_DIR}/asm_defines_nasm.h
    COMMAND ${CMAKE_COMMAND}
        "-DOBJECT=$<TARGET_OBJECTS:mupen64plus_next_asm_defines>"
        "-DOUTPUT=${M64_ASM_DIR}/asm_defines_nasm.h"
        -P ${CMAKE_CURRENT_LIST_DIR}/mupen64plus_next-asm-defines.cmake
    COMMAND ${M64_NASM_EXECUTABLE}
        -i${M64_ASM_DIR}/ -f win64 -d WIN64
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/x64/linkage_x64.asm
        -o ${M64_DYNAREC_OBJECT}
    DEPENDS
        mupen64plus_next_asm_defines
        $<TARGET_OBJECTS:mupen64plus_next_asm_defines>
        ${CMAKE_CURRENT_LIST_DIR}/mupen64plus_next-asm-defines.cmake
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/x64/linkage_x64.asm
    VERBATIM
)
set_source_files_properties(${M64_DYNAREC_OBJECT} PROPERTIES
    GENERATED TRUE EXTERNAL_OBJECT TRUE)
add_library(mupen64plus_next_core SHARED
    ${M64_SOURCES_C}
    ${M64_GLIDEN64_SOURCES_CXX}
    ${M64_ANGRYLION_DIR}/parallel_al.cpp
    ${M64_DYNAREC_OBJECT}
    ${CMAKE_CURRENT_LIST_DIR}/mupen64plus_next-windows.def
)
set_target_properties(mupen64plus_next_core PROPERTIES
    C_STANDARD 11 C_STANDARD_REQUIRED ON
    CXX_STANDARD 11 CXX_STANDARD_REQUIRED ON
    PREFIX ""
)
target_include_directories(mupen64plus_next_core SYSTEM PRIVATE ${M64_WINDOWS_INCLUDE_DIRS})
target_compile_definitions(mupen64plus_next_core PRIVATE ${M64_WINDOWS_DEFINES})
target_compile_options(mupen64plus_next_core PRIVATE
    ${M64_WINDOWS_OPTIONS}
    $<$<COMPILE_LANGUAGE:C>:-Wno-discarded-qualifiers>
    $<$<COMPILE_LANGUAGE:CXX>:-fpermissive>
)
target_link_options(mupen64plus_next_core PRIVATE
    "-Wl,--no-undefined" -static-libgcc -static-libstdc++)
find_package(Threads REQUIRED)
target_link_libraries(mupen64plus_next_core PRIVATE
    ${ROMM_ANGLE_EGL_LIBRARY}
    ${ROMM_ANGLE_GLESV2_LIBRARY}
    Threads::Threads
    shell32
)

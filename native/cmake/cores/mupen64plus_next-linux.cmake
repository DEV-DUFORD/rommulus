# Linux desktop OpenGL + x86_64 new dynarec, with the existing Apple
# interpreter-only verification path preserved.
include(${CMAKE_CURRENT_LIST_DIR}/mupen64plus_next-desktop-sources.cmake)
set(M64_LINUX_INCLUDE_DIRS ${M64_DESKTOP_INCLUDE_DIRS})
list(APPEND M64_SOURCES_C
    ${M64_GLIDEN64_DIR}/src/osal/osal_files_unix.c
    ${M64_COMM_DIR}/glsym/glsym_gl.c
)
set(M64_LINUX_DEFINES
    __STDC_CONSTANT_MACROS
    __STDC_LIMIT_MACROS
    __LIBRETRO__
    OS_LINUX
    USE_FILE32API
    M64P_PLUGIN_API
    M64P_CORE_PROTOTYPES
    _ENDUSER_RELEASE
    SINC_LOWER_QUALITY
    MUPENPLUSAPI
    TXFILTER_LIB
    __VEC4_OPT
    HAVE_POSIX_MEMALIGN=1
    HAVE_LLE
    HAVE_THR_AL
    ARCH_MIN_SSE2
    NEW_DYNAREC=2
    DYNAREC
    CORE
    HAVE_OPENGL
    GIT_VERSION=" 98c1b0d"
)

# Apple Silicon verification build: the x86_64 dynarec (nasm object + SSE2 C
# code) cannot link into an arm64 library, so fall back to the pure
# interpreter (the same #ifndef NEW_DYNAREC path upstream supports).
if(APPLE)
list(REMOVE_ITEM M64_LINUX_DEFINES ARCH_MIN_SSE2 NEW_DYNAREC=2 DYNAREC)
list(REMOVE_ITEM M64_SOURCES_C ${M64_CORE_DIR}/src/device/r4300/new_dynarec/new_dynarec.c)
# NO_ASM drops the x86_64 asm entry points (dyna_jump et al.) whose only
# provider is the excluded nasm linkage object.
list(APPEND M64_LINUX_DEFINES NO_ASM)
endif()

set(M64_ASM_DIR ${CMAKE_CURRENT_BINARY_DIR}/mupen64plus_next_asm)
set(M64_DYNAREC_OBJECT ${M64_ASM_DIR}/linkage_x64.o)
find_program(M64_NASM_EXECUTABLE nasm REQUIRED)

# The x86_64 dynarec linkage object is only linkable on non-Apple hosts.
if(NOT APPLE)
set(M64_DYNAREC_LINK_OBJECT ${M64_DYNAREC_OBJECT})
endif()

add_library(mupen64plus_next_asm_defines OBJECT
    ${M64_CORE_DIR}/src/asm_defines/asm_defines.c
)
set_target_properties(mupen64plus_next_asm_defines PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    POSITION_INDEPENDENT_CODE ON
)
target_include_directories(mupen64plus_next_asm_defines SYSTEM PRIVATE
    ${M64_LINUX_INCLUDE_DIRS}
)
target_compile_definitions(mupen64plus_next_asm_defines PRIVATE
    ${M64_LINUX_DEFINES}
)
target_compile_options(mupen64plus_next_asm_defines PRIVATE
    -O3
    -fno-lto
    -fcommon
    -fsigned-char
    -ffast-math
    -fno-strict-aliasing
    -fomit-frame-pointer
    -fvisibility=hidden
)

# x86_64-only tuning; not applicable to Apple Silicon verification builds.
if(NOT APPLE)
target_compile_options(mupen64plus_next_asm_defines PRIVATE -msse -msse2)
endif()

if(NOT APPLE)
add_custom_command(
    OUTPUT
        ${M64_ASM_DIR}/asm_defines_nasm.h
        ${M64_ASM_DIR}/asm_defines_gas.h
        ${M64_DYNAREC_OBJECT}
    COMMAND ${CMAKE_COMMAND} -E make_directory ${M64_ASM_DIR}
    COMMAND bash
        ${M64_CORE_DIR}/tools/gen_asm_script.sh
        ${M64_ASM_DIR}
        $<TARGET_OBJECTS:mupen64plus_next_asm_defines>
    COMMAND ${M64_NASM_EXECUTABLE}
        -i${M64_ASM_DIR}/
        -f elf64
        -d ELF_TYPE
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/x64/linkage_x64.asm
        -o ${M64_DYNAREC_OBJECT}
    DEPENDS
        mupen64plus_next_asm_defines
        ${M64_CORE_DIR}/tools/gen_asm_script.sh
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/x64/linkage_x64.asm
    VERBATIM
)

set_source_files_properties(${M64_DYNAREC_OBJECT} PROPERTIES
    GENERATED TRUE
    EXTERNAL_OBJECT TRUE
)
endif()

add_library(mupen64plus_next_core SHARED
    ${M64_SOURCES_C}
    ${M64_GLIDEN64_SOURCES_CXX}
    ${M64_ANGRYLION_DIR}/parallel_al.cpp
    $<TARGET_OBJECTS:mupen64plus_next_asm_defines>
    ${M64_DYNAREC_LINK_OBJECT}
)

set_target_properties(mupen64plus_next_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 11
    CXX_STANDARD_REQUIRED ON
)

target_include_directories(mupen64plus_next_core SYSTEM PRIVATE
    ${M64_LINUX_INCLUDE_DIRS}
)

target_compile_definitions(mupen64plus_next_core PRIVATE
    ${M64_LINUX_DEFINES}
)

target_compile_options(mupen64plus_next_core PRIVATE
    -O3
    -fPIC
    -fcommon
    -fsigned-char
    -ffast-math
    -fno-strict-aliasing
    -fomit-frame-pointer
    -fvisibility=hidden
)

# x86_64-only tuning; not applicable to Apple Silicon verification builds.
if(NOT APPLE)
target_compile_options(mupen64plus_next_core PRIVATE -msse -msse2)
endif()

if(APPLE)
# Apple clang predefines TARGET_OS_MAC, which steers the vendored libpng's
# pngpriv.h to a classic-Mac <fp.h> include that does not exist; undefine it
# so zlib/libpng take their portable Unix paths.
target_compile_options(mupen64plus_next_core PRIVATE -UTARGET_OS_MAC)
# This SDK does not export the CoreAudio AudioConverter API that the
# __APPLE__ path of audio_backend_libretro.c uses; force just this TU onto
# the portable libretro-common resampler path (already in the source list).
set_source_files_properties(
    ${M64_AUDIO_LIBRETRO_DIR}/audio_backend_libretro.c
    PROPERTIES COMPILE_OPTIONS "-U__APPLE__"
)
endif()

target_compile_options(mupen64plus_next_core PRIVATE
    $<$<COMPILE_LANGUAGE:C>:-Wno-discarded-qualifiers>
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>
)

# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(mupen64plus_next_core PRIVATE
    "-Wl,--version-script=${M64_LIBRETRO_DIR}/link.T"
    "-Wl,--no-undefined"
)
endif()

find_package(OpenGL REQUIRED)
target_link_libraries(mupen64plus_next_core PRIVATE
    OpenGL::GL
    m
    dl
    pthread
)

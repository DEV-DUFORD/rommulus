# PCSX-ReARMed Linux x86_64, pinned to
# da2cb8ecd17fd0932ab6d94774c0522beebce6e3. GPL-3.0-or-later combined target:
# Lightrec/Lightning, SSSE3 software GPU, physical CD-ROM, VFS, and CHD.
include(${CMAKE_CURRENT_LIST_DIR}/pcsx_rearmed-desktop-sources.cmake)
list(APPEND PCSX_REARMED_SOURCES ${PCSX_REARMED_CDROM_SOURCES})
if(NOT APPLE)
    list(APPEND PCSX_REARMED_SOURCES ${PCSX_REARMED_LIGHTREC_SOURCES})
endif()

add_library(pcsx_rearmed_core SHARED ${PCSX_REARMED_SOURCES})

target_include_directories(pcsx_rearmed_core SYSTEM PRIVATE
    ${PCSX_REARMED_DIR}/include
    ${PCSX_LIGHTNING_DIR}/include
    ${PCSX_LIGHTREC_DIR}
    ${PCSX_REARMED_DIR}/include/lightning
    ${PCSX_REARMED_DIR}/include/lightrec
    ${PCSX_COMMON_DIR}/include
    ${PCSX_CHDR_DIR}/include
    ${PCSX_CHDR_DIR}/deps/lzma-25.01/include
    ${PCSX_CHDR_DIR}/deps/zstd-1.5.7
    ${PCSX_CHDR_DIR}/deps/miniz-3.1.1
)

set(PCSX_REARMED_DEFINES
    NDEBUG
    P_HAVE_MMAP=1
    P_HAVE_POSIX_MEMALIGN=1
    DISABLE_MEM_LUTS=0
    GPU_NEON
    HAVE_CHD
    USE_MINIZ
    HAVE_CDROM
    USE_LIBRETRO_VFS
    HAVE_LIBRETRO
    NO_FRONTEND
    BUILTIN_GPU=neon
    GIT_VERSION=" da2cb8e"
)

# Apple verification uses the interpreter; Lightrec's Linux memory mapping
# and x86_64 Lightning backend are not portable to Apple Silicon.
if(APPLE)
    list(APPEND PCSX_REARMED_DEFINES DRC_DISABLE)
else()
    list(APPEND PCSX_REARMED_DEFINES
        LIGHTREC
        LIGHTREC_STATIC
        LIGHTREC_CUSTOM_MAP=1
        LIGHTREC_CODE_INV=0
        LIGHTREC_ENABLE_THREADED_COMPILER=0
        LIGHTREC_ENABLE_DISASSEMBLER=0
        LIGHTREC_NO_DEBUG=1)
endif()
target_compile_definitions(pcsx_rearmed_core PRIVATE ${PCSX_REARMED_DEFINES})
target_compile_options(pcsx_rearmed_core PRIVATE
    -O3 -ffast-math -ffunction-sections -fdata-sections)
if(NOT APPLE)
    target_compile_options(pcsx_rearmed_core PRIVATE -mssse3)
endif()

set(PCSX_DFSOUND_SOURCES
    ${PCSX_PLUGINS_DIR}/dfsound/dma.c
    ${PCSX_PLUGINS_DIR}/dfsound/freeze.c
    ${PCSX_PLUGINS_DIR}/dfsound/registers.c
    ${PCSX_PLUGINS_DIR}/dfsound/spu.c
    ${PCSX_PLUGINS_DIR}/dfsound/out.c
    ${PCSX_PLUGINS_DIR}/dfsound/nullsnd.c
)
set(PCSX_GPULIB_SOURCES
    ${PCSX_PLUGINS_DIR}/gpulib/gpu.c
    ${PCSX_PLUGINS_DIR}/gpulib/vout_pl.c
    ${PCSX_PLUGINS_DIR}/gpulib/prim.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu_async.c
)
set(PCSX_LIGHTNING_SOURCES
    ${PCSX_LIGHTNING_DIR}/lib/jit_disasm.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_memory.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_names.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_note.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_print.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_size.c
    ${PCSX_LIGHTNING_DIR}/lib/lightning.c
)

set_property(SOURCE ${PCSX_CORE_DIR}/cdrom-async.c APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_CDROM)
set_property(SOURCE ${PCSX_FRONTEND_DIR}/libretro.c APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_CDROM USE_ASYNC_GPU USE_ASYNC_SPU)
set_property(SOURCE ${PCSX_DFSOUND_SOURCES} APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_SPU)
set_property(SOURCE ${PCSX_GPULIB_SOURCES} APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_GPU)
set_property(SOURCE ${PCSX_FRONTEND_DIR}/main.c APPEND
    PROPERTY COMPILE_DEFINITIONS HAVE_RTHREADS)
set_property(SOURCE ${PCSX_CORE_DIR}/lightrec/mem.c APPEND
    PROPERTY COMPILE_DEFINITIONS _GNU_SOURCE)
set_property(SOURCE ${PCSX_LIGHTNING_SOURCES} APPEND
    PROPERTY COMPILE_DEFINITIONS HAVE_MMAP=P_HAVE_MMAP)
set_property(SOURCE ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu_if.c APPEND
    PROPERTY COMPILE_DEFINITIONS
        NEON_BUILD TEXTURE_CACHE_4BPP TEXTURE_CACHE_8BPP SIMD_BUILD)
set_property(SOURCE ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu/psx_gpu_simd.c APPEND
    PROPERTY COMPILE_DEFINITIONS SIMD_BUILD)
set_property(SOURCE ${PCSX_CHDR_DIR}/deps/miniz-3.1.1/miniz.c APPEND
    PROPERTY COMPILE_DEFINITIONS
        MINIZ_NO_STDIO MINIZ_NO_DEFLATE_APIS MINIZ_NO_ARCHIVE_APIS)

set_property(SOURCE ${PCSX_LIGHTNING_SOURCES} APPEND
    PROPERTY COMPILE_OPTIONS -Wno-uninitialized)
set_property(SOURCE
    ${PCSX_LIGHTREC_DIR}/blockcache.c
    ${PCSX_LIGHTREC_DIR}/constprop.c
    ${PCSX_LIGHTREC_DIR}/disassembler.c
    ${PCSX_LIGHTREC_DIR}/emitter.c
    ${PCSX_LIGHTREC_DIR}/interpreter.c
    ${PCSX_LIGHTREC_DIR}/lightrec.c
    ${PCSX_LIGHTREC_DIR}/memmanager.c
    ${PCSX_LIGHTREC_DIR}/optimizer.c
    ${PCSX_LIGHTREC_DIR}/regcache.c
    APPEND PROPERTY COMPILE_OPTIONS -Wno-uninitialized)

if(NOT APPLE)
    target_link_options(pcsx_rearmed_core PRIVATE
        "-Wl,--version-script=${PCSX_FRONTEND_DIR}/libretro-version-script"
        "-Wl,--gc-sections"
        "-Wl,--no-undefined")
endif()
target_link_libraries(pcsx_rearmed_core pthread m dl)
if(NOT APPLE)
    target_link_libraries(pcsx_rearmed_core rt)
else()
    target_link_libraries(pcsx_rearmed_core
        "-framework CoreFoundation" "-framework IOKit")
endif()

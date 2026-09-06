# PCSX-ReARMed Windows x86_64 interpreter candidate. This target remains
# isolated under cores-candidate/ until physical Windows qualification.
set(ROMM_PCSX_REARMED_SOURCES_ONLY ON)
include(${CMAKE_CURRENT_LIST_DIR}/pcsx_rearmed-linux.cmake)
unset(ROMM_PCSX_REARMED_SOURCES_ONLY)

# The first Windows candidate deliberately excludes Lightrec/GNU Lightning,
# executable memory, and host physical-CD access.
list(REMOVE_ITEM PCSX_REARMED_SOURCES
    ${PCSX_CORE_DIR}/lightrec/mem.c
    ${PCSX_CORE_DIR}/lightrec/plugin.c
    ${PCSX_LIGHTREC_DIR}/tlsf/tlsf.c
    ${PCSX_LIGHTREC_DIR}/blockcache.c
    ${PCSX_LIGHTREC_DIR}/constprop.c
    ${PCSX_LIGHTREC_DIR}/disassembler.c
    ${PCSX_LIGHTREC_DIR}/emitter.c
    ${PCSX_LIGHTREC_DIR}/interpreter.c
    ${PCSX_LIGHTREC_DIR}/lightrec.c
    ${PCSX_LIGHTREC_DIR}/memmanager.c
    ${PCSX_LIGHTREC_DIR}/optimizer.c
    ${PCSX_LIGHTREC_DIR}/regcache.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_disasm.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_memory.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_names.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_note.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_print.c
    ${PCSX_LIGHTNING_DIR}/lib/jit_size.c
    ${PCSX_LIGHTNING_DIR}/lib/lightning.c
    ${PCSX_FRONTEND_DIR}/libretro-cdrom.c
    ${PCSX_COMMON_DIR}/vfs/vfs_implementation_cdrom.c
)

add_library(pcsx_rearmed_core SHARED
    ${PCSX_REARMED_SOURCES}
    ${CMAKE_CURRENT_LIST_DIR}/pcsx_rearmed-windows.def
)

set_target_properties(pcsx_rearmed_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    PREFIX ""
)

target_include_directories(pcsx_rearmed_core SYSTEM PRIVATE
    ${PCSX_REARMED_DIR}/include
    ${PCSX_COMMON_DIR}/include
    ${PCSX_CHDR_DIR}/include
    ${PCSX_CHDR_DIR}/deps/lzma-25.01/include
    ${PCSX_CHDR_DIR}/deps/zstd-1.5.7
    ${PCSX_CHDR_DIR}/deps/miniz-3.1.1
)

target_compile_definitions(pcsx_rearmed_core PRIVATE
    NDEBUG
    DRC_DISABLE
    P_HAVE_MMAP=0
    P_HAVE_POSIX_MEMALIGN=0
    DISABLE_MEM_LUTS=0
    GPU_NEON
    HAVE_CHD
    USE_MINIZ
    USE_LIBRETRO_VFS
    HAVE_LIBRETRO
    NO_FRONTEND
    BUILTIN_GPU=neon
    GIT_VERSION=" da2cb8e"
    RETRO_API=
)

target_compile_options(pcsx_rearmed_core PRIVATE
    -O3
    -ffast-math
    -ffunction-sections
    -fdata-sections
    -mssse3
)

set(PCSX_WINDOWS_DFSOUND_SOURCES
    ${PCSX_PLUGINS_DIR}/dfsound/dma.c
    ${PCSX_PLUGINS_DIR}/dfsound/freeze.c
    ${PCSX_PLUGINS_DIR}/dfsound/registers.c
    ${PCSX_PLUGINS_DIR}/dfsound/spu.c
    ${PCSX_PLUGINS_DIR}/dfsound/out.c
    ${PCSX_PLUGINS_DIR}/dfsound/nullsnd.c
)
set(PCSX_WINDOWS_GPULIB_SOURCES
    ${PCSX_PLUGINS_DIR}/gpulib/gpu.c
    ${PCSX_PLUGINS_DIR}/gpulib/vout_pl.c
    ${PCSX_PLUGINS_DIR}/gpulib/prim.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu_async.c
)

set_property(SOURCE ${PCSX_CORE_DIR}/cdrom-async.c APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_CDROM)
set_property(SOURCE ${PCSX_FRONTEND_DIR}/libretro.c APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_CDROM USE_ASYNC_GPU)
set_property(SOURCE ${PCSX_WINDOWS_GPULIB_SOURCES} APPEND
    PROPERTY COMPILE_DEFINITIONS USE_ASYNC_GPU)
set_property(SOURCE ${PCSX_FRONTEND_DIR}/main.c APPEND
    PROPERTY COMPILE_DEFINITIONS HAVE_RTHREADS)
set_property(SOURCE ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu_if.c APPEND
    PROPERTY COMPILE_DEFINITIONS
        NEON_BUILD TEXTURE_CACHE_4BPP TEXTURE_CACHE_8BPP SIMD_BUILD)
set_property(SOURCE ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu/psx_gpu_simd.c APPEND
    PROPERTY COMPILE_DEFINITIONS SIMD_BUILD)
set_property(SOURCE ${PCSX_CHDR_DIR}/deps/miniz-3.1.1/miniz.c APPEND
    PROPERTY COMPILE_DEFINITIONS
        MINIZ_NO_STDIO MINIZ_NO_DEFLATE_APIS MINIZ_NO_ARCHIVE_APIS)

target_link_options(pcsx_rearmed_core PRIVATE
    "-Wl,--gc-sections"
    "-Wl,--no-undefined"
)

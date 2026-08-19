# ---------------------------------------------------------------------------
# PCSX-ReARMed (GPL-2.0-or-later; GPL-3.0-or-later combined Linux target):
# Linux x86_64 libretro build pinned to
# da2cb8ecd17fd0932ab6d94774c0522beebce6e3. This mirrors the default
# platform=unix Makefile.libretro build: Lightrec with GNU Lightning, the
# SSSE3 software renderer, physical CD-ROM support, libretro VFS, and CHD.
# ---------------------------------------------------------------------------

if(NOT DEFINED PCSX_REARMED_DIR)
    if(DEFINED ROMM_REPO_ROOT)
        set(PCSX_REARMED_DIR ${ROMM_REPO_ROOT}/third_party/cores/pcsx_rearmed)
    else()
        get_filename_component(
            PCSX_REARMED_DIR
            ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/cores/pcsx_rearmed
            ABSOLUTE
        )
    endif()
endif()

set(PCSX_CORE_DIR ${PCSX_REARMED_DIR}/libpcsxcore)
set(PCSX_FRONTEND_DIR ${PCSX_REARMED_DIR}/frontend)
set(PCSX_PLUGINS_DIR ${PCSX_REARMED_DIR}/plugins)
set(PCSX_COMMON_DIR ${PCSX_REARMED_DIR}/deps/libretro-common)
set(PCSX_CHDR_DIR ${PCSX_REARMED_DIR}/deps/libchdr)
set(PCSX_LIGHTREC_DIR ${PCSX_REARMED_DIR}/deps/lightrec)
set(PCSX_LIGHTNING_DIR ${PCSX_REARMED_DIR}/deps/lightning)

set(PCSX_REARMED_SOURCES
    ${PCSX_CORE_DIR}/cdriso.c
    ${PCSX_CORE_DIR}/cdrom.c
    ${PCSX_CORE_DIR}/cdrom-async.c
    ${PCSX_CORE_DIR}/cheat.c
    ${PCSX_CORE_DIR}/database.c
    ${PCSX_CORE_DIR}/decode_xa.c
    ${PCSX_CORE_DIR}/mdec.c
    ${PCSX_CORE_DIR}/misc.c
    ${PCSX_CORE_DIR}/plugins.c
    ${PCSX_CORE_DIR}/ppf.c
    ${PCSX_CORE_DIR}/psxbios.c
    ${PCSX_CORE_DIR}/psxcommon.c
    ${PCSX_CORE_DIR}/psxcounters.c
    ${PCSX_CORE_DIR}/psxdma.c
    ${PCSX_CORE_DIR}/psxhw.c
    ${PCSX_CORE_DIR}/psxinterpreter.c
    ${PCSX_CORE_DIR}/psxmem.c
    ${PCSX_CORE_DIR}/psxevents.c
    ${PCSX_CORE_DIR}/r3000a.c
    ${PCSX_CORE_DIR}/sio.c
    ${PCSX_CORE_DIR}/spu.c
    ${PCSX_CORE_DIR}/gpu.c
    ${PCSX_CORE_DIR}/pad.c
    ${PCSX_CORE_DIR}/gte.c
    ${PCSX_CORE_DIR}/gte_nf.c
    ${PCSX_CORE_DIR}/gte_divider.c
    ${PCSX_CORE_DIR}/lightrec/mem.c
    ${PCSX_CORE_DIR}/lightrec/plugin.c
    ${PCSX_CORE_DIR}/new_dynarec/emu_if.c
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
    ${PCSX_PLUGINS_DIR}/dfsound/dma.c
    ${PCSX_PLUGINS_DIR}/dfsound/freeze.c
    ${PCSX_PLUGINS_DIR}/dfsound/registers.c
    ${PCSX_PLUGINS_DIR}/dfsound/spu.c
    ${PCSX_PLUGINS_DIR}/dfsound/out.c
    ${PCSX_PLUGINS_DIR}/dfsound/nullsnd.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu.c
    ${PCSX_PLUGINS_DIR}/gpulib/vout_pl.c
    ${PCSX_PLUGINS_DIR}/gpulib/prim.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu_async.c
    ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu_if.c
    ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu/psx_gpu_simd.c
    ${PCSX_CHDR_DIR}/src/libchdr_bitstream.c
    ${PCSX_CHDR_DIR}/src/libchdr_cdrom.c
    ${PCSX_CHDR_DIR}/src/libchdr_chd.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_cdfl.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_cdlz.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_cdzl.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_cdzs.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_flac.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_huff.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_lzma.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_zlib.c
    ${PCSX_CHDR_DIR}/src/libchdr_codec_zstd.c
    ${PCSX_CHDR_DIR}/src/libchdr_flac.c
    ${PCSX_CHDR_DIR}/src/libchdr_huffman.c
    ${PCSX_CHDR_DIR}/deps/lzma-25.01/src/LzmaDec.c
    ${PCSX_CHDR_DIR}/deps/zstd-1.5.7/zstddeclib.c
    ${PCSX_CHDR_DIR}/deps/miniz-3.1.1/miniz.c
    ${PCSX_FRONTEND_DIR}/cspace.c
    ${PCSX_FRONTEND_DIR}/libretro-cdrom.c
    ${PCSX_FRONTEND_DIR}/libretro.c
    ${PCSX_FRONTEND_DIR}/pcsxr-threads.c
    ${PCSX_FRONTEND_DIR}/main.c
    ${PCSX_FRONTEND_DIR}/plugin.c
    ${PCSX_COMMON_DIR}/compat/compat_strl.c
    ${PCSX_COMMON_DIR}/file/file_path.c
    ${PCSX_COMMON_DIR}/file/file_path_io.c
    ${PCSX_COMMON_DIR}/string/stdstring.c
    ${PCSX_COMMON_DIR}/vfs/vfs_implementation.c
    ${PCSX_COMMON_DIR}/lists/string_list.c
    ${PCSX_COMMON_DIR}/memmap/memalign.c
    ${PCSX_COMMON_DIR}/vfs/vfs_implementation_cdrom.c
    ${PCSX_COMMON_DIR}/compat/compat_posix_string.c
    ${PCSX_COMMON_DIR}/compat/fopen_utf8.c
    ${PCSX_COMMON_DIR}/encodings/encoding_utf.c
    ${PCSX_COMMON_DIR}/file/retro_dirent.c
    ${PCSX_COMMON_DIR}/streams/file_stream.c
    ${PCSX_COMMON_DIR}/streams/file_stream_transforms.c
    ${PCSX_COMMON_DIR}/time/rtime.c
    ${PCSX_COMMON_DIR}/features/features_cpu.c
)

# Apple Silicon verification build: Lightrec/Lightning are x86_64-only JITs
# (lightrec/mem.c uses Linux memfd/hugetlb unconditionally), so drop them and
# the dynarec glue; the core then falls back to its built-in interpreter via
# DRC_DISABLE (see the definitions block below).
if(APPLE)
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
)
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
    LIGHTREC
    LIGHTREC_STATIC
    LIGHTREC_CUSTOM_MAP=1
    LIGHTREC_CODE_INV=0
    LIGHTREC_ENABLE_THREADED_COMPILER=0
    LIGHTREC_ENABLE_DISASSEMBLER=0
    LIGHTREC_NO_DEBUG=1
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

# Apple-only: drop the Lightrec x86_64 JIT defines (its sources are excluded
# above) and select DRC_DISABLE so r3000a.c uses the built-in interpreter.
if(APPLE)
list(REMOVE_ITEM PCSX_REARMED_DEFINES
    LIGHTREC
    LIGHTREC_STATIC
    LIGHTREC_CUSTOM_MAP=1
    LIGHTREC_CODE_INV=0
    LIGHTREC_ENABLE_THREADED_COMPILER=0
    LIGHTREC_ENABLE_DISASSEMBLER=0
    LIGHTREC_NO_DEBUG=1
)
list(APPEND PCSX_REARMED_DEFINES DRC_DISABLE)
endif()

target_compile_definitions(pcsx_rearmed_core PRIVATE ${PCSX_REARMED_DEFINES})

target_compile_options(pcsx_rearmed_core PRIVATE
    -O3
    -ffast-math
    -ffunction-sections
    -fdata-sections
)

# x86_64-only tuning; not applicable to Apple Silicon verification builds.
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

# GNU-only linker flags (Linux per-core gate); Apple ld does not support them.
if(NOT APPLE)
target_link_options(pcsx_rearmed_core PRIVATE
    "-Wl,--version-script=${PCSX_FRONTEND_DIR}/libretro-version-script"
    "-Wl,--gc-sections"
    "-Wl,--no-undefined"
)
endif()

target_link_libraries(pcsx_rearmed_core
    pthread
    m
    dl
)

# librt has no macOS counterpart (its symbols live in libSystem); the Apple
# cdrom.c path instead needs IOKit/CoreFoundation for physical CD-ROM.
if(NOT APPLE)
target_link_libraries(pcsx_rearmed_core rt)
else()
target_link_libraries(pcsx_rearmed_core
    "-framework CoreFoundation"
    "-framework IOKit"
)
endif()

# ---------------------------------------------------------------------------
# PCSX-ReARMed (GPL-2.0-or-later overall): pinned to upstream commit
# da2cb8ecd17fd0932ab6d94774c0522beebce6e3. The source list, ARM ari64
# dynarec, NEON renderer, asynchronous subsystems, libretro VFS, and CHD
# dependency closure mirror upstream jni/Android.mk.
# ---------------------------------------------------------------------------
set(PCSX_REARMED_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/pcsx_rearmed)
set(PCSX_CORE_DIR ${PCSX_REARMED_DIR}/libpcsxcore)
set(PCSX_FRONTEND_DIR ${PCSX_REARMED_DIR}/frontend)
set(PCSX_PLUGINS_DIR ${PCSX_REARMED_DIR}/plugins)
set(PCSX_COMMON_DIR ${PCSX_REARMED_DIR}/deps/libretro-common)
set(PCSX_CHDR_DIR ${PCSX_REARMED_DIR}/deps/libchdr)

set(PCSX_REARMED_SOURCES
    ${PCSX_CORE_DIR}/cdriso.c
    ${PCSX_CORE_DIR}/cdrom.c
    ${PCSX_CORE_DIR}/cdrom-async.c
    ${PCSX_CORE_DIR}/cheat.c
    ${PCSX_CORE_DIR}/database.c
    ${PCSX_CORE_DIR}/decode_xa.c
    ${PCSX_CORE_DIR}/mdec.c
    ${PCSX_CORE_DIR}/misc.c
    ${PCSX_CORE_DIR}/pad.c
    ${PCSX_CORE_DIR}/plugins.c
    ${PCSX_CORE_DIR}/ppf.c
    ${PCSX_CORE_DIR}/psxbios.c
    ${PCSX_CORE_DIR}/psxcommon.c
    ${PCSX_CORE_DIR}/psxcounters.c
    ${PCSX_CORE_DIR}/psxdma.c
    ${PCSX_CORE_DIR}/psxevents.c
    ${PCSX_CORE_DIR}/psxhw.c
    ${PCSX_CORE_DIR}/psxinterpreter.c
    ${PCSX_CORE_DIR}/psxmem.c
    ${PCSX_CORE_DIR}/r3000a.c
    ${PCSX_CORE_DIR}/sio.c
    ${PCSX_CORE_DIR}/spu.c
    ${PCSX_CORE_DIR}/gpu.c
    ${PCSX_CORE_DIR}/gte.c
    ${PCSX_CORE_DIR}/gte_nf.c
    ${PCSX_CORE_DIR}/gte_divider.c
    ${PCSX_CORE_DIR}/new_dynarec/new_dynarec.c
    ${PCSX_CORE_DIR}/new_dynarec/pcsxmem.c
    ${PCSX_CORE_DIR}/new_dynarec/emu_if.c
    ${PCSX_PLUGINS_DIR}/dfsound/dma.c
    ${PCSX_PLUGINS_DIR}/dfsound/freeze.c
    ${PCSX_PLUGINS_DIR}/dfsound/registers.c
    ${PCSX_PLUGINS_DIR}/dfsound/spu.c
    ${PCSX_PLUGINS_DIR}/dfsound/out.c
    ${PCSX_PLUGINS_DIR}/dfsound/nullsnd.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu.c
    ${PCSX_PLUGINS_DIR}/gpulib/prim.c
    ${PCSX_PLUGINS_DIR}/gpulib/vout_pl.c
    ${PCSX_PLUGINS_DIR}/gpulib/gpu_async.c
    ${PCSX_PLUGINS_DIR}/cdrcimg/cdrcimg.c
    ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu_if.c
    ${PCSX_FRONTEND_DIR}/main.c
    ${PCSX_FRONTEND_DIR}/plugin.c
    ${PCSX_FRONTEND_DIR}/cspace.c
    ${PCSX_FRONTEND_DIR}/libretro.c
    ${PCSX_FRONTEND_DIR}/pcsxr-threads.c
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
    ${PCSX_COMMON_DIR}/compat/compat_posix_string.c
    ${PCSX_COMMON_DIR}/compat/fopen_utf8.c
    ${PCSX_COMMON_DIR}/compat/compat_strl.c
    ${PCSX_COMMON_DIR}/encodings/encoding_utf.c
    ${PCSX_COMMON_DIR}/file/file_path.c
    ${PCSX_COMMON_DIR}/file/file_path_io.c
    ${PCSX_COMMON_DIR}/file/retro_dirent.c
    ${PCSX_COMMON_DIR}/streams/file_stream.c
    ${PCSX_COMMON_DIR}/streams/file_stream_transforms.c
    ${PCSX_COMMON_DIR}/string/stdstring.c
    ${PCSX_COMMON_DIR}/time/rtime.c
    ${PCSX_COMMON_DIR}/vfs/vfs_implementation.c
    ${PCSX_COMMON_DIR}/features/features_cpu.c
)

if(ANDROID_ABI STREQUAL "armeabi-v7a")
    list(APPEND PCSX_REARMED_SOURCES
        ${PCSX_CORE_DIR}/gte_arm.S
        ${PCSX_CORE_DIR}/gte_nf_arm.S
        ${PCSX_CORE_DIR}/gte_neon.S
        ${PCSX_CORE_DIR}/gte_nf_neon.S
        ${PCSX_CORE_DIR}/new_dynarec/linkage_arm.S
        ${PCSX_PLUGINS_DIR}/dfsound/arm_utils.S
        ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu/psx_gpu_arm_neon.S
        ${PCSX_FRONTEND_DIR}/cspace_neon.S
    )
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    list(APPEND PCSX_REARMED_SOURCES
        ${PCSX_CORE_DIR}/gte_arm64.S
        ${PCSX_CORE_DIR}/gte_nf_arm64.S
        ${PCSX_CORE_DIR}/new_dynarec/linkage_arm64.S
        ${PCSX_PLUGINS_DIR}/gpu_neon/psx_gpu/psx_gpu_simd.c
        ${PCSX_CHDR_DIR}/deps/lzma-25.01/Asm/arm64/LzmaDecOpt.S
    )
endif()

add_library(pcsx_rearmed_core SHARED ${PCSX_REARMED_SOURCES})

target_include_directories(pcsx_rearmed_core SYSTEM PRIVATE
    ${PCSX_REARMED_DIR}/include
    ${PCSX_REARMED_DIR}/deps/crypto
    ${PCSX_COMMON_DIR}/include
    ${PCSX_CHDR_DIR}/include
    ${PCSX_CHDR_DIR}/deps/lzma-25.01/include
    ${PCSX_CHDR_DIR}/deps/zstd-1.5.7
)

target_compile_definitions(pcsx_rearmed_core PRIVATE
    HAVE_CHD
    CHDR_SYSTEM_ZLIB
    HAVE_LIBRETRO
    NO_FRONTEND
    ANDROID
    REARMED
    P_HAVE_MMAP=1
    P_HAVE_PTHREAD=1
    P_HAVE_POSIX_MEMALIGN=1
    USE_LIBRETRO_VFS
    NDRC_THREAD
    NEON_BUILD
    TEXTURE_CACHE_4BPP
    TEXTURE_CACHE_8BPP
    GPU_NEON
    HAVE_RTHREADS
    USE_ASYNC_CDROM
    USE_ASYNC_GPU
    USE_ASYNC_SPU
    LIGHTREC_CUSTOM_MAP=0
    LIGHTREC_ENABLE_THREADED_COMPILER=0
    LIGHTREC_ENABLE_DISASSEMBLER=0
    LIGHTREC_NO_DEBUG=1
    GIT_VERSION=" da2cb8e"
)

target_compile_options(pcsx_rearmed_core PRIVATE -ffast-math -ffunction-sections -fdata-sections)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_definitions(pcsx_rearmed_core PRIVATE HAVE_bgr555_to_rgb565 HAVE_bgr888_to_x)
    target_compile_options(pcsx_rearmed_core PRIVATE -marm -mfpu=neon)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    target_compile_definitions(pcsx_rearmed_core PRIVATE SIMD_BUILD Z7_LZMA_DEC_OPT)
endif()

target_link_options(pcsx_rearmed_core PRIVATE
    "-Wl,--version-script=${PCSX_FRONTEND_DIR}/libretro-version-script"
    "-Wl,--script=${PCSX_FRONTEND_DIR}/libretro-extern.T"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
    "-Wl,--no-undefined"
)

target_link_libraries(pcsx_rearmed_core log m z)

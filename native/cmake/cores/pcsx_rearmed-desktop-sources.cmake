# Shared desktop inventory only: no targets, platform definitions or flags.
# Linux adds Lightrec/Lightning; Apple and Windows use the interpreter.
if(NOT DEFINED PCSX_REARMED_DIR)
    if(DEFINED ROMM_REPO_ROOT)
        set(PCSX_REARMED_DIR ${ROMM_REPO_ROOT}/third_party/cores/pcsx_rearmed)
    else()
        get_filename_component(PCSX_REARMED_DIR
            ${CMAKE_CURRENT_LIST_DIR}/../../../third_party/cores/pcsx_rearmed ABSOLUTE)
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
    ${PCSX_CORE_DIR}/new_dynarec/emu_if.c
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
    ${PCSX_COMMON_DIR}/compat/compat_posix_string.c
    ${PCSX_COMMON_DIR}/compat/fopen_utf8.c
    ${PCSX_COMMON_DIR}/encodings/encoding_utf.c
    ${PCSX_COMMON_DIR}/file/retro_dirent.c
    ${PCSX_COMMON_DIR}/streams/file_stream.c
    ${PCSX_COMMON_DIR}/streams/file_stream_transforms.c
    ${PCSX_COMMON_DIR}/time/rtime.c
    ${PCSX_COMMON_DIR}/features/features_cpu.c
)
set(PCSX_REARMED_LIGHTREC_SOURCES
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
set(PCSX_REARMED_CDROM_SOURCES
    ${PCSX_FRONTEND_DIR}/libretro-cdrom.c
    ${PCSX_COMMON_DIR}/vfs/vfs_implementation_cdrom.c
)

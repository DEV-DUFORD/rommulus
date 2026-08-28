# ---------------------------------------------------------------------------
# mupen64plus_next (Nintendo 64): vendored under third_party/cores/mupen64plus_next/
# (libretro/mupen64plus-libretro-nx develop @ 98c1b0d, with git-subrepo
# components; see its VENDORING.md). Source list and preprocessor flags mirror
# upstream libretro/jni/Android.mk + Makefile.common exactly, built with the
# GLES3 override (the target TV exposes OpenGL ES 3.2) plus LLE, paraLLEl
# RSP/RDP, THR_AL (Angrylion) for BOTH ABIs. armeabi-v7a additionally gets the
# ARM dynarec (NEW_DYNAREC=3) + NEON renderer; arm64-v8a the ARM64 dynarec
# (NEW_DYNAREC=4) + generic 3DMath. Upstream Android.mk sets -std=gnu++11, but
# the paraLLEl RSP/RDP closure requires C++14/17 (std::make_unique etc.), so
# CXX is pinned to 17 (documented deviation; C stays gnu11).
# ---------------------------------------------------------------------------
set(MUPEN64_DIR ${ROMM_APP_CPP_DIR}/../../../../third_party/cores/mupen64plus_next)
set(M64_CORE_DIR ${MUPEN64_DIR}/mupen64plus-core)
set(M64_RSP_HLE_DIR ${MUPEN64_DIR}/mupen64plus-rsp-hle)
set(M64_CXD4_DIR ${MUPEN64_DIR}/mupen64plus-rsp-cxd4)
set(M64_GLIDEN64_DIR ${MUPEN64_DIR}/GLideN64)
set(M64_ANGRYLION_DIR ${MUPEN64_DIR}/mupen64plus-video-angrylion)
set(M64_PARALLEL_DIR ${MUPEN64_DIR}/mupen64plus-video-paraLLEl)
set(M64_PARALLEL_RDP_DIR ${M64_PARALLEL_DIR}/parallel-rdp)
set(M64_RSP_PARALLEL_DIR ${MUPEN64_DIR}/mupen64plus-rsp-paraLLEl)
set(M64_COMM_DIR ${MUPEN64_DIR}/libretro-common)
set(M64_LIBRETRO_DIR ${MUPEN64_DIR}/libretro)
set(M64_AUDIO_LIBRETRO_DIR ${MUPEN64_DIR}/custom/mupen64plus-core/plugin/audio_libretro)
set(M64_MINIZIP_DIR ${M64_CORE_DIR}/subprojects/minizip)
set(M64_LIBPNG_DIR ${MUPEN64_DIR}/custom/dependencies/libpng)
set(M64_ZLIB_DIR ${MUPEN64_DIR}/custom/dependencies/libzlib)
set(M64_XXHASH_DIR ${MUPEN64_DIR}/xxHash)

set(M64_SOURCES_C
    # mupen64plus-core (SOURCES_C, Makefile.common lines 37-100)
    ${M64_CORE_DIR}/src/asm_defines/asm_defines.c
    ${M64_CORE_DIR}/src/api/callbacks.c
    ${MUPEN64_DIR}/custom/mupen64plus-core/api/config.c
    ${M64_CORE_DIR}/src/api/debugger.c
    ${M64_CORE_DIR}/src/api/frontend.c
    ${M64_CORE_DIR}/src/backends/plugins_compat/audio_plugin_compat.c
    ${M64_CORE_DIR}/src/backends/api/video_capture_backend.c
    ${M64_CORE_DIR}/src/backends/plugins_compat/input_plugin_compat.c
    ${M64_CORE_DIR}/src/backends/clock_ctime_plus_delta.c
    ${M64_CORE_DIR}/src/backends/dummy_video_capture.c
    ${M64_CORE_DIR}/src/backends/file_storage.c
    ${M64_CORE_DIR}/src/device/cart/cart.c
    ${M64_CORE_DIR}/src/device/cart/af_rtc.c
    ${M64_CORE_DIR}/src/device/cart/cart_rom.c
    ${M64_CORE_DIR}/src/device/cart/eeprom.c
    ${M64_CORE_DIR}/src/device/cart/flashram.c
    ${M64_CORE_DIR}/src/device/cart/is_viewer.c
    ${M64_CORE_DIR}/src/device/cart/sram.c
    ${M64_CORE_DIR}/src/device/controllers/game_controller.c
    ${M64_CORE_DIR}/src/device/controllers/vru_controller.c
    ${M64_CORE_DIR}/src/device/controllers/paks/biopak.c
    ${M64_CORE_DIR}/src/device/controllers/paks/mempak.c
    ${M64_CORE_DIR}/src/device/controllers/paks/rumblepak.c
    ${M64_CORE_DIR}/src/device/controllers/paks/transferpak.c
    ${M64_CORE_DIR}/src/device/dd/dd_controller.c
    ${M64_CORE_DIR}/src/device/dd/disk.c
    ${M64_CORE_DIR}/src/device/device.c
    ${M64_CORE_DIR}/src/device/gb/gb_cart.c
    ${M64_CORE_DIR}/src/device/gb/mbc3_rtc.c
    ${M64_CORE_DIR}/src/device/gb/m64282fp.c
    ${M64_CORE_DIR}/src/device/memory/memory.c
    ${M64_CORE_DIR}/src/device/pif/bootrom_hle.c
    ${M64_CORE_DIR}/src/device/pif/cic.c
    ${M64_CORE_DIR}/src/device/pif/n64_cic_nus_6105.c
    ${M64_CORE_DIR}/src/device/pif/pif.c
    ${M64_CORE_DIR}/src/device/r4300/cached_interp.c
    ${M64_CORE_DIR}/src/device/r4300/cp0.c
    ${M64_CORE_DIR}/src/device/r4300/cp1.c
    ${M64_CORE_DIR}/src/device/r4300/cp2.c
    ${M64_CORE_DIR}/src/device/r4300/idec.c
    ${M64_CORE_DIR}/src/device/r4300/interrupt.c
    ${M64_CORE_DIR}/src/device/r4300/pure_interp.c
    ${M64_CORE_DIR}/src/device/r4300/r4300_core.c
    ${M64_CORE_DIR}/src/device/r4300/tlb.c
    ${M64_CORE_DIR}/src/device/rcp/ai/ai_controller.c
    ${M64_CORE_DIR}/src/device/rcp/mi/mi_controller.c
    ${M64_CORE_DIR}/src/device/rcp/pi/pi_controller.c
    ${M64_CORE_DIR}/src/device/rcp/rdp/fb.c
    ${M64_CORE_DIR}/src/device/rcp/rdp/rdp_core.c
    ${M64_CORE_DIR}/src/device/rcp/ri/ri_controller.c
    ${M64_CORE_DIR}/src/device/rcp/rsp/rsp_core.c
    ${M64_CORE_DIR}/src/device/rcp/si/si_controller.c
    ${M64_CORE_DIR}/src/device/rcp/vi/vi_controller.c
    ${M64_CORE_DIR}/src/device/rdram/rdram.c
    ${M64_CORE_DIR}/src/main/main.c
    ${M64_CORE_DIR}/src/main/util.c
    ${M64_CORE_DIR}/src/main/cheat.c
    ${M64_CORE_DIR}/src/main/rom.c
    ${M64_CORE_DIR}/src/main/savestates.c
    ${M64_CORE_DIR}/src/plugin/plugin.c
    ${M64_CORE_DIR}/src/plugin/dummy_audio.c
    ${M64_CORE_DIR}/src/plugin/dummy_input.c
    # minizip (lines 102-105)
    ${M64_MINIZIP_DIR}/zip.c
    ${M64_MINIZIP_DIR}/unzip.c
    ${M64_MINIZIP_DIR}/ioapi.c
    # bundled libpng (lines 107-122)
    ${M64_LIBPNG_DIR}/png.c
    ${M64_LIBPNG_DIR}/pngerror.c
    ${M64_LIBPNG_DIR}/pngget.c
    ${M64_LIBPNG_DIR}/pngmem.c
    ${M64_LIBPNG_DIR}/pngpread.c
    ${M64_LIBPNG_DIR}/pngread.c
    ${M64_LIBPNG_DIR}/pngrio.c
    ${M64_LIBPNG_DIR}/pngrtran.c
    ${M64_LIBPNG_DIR}/pngrutil.c
    ${M64_LIBPNG_DIR}/pngset.c
    ${M64_LIBPNG_DIR}/pngtrans.c
    ${M64_LIBPNG_DIR}/pngwio.c
    ${M64_LIBPNG_DIR}/pngwrite.c
    ${M64_LIBPNG_DIR}/pngwtran.c
    ${M64_LIBPNG_DIR}/pngwutil.c
    # bundled zlib (lines 124-139)
    ${M64_ZLIB_DIR}/adler32.c
    ${M64_ZLIB_DIR}/compress.c
    ${M64_ZLIB_DIR}/crc32.c
    ${M64_ZLIB_DIR}/deflate.c
    ${M64_ZLIB_DIR}/gzclose.c
    ${M64_ZLIB_DIR}/gzlib.c
    ${M64_ZLIB_DIR}/gzread.c
    ${M64_ZLIB_DIR}/gzwrite.c
    ${M64_ZLIB_DIR}/infback.c
    ${M64_ZLIB_DIR}/inffast.c
    ${M64_ZLIB_DIR}/inflate.c
    ${M64_ZLIB_DIR}/inftrees.c
    ${M64_ZLIB_DIR}/trees.c
    ${M64_ZLIB_DIR}/uncompr.c
    ${M64_ZLIB_DIR}/zutil.c
    # md5 (lines 145-146)
    ${M64_CORE_DIR}/subprojects/md5/md5.c
    # GLideN64 osal (line 187; android => unix)
    ${M64_GLIDEN64_DIR}/src/osal/osal_files_unix.c
    # libretro frontend + libretro-common helpers (lines 234-259)
    ${M64_LIBRETRO_DIR}/libretro.c
    ${M64_COMM_DIR}/memmap/memalign.c
    ${MUPEN64_DIR}/custom/mupen64plus-core/plugin/emulate_game_controller_via_libretro.c
    ${M64_COMM_DIR}/audio/resampler/drivers/sinc_resampler.c
    ${M64_COMM_DIR}/audio/resampler/drivers/nearest_resampler.c
    ${M64_COMM_DIR}/audio/resampler/audio_resampler.c
    ${M64_AUDIO_LIBRETRO_DIR}/audio_backend_libretro.c
    ${M64_COMM_DIR}/file/config_file.c
    ${M64_COMM_DIR}/file/config_file_userdata.c
    ${M64_COMM_DIR}/file/file_path.c
    ${M64_COMM_DIR}/file/file_path_io.c
    ${M64_COMM_DIR}/time/rtime.c
    ${M64_COMM_DIR}/compat/compat_strl.c
    ${M64_COMM_DIR}/compat/compat_posix_string.c
    ${M64_COMM_DIR}/compat/compat_strcasestr.c
    ${M64_COMM_DIR}/audio/conversion/float_to_s16.c
    ${M64_COMM_DIR}/audio/conversion/s16_to_float.c
    ${M64_COMM_DIR}/features/features_cpu.c
    ${M64_COMM_DIR}/lists/string_list.c
    ${M64_COMM_DIR}/encodings/encoding_utf.c
    ${M64_COMM_DIR}/string/stdstring.c
    ${M64_COMM_DIR}/vfs/vfs_implementation.c
    ${M64_COMM_DIR}/streams/file_stream.c
    ${M64_COMM_DIR}/compat/fopen_utf8.c
    ${MUPEN64_DIR}/custom/mupen64plus-core/api/vidext_libretro.c
    ${M64_COMM_DIR}/glsm/glsm.c
    # Angrylion renderer (HAVE_THR_AL, lines 398-401)
    ${M64_ANGRYLION_DIR}/interface.c
    ${M64_ANGRYLION_DIR}/n64video.c
    # rsp-hle (lines 403-417)
    ${M64_RSP_HLE_DIR}/src/alist.c
    ${M64_RSP_HLE_DIR}/src/alist_audio.c
    ${M64_RSP_HLE_DIR}/src/alist_naudio.c
    ${M64_RSP_HLE_DIR}/src/alist_nead.c
    ${M64_RSP_HLE_DIR}/src/audio.c
    ${M64_RSP_HLE_DIR}/src/cicx105.c
    ${M64_RSP_HLE_DIR}/src/hle.c
    ${M64_RSP_HLE_DIR}/src/hvqm.c
    ${M64_RSP_HLE_DIR}/src/jpeg.c
    ${M64_RSP_HLE_DIR}/src/memory.c
    ${M64_RSP_HLE_DIR}/src/mp3.c
    ${M64_RSP_HLE_DIR}/src/musyx.c
    ${M64_RSP_HLE_DIR}/src/re2.c
    ${M64_RSP_HLE_DIR}/src/plugin.c
    # cxd4 low-level RSP (LLE=1, lines 419-421)
    ${M64_CXD4_DIR}/rsp.c
    # paraLLEl RDP (HAVE_PARALLEL_RDP, line 430): volk
    ${M64_PARALLEL_RDP_DIR}/volk/volk.c
    # paraLLEl RSP (HAVE_PARALLEL_RSP, non-DEBUG_JIT, lines 448-455): lightning
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_disasm.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_memory.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_names.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_note.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_print.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/jit_size.c
    ${M64_RSP_PARALLEL_DIR}/lightning/lib/lightning.c
    # libco (line 468)
    ${M64_COMM_DIR}/libco/libco.c
    # GLES3 glsym (line 500) + rglgen (line 521)
    ${M64_COMM_DIR}/glsym/glsym_es3.c
    ${M64_COMM_DIR}/glsym/rglgen.c
)

set(M64_SOURCES_CXX
    # GLideN64 graphics plugin (Makefile.common lines 262-372)
    ${M64_GLIDEN64_DIR}/src/Combiner.cpp
    ${M64_GLIDEN64_DIR}/src/CombinerKey.cpp
    ${M64_GLIDEN64_DIR}/src/CommonPluginAPI.cpp
    ${M64_GLIDEN64_DIR}/src/Config.cpp
    ${M64_GLIDEN64_DIR}/src/convert.cpp
    ${M64_GLIDEN64_DIR}/src/DebugDump.cpp
    ${M64_GLIDEN64_DIR}/src/Debugger.cpp
    ${M64_GLIDEN64_DIR}/src/DepthBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/DisplayWindow.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/mupen64plus/mupen64plus_DisplayWindow.cpp
    ${M64_GLIDEN64_DIR}/src/DisplayLoadProgress.cpp
    ${M64_GLIDEN64_DIR}/src/FrameBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/FrameBufferInfo.cpp
    ${M64_GLIDEN64_DIR}/src/GBI.cpp
    ${M64_GLIDEN64_DIR}/src/gDP.cpp
    ${M64_GLIDEN64_DIR}/src/GLideN64.cpp
    ${M64_GLIDEN64_DIR}/src/gSP.cpp
    ${M64_GLIDEN64_DIR}/src/N64.cpp
    ${M64_GLIDEN64_DIR}/src/TextDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/PaletteTexture.cpp
    ${M64_GLIDEN64_DIR}/src/Performance.cpp
    ${M64_GLIDEN64_DIR}/src/PostProcessor.cpp
    ${M64_GLIDEN64_DIR}/src/RDP.cpp
    ${M64_GLIDEN64_DIR}/src/RSP.cpp
    ${M64_GLIDEN64_DIR}/src/SoftwareRender.cpp
    ${M64_GLIDEN64_DIR}/src/TexrectDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/TextureFilterHandler.cpp
    ${M64_GLIDEN64_DIR}/src/Textures.cpp
    ${M64_GLIDEN64_DIR}/src/VI.cpp
    ${M64_GLIDEN64_DIR}/src/ZlutTexture.cpp
    ${M64_GLIDEN64_DIR}/src/common/CommonAPIImpl_common.cpp
    ${M64_GLIDEN64_DIR}/src/DepthBufferRender/ClipPolygon.cpp
    ${M64_GLIDEN64_DIR}/src/DepthBufferRender/DepthBufferRender.cpp
    ${M64_GLIDEN64_DIR}/src/BufferCopy/BlueNoiseTexture.cpp
    ${M64_GLIDEN64_DIR}/src/BufferCopy/ColorBufferToRDRAM.cpp
    ${M64_GLIDEN64_DIR}/src/BufferCopy/DepthBufferToRDRAM.cpp
    ${M64_GLIDEN64_DIR}/src/BufferCopy/RDRAMtoColorBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/GraphicsDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/Context.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/ColorBufferReader.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/CombinerProgram.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/ObjectHandle.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLFunctions.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_Wrapper.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_WrappedFunctions.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_Command.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/opengl_ObjectPool.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/RingBufferPool.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_Attributes.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_BufferedDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_BufferManipulationObjectFactory.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_CachedFunctions.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_ColorBufferReaderWithBufferStorage.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_ColorBufferReaderWithPixelBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_ColorBufferReaderWithReadPixels.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_ColorBufferReaderWithEGLImage.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_ContextImpl.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_GLInfo.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_Parameters.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_TextureManipulationObjectFactory.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_UnbufferedDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/opengl_Utils.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerInputs.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramBuilder.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramImpl.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramUniformFactory.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramUniformFactoryAccurate.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramUniformFactoryFast.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramUniformFactoryCommon.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramBuilderCommon.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramBuilderAccurate.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_CombinerProgramBuilderFast.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_FXAA.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_ShaderStorage.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_SpecialShadersFactory.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/glsl_Utils.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GraphicBuffer/PrivateApi/GraphicBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/mupenplus/MemoryStatus_mupenplus.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3D.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DAM.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DBETA.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DDKR.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX2.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX3.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX095.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX2ACCLAIM.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DEX2CBFD.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DZEX2.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DFLX2.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DGOLDEN.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DPD.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DSETA.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F5Indi_Naboo.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F5Rogue.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/F3DTEXA.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/L3D.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/L3DEX2.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/L3DEX.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/S2DEX2.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/S2DEX.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/T3DUX.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/Turbo3D.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/ZSort.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/ZSortBOSS.cpp
    ${M64_GLIDEN64_DIR}/src/MupenPlusPluginAPI.cpp
    ${M64_GLIDEN64_DIR}/src/mupenplus/MupenPlusAPIImpl.cpp
    ${MUPEN64_DIR}/custom/GLideN64/mupenplus/Config_mupenplus.cpp
    ${MUPEN64_DIR}/custom/GLideN64/mupenplus/CommonAPIImpl_mupenplus.cpp
    ${M64_GLIDEN64_DIR}/src/Log.cpp
    # GLideNHQ texture filters (lines 374-392)
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TextureFilters.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TextureFilters_2xsai.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TextureFilters_hq2x.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TextureFilters_hq4x.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TextureFilters_xbrz.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxCache.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxDbg.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxFilter.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxFilterExport.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxHiResCache.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxHiResNoCache.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxHiResLoader.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxImage.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxQuantize.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxReSample.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxTexCache.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/TxUtil.cpp
    ${M64_GLIDEN64_DIR}/src/RSP_LoadMatrix.cpp
    # Angrylion threaded renderer (HAVE_THR_AL, line 398)
    ${M64_ANGRYLION_DIR}/parallel_al.cpp
    # paraLLEl RDP (lines 429): parallel.cpp, rdp.cpp, parallel-rdp/, vulkan/, util/
    ${M64_PARALLEL_DIR}/parallel.cpp
    ${M64_PARALLEL_DIR}/rdp.cpp
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp/rdp_renderer.cpp
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp/command_ring.cpp
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp/rdp_dump_write.cpp
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp/rdp_device.cpp
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp/video_interface.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/buffer.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/buffer_pool.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/command_buffer.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/command_pool.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/context.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/cookie.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/descriptor_set.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/device.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/event_manager.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/fence.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/fence_manager.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/image.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/memory_allocator.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/pipeline_event.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/query_pool.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/render_pass.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/sampler.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/semaphore.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/semaphore_manager.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/shader.cpp
    ${M64_PARALLEL_RDP_DIR}/vulkan/texture_format.cpp
    ${M64_PARALLEL_RDP_DIR}/util/arena_allocator.cpp
    ${M64_PARALLEL_RDP_DIR}/util/logging.cpp
    ${M64_PARALLEL_RDP_DIR}/util/thread_id.cpp
    ${M64_PARALLEL_RDP_DIR}/util/aligned_alloc.cpp
    ${M64_PARALLEL_RDP_DIR}/util/timer.cpp
    ${M64_PARALLEL_RDP_DIR}/util/timeline_trace_file.cpp
    ${M64_PARALLEL_RDP_DIR}/util/thread_name.cpp
    # paraLLEl RSP (lines 436-447)
    ${M64_RSP_PARALLEL_DIR}/parallel.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp_disasm.cpp
    ${M64_RSP_PARALLEL_DIR}/jit_allocator.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp/cp2.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp/ls.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp/cp0.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp/vfunctions.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp/reciprocal.cpp
    ${M64_RSP_PARALLEL_DIR}/arch/simd/rsp/rsp_core.cpp
    ${M64_RSP_PARALLEL_DIR}/rsp_jit.cpp
    # CRC (line 473; android not in rpi3/rpi4/libnx list => CRC_OPT.cpp)
    ${M64_GLIDEN64_DIR}/src/CRC_OPT.cpp
    # GLES3 Android-specific GraphicBuffer (lines 501-503)
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GraphicBuffer/GraphicBufferWrapper.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GraphicBuffer/PublicApi/android_hardware_buffer_compat.cpp
)

set(M64_SOURCES_ASM)
set(M64_DYNAREC_DEFINES)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # ARM dynarec (NEW_DYNAREC=3) + NEON renderer + NEON asm
    list(APPEND M64_SOURCES_C
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/new_dynarec.c
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/arm/arm_cpu_features.c
    )
    list(APPEND M64_SOURCES_ASM
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/arm/linkage_arm.S
        ${M64_COMM_DIR}/audio/conversion/float_to_s16_neon.S
        ${M64_COMM_DIR}/audio/conversion/s16_to_float_neon.S
        ${M64_COMM_DIR}/audio/resampler/drivers/sinc_resampler_neon.S
    )
    list(APPEND M64_SOURCES_CXX
        ${M64_GLIDEN64_DIR}/src/Neon/3DMathNeon.cpp
        ${M64_GLIDEN64_DIR}/src/Neon/gSPNeon.cpp
    )
    set(M64_DYNAREC_DEFINES NEW_DYNAREC=3 HAVE_NEON)
elseif(ANDROID_ABI STREQUAL "arm64-v8a")
    # ARM64 dynarec (NEW_DYNAREC=4) + generic 3DMath
    list(APPEND M64_SOURCES_C
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/new_dynarec.c
    )
    list(APPEND M64_SOURCES_ASM
        ${M64_CORE_DIR}/src/device/r4300/new_dynarec/arm64/linkage_arm64.S
    )
    list(APPEND M64_SOURCES_CXX
        ${M64_GLIDEN64_DIR}/src/3DMath.cpp
    )
    set(M64_DYNAREC_DEFINES NEW_DYNAREC=4)
endif()

add_library(mupen64plus_next_core SHARED
    ${M64_SOURCES_C}
    ${M64_SOURCES_CXX}
    ${M64_SOURCES_ASM}
)

set_target_properties(mupen64plus_next_core PROPERTIES
    C_STANDARD 11
    C_STANDARD_REQUIRED ON
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches all prior core targets); SYSTEM include marking keeps any vendored
# inline-header warnings out of our own -Wall -Wextra targets.
target_include_directories(mupen64plus_next_core SYSTEM PRIVATE
    ${MUPEN64_DIR}/custom
    ${MUPEN64_DIR}/custom/mupen64plus-core
    ${MUPEN64_DIR}/custom/android/include
    ${MUPEN64_DIR}/custom/GLideN64
    ${M64_GLIDEN64_DIR}/src
    ${M64_GLIDEN64_DIR}/src/osal
    ${M64_GLIDEN64_DIR}/src/inc
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/inc
    ${M64_CORE_DIR}/src
    ${M64_CORE_DIR}/src/api
    ${M64_CORE_DIR}/include
    ${M64_CORE_DIR}/src/device/r4300/new_dynarec
    ${M64_CORE_DIR}/src/asm_defines
    ${M64_AUDIO_LIBRETRO_DIR}
    ${M64_COMM_DIR}/include
    ${M64_LIBRETRO_DIR}
    ${M64_CORE_DIR}/subprojects/md5
    ${M64_CORE_DIR}/subprojects/minizip
    ${M64_LIBPNG_DIR}
    ${M64_ZLIB_DIR}
    ${M64_XXHASH_DIR}
    ${M64_PARALLEL_RDP_DIR}/parallel-rdp
    ${M64_PARALLEL_RDP_DIR}/volk
    ${M64_PARALLEL_RDP_DIR}/vulkan
    ${M64_PARALLEL_RDP_DIR}/vulkan-headers/include
    ${M64_PARALLEL_RDP_DIR}/util
    ${M64_RSP_PARALLEL_DIR}/arch/simd/rsp
    ${M64_RSP_PARALLEL_DIR}/lightning/include
    ${ROMM_APP_CPP_DIR}/../../../../third_party/libretro
)

target_compile_definitions(mupen64plus_next_core PRIVATE
    # Android.mk line 65 COREFLAGS
    __LIBRETRO__
    OS_ANDROID
    USE_FILE32API
    M64P_PLUGIN_API
    M64P_CORE_PROTOTYPES
    _ENDUSER_RELEASE
    SINC_LOWER_QUALITY
    MUPENPLUSAPI
    TXFILTER_LIB
    __VEC4_OPT
    ANDROID
    EGL_EGLEXT_PROTOTYPES
    HAVE_POSIX_MEMALIGN=1
    # LLE / parallel RSP/RDP / THR_AL
    HAVE_LLE
    HAVE_MMAP=1
    HAVE_PARALLEL_RDP
    HAVE_PARALLEL_RSP
    PARALLEL_INTEGRATION
    HAVE_THR_AL
    # paraLLEl RDP CXXFLAGS (config.mk)
    GRANITE_VULKAN_MT
    # GLES3=1 override (Makefile.common lines 493-500)
    EGL
    HAVE_OPENGLES
    HAVE_OPENGLES3
    GLES3
    # dynarec (ABI-specific)
    DYNAREC
    ${M64_DYNAREC_DEFINES}
    GIT_VERSION=" 98c1b0d"
)

# Upstream Android.mk adds -marm + NEON for armeabi-v7a; keep ARM mode for the
# dynarec/NEON asm. C++ stays default (no -marm) to match upstream's gnu++11.
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    target_compile_options(mupen64plus_next_core PRIVATE
        -marm
        -mfpu=neon
    )
endif()

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches all prior core targets). Suppress the handful of upstream warning
# categories emitted by the bundled zlib/libpng/libretro-common and the
# dynarec/RSP plugins so this target builds clean; mirrors the targeted -Wno-*
# convention used by the other core targets (e.g. fceumm_core's
# -Wno-write-strings). Clang ignores -Wno- groups that don't apply to a given
# translation unit, so a single shared option set is safe for both C and CXX.
target_compile_options(mupen64plus_next_core PRIVATE
    # Upstream Makefile release CPUOPTS (lines 650-652). The project-wide
    # RelWithDebInfo default is only -O2, so keep this target aligned with the
    # performance profile used by the pinned libretro core.
    -O3
    -fsigned-char
    -ffast-math
    -fno-strict-aliasing
    -fomit-frame-pointer
    -fvisibility=hidden
    -Wno-strict-prototypes
    -Wno-deprecated-non-prototype
    -Wno-switch
    -Wno-write-strings
    -Wno-invalid-offsetof
    -Wno-discarded-qualifiers
    -Wno-incompatible-pointer-types-discards-qualifiers
    -Wno-parentheses
    -Wno-xor-used-as-pow
    -Wno-shift-negative-value
    -Wno-absolute-value
)

target_compile_options(mupen64plus_next_core PRIVATE
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>
)

# Linked with upstream's own version script so only the standard retro_*
# Libretro ABI is exported. --gc-sections + 16 KiB max page size mirror the
# project's other cores for the Android 16 KiB page-size requirement.
target_link_options(mupen64plus_next_core PRIVATE
    "-Wl,--version-script=${M64_LIBRETRO_DIR}/link.T"
    "-Wl,--gc-sections"
    "-Wl,-z,max-page-size=16384"
    "-Wl,--no-undefined"
)

target_link_libraries(mupen64plus_next_core
    log
    EGL
    GLESv3
    m
    z
    dl
)

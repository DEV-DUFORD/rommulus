# ---------------------------------------------------------------------------
# Mupen64Plus-Next: Linux x86_64 GLES3 build of the vendored libretro core.
# GLideN64 is the default GPU renderer; Angrylion remains compiled as a
# fallback. The CPU core continues to use the x86_64 new dynarec.
# ---------------------------------------------------------------------------
set(M64_CORE_DIR ${MUPEN64_DIR}/mupen64plus-core)
set(M64_RSP_HLE_DIR ${MUPEN64_DIR}/mupen64plus-rsp-hle)
set(M64_CXD4_DIR ${MUPEN64_DIR}/mupen64plus-rsp-cxd4)
set(M64_GLIDEN64_DIR ${MUPEN64_DIR}/GLideN64)
set(M64_ANGRYLION_DIR ${MUPEN64_DIR}/mupen64plus-video-angrylion)
set(M64_COMM_DIR ${MUPEN64_DIR}/libretro-common)
set(M64_LIBRETRO_DIR ${MUPEN64_DIR}/libretro)
set(M64_AUDIO_LIBRETRO_DIR ${MUPEN64_DIR}/custom/mupen64plus-core/plugin/audio_libretro)
set(M64_MINIZIP_DIR ${M64_CORE_DIR}/subprojects/minizip)
set(M64_LIBPNG_DIR ${MUPEN64_DIR}/custom/dependencies/libpng)
set(M64_ZLIB_DIR ${MUPEN64_DIR}/custom/dependencies/libzlib)
set(M64_XXHASH_DIR ${MUPEN64_DIR}/xxHash)

set(M64_LINUX_INCLUDE_DIRS
    ${MUPEN64_DIR}/custom
    ${MUPEN64_DIR}/custom/mupen64plus-core
    ${M64_GLIDEN64_DIR}/src
    ${M64_GLIDEN64_DIR}/src/osal
    ${M64_GLIDEN64_DIR}/src/inc
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/inc
    ${MUPEN64_DIR}/custom/android/include
    ${MUPEN64_DIR}/custom/GLideN64
    ${M64_CORE_DIR}/src
    ${M64_CORE_DIR}/src/api
    ${M64_CORE_DIR}/include
    ${M64_CORE_DIR}/src/device/r4300/new_dynarec
    ${M64_CORE_DIR}/src/asm_defines
    ${M64_AUDIO_LIBRETRO_DIR}
    ${M64_COMM_DIR}/include
    ${M64_LIBRETRO_DIR}
    ${M64_CORE_DIR}/subprojects/md5
    ${M64_MINIZIP_DIR}
    ${M64_LIBPNG_DIR}
    ${M64_ZLIB_DIR}
    ${M64_XXHASH_DIR}
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
    EGL
    HAVE_OPENGLES
    HAVE_OPENGLES3
    GLES3
    GIT_VERSION=" 98c1b0d"
)

set(M64_SOURCES_C
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
    ${M64_CORE_DIR}/src/device/r4300/new_dynarec/new_dynarec.c
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
    ${M64_MINIZIP_DIR}/zip.c
    ${M64_MINIZIP_DIR}/unzip.c
    ${M64_MINIZIP_DIR}/ioapi.c
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
    ${M64_CORE_DIR}/subprojects/md5/md5.c
    ${M64_GLIDEN64_DIR}/src/osal/osal_files_unix.c
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
    ${M64_COMM_DIR}/glsym/glsym_es3.c
    ${M64_COMM_DIR}/glsym/rglgen.c
    ${M64_ANGRYLION_DIR}/interface.c
    ${M64_ANGRYLION_DIR}/n64video.c
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
    ${M64_CXD4_DIR}/rsp.c
    ${M64_COMM_DIR}/libco/libco.c
)

set(M64_GLIDEN64_SOURCES_CXX
    ${M64_GLIDEN64_DIR}/src/Combiner.cpp
    ${M64_GLIDEN64_DIR}/src/CombinerKey.cpp
    ${M64_GLIDEN64_DIR}/src/CommonPluginAPI.cpp
    ${M64_GLIDEN64_DIR}/src/Config.cpp
    ${M64_GLIDEN64_DIR}/src/convert.cpp
    ${M64_GLIDEN64_DIR}/src/DebugDump.cpp
    ${M64_GLIDEN64_DIR}/src/Debugger.cpp
    ${M64_GLIDEN64_DIR}/src/DepthBuffer.cpp
    ${M64_GLIDEN64_DIR}/src/DisplayWindow.cpp
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
    ${M64_GLIDEN64_DIR}/src/GraphicsDrawer.cpp
    ${M64_GLIDEN64_DIR}/src/MupenPlusPluginAPI.cpp
    ${M64_GLIDEN64_DIR}/src/Log.cpp
    ${M64_GLIDEN64_DIR}/src/RSP_LoadMatrix.cpp
    ${M64_GLIDEN64_DIR}/src/CRC_OPT.cpp
    ${M64_GLIDEN64_DIR}/src/3DMath.cpp
    ${M64_GLIDEN64_DIR}/src/common/CommonAPIImpl_common.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/mupen64plus/mupen64plus_DisplayWindow.cpp
    ${M64_GLIDEN64_DIR}/src/mupenplus/MemoryStatus_mupenplus.cpp
    ${M64_GLIDEN64_DIR}/src/mupenplus/MupenPlusAPIImpl.cpp
    ${MUPEN64_DIR}/custom/GLideN64/mupenplus/Config_mupenplus.cpp
    ${MUPEN64_DIR}/custom/GLideN64/mupenplus/CommonAPIImpl_mupenplus.cpp
)

# These pinned vendored directories contain only the upstream source closure
# listed by Makefile.common for GLideN64.
file(GLOB M64_GLIDEN64_BUFFER_SOURCES CONFIGURE_DEPENDS
    ${M64_GLIDEN64_DIR}/src/BufferCopy/*.cpp
    ${M64_GLIDEN64_DIR}/src/DepthBufferRender/*.cpp
    ${M64_GLIDEN64_DIR}/src/GLideNHQ/*.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/*.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/*.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/GLSL/*.cpp
    ${M64_GLIDEN64_DIR}/src/Graphics/OpenGLContext/ThreadedOpenGl/*.cpp
    ${M64_GLIDEN64_DIR}/src/uCodes/*.cpp
)
list(APPEND M64_GLIDEN64_SOURCES_CXX ${M64_GLIDEN64_BUFFER_SOURCES})

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

target_link_libraries(mupen64plus_next_core PRIVATE
    EGL
    GLESv2
    m
    dl
    pthread
)

# ---------------------------------------------------------------------------
# mGBA (BSD-2-Clause): vendored under third_party/cores/mgba/, pinned to the
# upstream commit 32de792
# (https://github.com/libretro/mgba/tree/32de792). See
# third_party/cores/mgba/VENDORING.md for exactly what was vendored, why, and
# what was deliberately excluded (threading, SDL/Qt GUI, all cores besides
# GBA/GB, and non-libretro platform code).
#
# Source list and preprocessor flags mirror upstream's own
# libretro/jni/Android.mk (COREFLAGS) exactly.
# ---------------------------------------------------------------------------

add_library(mgba_core SHARED
    ${MGBA_DIR}/src/arm/arm.c
    ${MGBA_DIR}/src/arm/decoder-arm.c
    ${MGBA_DIR}/src/arm/decoder-thumb.c
    ${MGBA_DIR}/src/arm/decoder.c
    ${MGBA_DIR}/src/arm/isa-arm.c
    ${MGBA_DIR}/src/arm/isa-thumb.c
    ${MGBA_DIR}/src/core/bitmap-cache.c
    ${MGBA_DIR}/src/core/cache-set.c
    ${MGBA_DIR}/src/core/cheats.c
    ${MGBA_DIR}/src/core/config.c
    ${MGBA_DIR}/src/core/core-serialize.c
    ${MGBA_DIR}/src/core/core.c
    ${MGBA_DIR}/src/core/interface.c
    ${MGBA_DIR}/src/core/lockstep.c
    ${MGBA_DIR}/src/core/log.c
    ${MGBA_DIR}/src/core/map-cache.c
    ${MGBA_DIR}/src/core/sync.c
    ${MGBA_DIR}/src/core/thread.c
    ${MGBA_DIR}/src/core/tile-cache.c
    ${MGBA_DIR}/src/core/timing.c
    ${MGBA_DIR}/src/gb/audio.c
    ${MGBA_DIR}/src/gb/cheats.c
    ${MGBA_DIR}/src/gb/core.c
    ${MGBA_DIR}/src/gb/gb.c
    ${MGBA_DIR}/src/gb/io.c
    ${MGBA_DIR}/src/gb/mbc.c
    ${MGBA_DIR}/src/gb/mbc/huc-3.c
    ${MGBA_DIR}/src/gb/mbc/licensed.c
    ${MGBA_DIR}/src/gb/mbc/mbc.c
    ${MGBA_DIR}/src/gb/mbc/pocket-cam.c
    ${MGBA_DIR}/src/gb/mbc/tama5.c
    ${MGBA_DIR}/src/gb/mbc/unlicensed.c
    ${MGBA_DIR}/src/gb/memory.c
    ${MGBA_DIR}/src/gb/overrides.c
    ${MGBA_DIR}/src/gb/renderers/cache-set.c
    ${MGBA_DIR}/src/gb/renderers/software.c
    ${MGBA_DIR}/src/gb/serialize.c
    ${MGBA_DIR}/src/gb/sio.c
    ${MGBA_DIR}/src/gb/timer.c
    ${MGBA_DIR}/src/gb/video.c
    ${MGBA_DIR}/src/gba/audio.c
    ${MGBA_DIR}/src/gba/bios.c
    ${MGBA_DIR}/src/gba/cart/ereader.c
    ${MGBA_DIR}/src/gba/cart/gpio.c
    ${MGBA_DIR}/src/gba/cart/matrix.c
    ${MGBA_DIR}/src/gba/cart/unlicensed.c
    ${MGBA_DIR}/src/gba/cart/vfame.c
    ${MGBA_DIR}/src/gba/cheats.c
    ${MGBA_DIR}/src/gba/cheats/codebreaker.c
    ${MGBA_DIR}/src/gba/cheats/gameshark.c
    ${MGBA_DIR}/src/gba/cheats/parv3.c
    ${MGBA_DIR}/src/gba/core.c
    ${MGBA_DIR}/src/gba/dma.c
    ${MGBA_DIR}/src/gba/gba.c
    ${MGBA_DIR}/src/gba/hle-bios.c
    ${MGBA_DIR}/src/gba/input.c
    ${MGBA_DIR}/src/gba/io.c
    ${MGBA_DIR}/src/gba/memory.c
    ${MGBA_DIR}/src/gba/overrides.c
    ${MGBA_DIR}/src/gba/renderers/cache-set.c
    ${MGBA_DIR}/src/gba/renderers/common.c
    ${MGBA_DIR}/src/gba/renderers/software-bg.c
    ${MGBA_DIR}/src/gba/renderers/software-mode0.c
    ${MGBA_DIR}/src/gba/renderers/software-obj.c
    ${MGBA_DIR}/src/gba/renderers/video-software.c
    ${MGBA_DIR}/src/gba/savedata.c
    ${MGBA_DIR}/src/gba/serialize.c
    ${MGBA_DIR}/src/gba/sio.c
    ${MGBA_DIR}/src/gba/sio/gbp.c
    ${MGBA_DIR}/src/gba/timer.c
    ${MGBA_DIR}/src/gba/video.c
    ${MGBA_DIR}/src/platform/libretro/libretro.c
    ${MGBA_DIR}/src/platform/libretro/memory.c
    ${MGBA_DIR}/src/sm83/isa-sm83.c
    ${MGBA_DIR}/src/sm83/sm83.c
    ${MGBA_DIR}/src/third-party/inih/ini.c
    ${MGBA_DIR}/src/util/audio-buffer.c
    ${MGBA_DIR}/src/util/audio-resampler.c
    ${MGBA_DIR}/src/util/circle-buffer.c
    ${MGBA_DIR}/src/util/configuration.c
    ${MGBA_DIR}/src/util/crc32.c
    ${MGBA_DIR}/src/util/formatting.c
    ${MGBA_DIR}/src/util/gbk-table.c
    ${MGBA_DIR}/src/util/geometry.c
    ${MGBA_DIR}/src/util/hash.c
    ${MGBA_DIR}/src/util/image.c
    ${MGBA_DIR}/src/util/interpolator.c
    ${MGBA_DIR}/src/util/md5.c
    ${MGBA_DIR}/src/util/patch-ips.c
    ${MGBA_DIR}/src/util/patch-ups.c
    ${MGBA_DIR}/src/util/patch.c
    ${MGBA_DIR}/src/util/sha1.c
    ${MGBA_DIR}/src/util/string.c
    ${MGBA_DIR}/src/util/table.c
    ${MGBA_DIR}/src/util/vector.c
    ${MGBA_DIR}/src/util/vfs.c
    ${MGBA_DIR}/src/util/vfs/vfs-fd.c
    ${MGBA_DIR}/src/util/vfs/vfs-mem.c
)

# Upstream's own libretro/jni/Android.mk builds this core with -std=c99
# specifically; match it exactly rather than relying on this project's own
# (newer) default C_STANDARD.
set_target_properties(mgba_core PROPERTIES
    C_STANDARD 99
    C_STANDARD_REQUIRED ON
)

target_include_directories(mgba_core SYSTEM PRIVATE
    ${MGBA_DIR}/src
    ${MGBA_DIR}/src/arm
    ${MGBA_DIR}/include
    ${MGBA_DIR}/src/platform/libretro
)

target_compile_definitions(mgba_core PRIVATE
    # glibc exposes locale_t directly from locale.h; unlike Android, modern
    # Linux distributions do not provide the legacy xlocale.h wrapper.
    _GNU_SOURCE
    HAVE_LOCALE
    HAVE_USELOCALE
    HAVE_STRTOF_L
    DISABLE_THREADING
    MINIMAL_CORE=2
    __LIBRETRO__
    M_CORE_GBA
    M_CORE_GB
    ENABLE_VFS
    ENABLE_DIRECTORIES
    HAVE_STDINT_H
    HAVE_INTTYPES_H
    INLINE=inline
    COLOR_16_BIT
    RESAMPLE_LIBRARY=2
    M_PI=3.14159265358979323846
    MGBA_STANDALONE
    PATH_MAX=4096
    NDEBUG
    HAVE_LOCALTIME_R
    COLOR_5_6_5
    ENABLE_VFS_FD
    GIT_VERSION="32de792"
)

# Vendored third-party source: not held to this project's own -Wall -Wextra
# (matches sameboy_core/genesis_plus_gx_core), linked with upstream's own
# version script so only the standard retro_* Libretro ABI is exported.
target_link_options(mgba_core PRIVATE
    "-Wl,--version-script=${MGBA_DIR}/link.T"
    "-Wl,--no-undefined"
)

target_link_libraries(mgba_core
    m
)

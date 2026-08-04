# mupen64plus_next vendoring notes

Upstream: https://github.com/libretro/mupen64plus-libretro-nx
Pinned top-level commit: `98c1b0d877542b01314b3b04272282ba223b65b3` (libretro/mupen64plus-libretro-nx, branch `develop`)

Built exclusively for `armeabi-v7a` and `arm64-v8a`. Both ABIs enable the
ARM/ARM64 dynarec (NEW_DYNAREC=3/4), the paraLLEl RSP/RDP plugins, LLE (low-level
emulation), the Angrylion renderer, and NEON on armeabi-v7a.

## Component pins (git-subrepo)

Top-level repo:

| Component | Subrepo remote | Branch | Pin (SHA) |
|---|---|---|---|
| Top-level | `https://github.com/libretro/mupen64plus-libretro-nx` | `develop` | `98c1b0d877542b01314b3b04272282ba223b65b3` |
| mupen64plus-core | `https://github.com/libretro/mupen64plus-core.git` | `master` | `ef94b0dc740e993a58783052bdcda64d97737d67` |
| GLideN64 | `git@github.com:libretro/GLideN64.git` | `develop` | `ad294151008712cb102245649efa828fbe14e41b` |
| mupen64plus-rsp-hle | `https://github.com/libretro/mupen64plus-rsp-hle.git` | `master` | `2416bd4c698953310995d8a24b8081f9cd974354` |
| mupen64plus-rsp-paraLLEl | `git@github.com:libretro/parallel-rsp.git` | `master` | `9ab776a2ce28899769c4f911403234cac62fc799` |
| libretro-common | `https://github.com/libretro-fork/libretro-common-mupen64plus-nx.git` | `master` | `f9f6ac61c3c7a4c1766c19ee9af70302d1914470` |
| mupen64plus-video-paraLLEl (contains parallel-rdp subrepo) | `https://github.com/Themaister/parallel-rdp-standalone.git` (parallel-rdp sub) | `master` | `c6f79549e709b5f0bf5cb011028cb6d57627b051` |
| lightning (sub-sub of paraLLEl rsp) | `https://git.savannah.gnu.org/git/lightning.git` | `master` | `75e5274ba59feab99a2afcf329d890911ed01eaf` |
| mman-win32 (sub-sub of paraLLEl rsp) | `https://github.com/witwall/mman-win32.git` | `master` | `2d1c576e62b99e85d99407e1a88794c6e44c3310` |

Notes:
- `mupen64plus-rsp-cxd4`, `mupen64plus-video-angrylion` do **not** have a
  `.gitrepo` file — they are vendored as a 1:1 copy of the upstream source.
  No per-component pin is recorded; treat them as rolling with the top-level
  commit unless a LICENSE or source-header indicates otherwise.
- `lightning` and `mman-win32` are sub-subrepos folded into
  `mupen64plus-rsp-paraLLEl/` at vendoring time (not a separate top-level dir).
- `parallel-rdp` is the vulkan-headers + volk submodule nested inside
  `mupen64plus-video-paraLLEl/parallel-rdp/`.

## Compiled closure (what is inside this directory)

- `libretro/` — the libretro frontend (`libretro.c`, `main.c`,
  `libretro_core_options.h`, `libretro_memory.h`, `libretro_perf.h`,
  `libretro_private.h`, `link.T`, `jni/Android.mk`).
- `mupen64plus-core/` — full core source tree: `src/`, `include/`,
  `subprojects/md5/`, `subprojects/minizip/` (zip.c, unzip.c, ioapi.c),
  `plugins/`, plus the full `COPYING` file. No `tests/` or desktop-only
  non-Android test helpers.
- `GLideN64/` — full graphics plugin: `src/` (all .cpp sources enumerated in
  `Makefile.common`), `include/`, `LICENSE` (GPL-2.0).
- `mupen64plus-rsp-hle/` — full RSP HLE: `src/alist.c`, `src/audio.c`,
  `src/plugin.c`, `src/cicx105.c`, `src/hle.c`, `src/jpeg.c`,
  `src/memory.c`, `src/mp3.c`, `src/musyx.c`, `src/re2.c`, `src/alist_*.c`,
  plus `COPYING`.
- `mupen64plus-rsp-cxd4/` — low-level RSP plugin: `rsp.c`, `rsp.h`,
  `module.c/.h`, plus `COPYING` (CC0 1.0).
- `mupen64plus-rsp-paraLLEl/` — paraLLEl RSP: `parallel.cpp`,
  `rsp_*.cpp`, `jit_allocator.cpp`, `rsp_jit.cpp`, plus the bundled
  `lightning/` and `win32/mman/` sub-subrepos. Licenses:
  `LICENSE.MIT`, `LICENSE` (dual MIT + LGPL-3.0), `LICENSE.LESSER`.
- `mupen64plus-video-paraLLEl/` — paraLLEl RDP: `parallel.cpp`, `rdp.cpp`,
  and the nested `parallel-rdp/` (which itself contains the vulkan-headers
  and volk vendored as a subrepo). License: `parallel-rdp/LICENSE` (MIT).
- `mupen64plus-video-angrylion/` — Angrylion threaded renderer:
  `parallel_al.cpp`, `interface.c`, `n64video.c`. No explicit LICENSE file
  is present in the vendored copy; upstream convention is GPL-2.0.
- `libretro-common/` — the libretro-fork variant of libretro-common: all
  sources referenced by `Makefile.common` (VFS, path, stream, string, time,
  compat, audio conversion, resampler, glsm, libco, encoding, lists,
  features, config_file). Public headers under `include/`.
- `xxHash/` — xxhash.h, xxh3.h (header-only distribution).
- `custom/` — the Android-specific overrides:
  - `custom/android/` (includes),
  - `custom/mupen64plus-core/api/vidext_libretro.c`,
  - `custom/mupen64plus-core/plugin/audio_libretro/audio_backend_libretro.c`,
  - `custom/mupen64plus-core/plugin/emulate_game_controller_via_libretro.c`,
  - `custom/GLideN64/mupenplus/{Config_mupenplus.cpp, CommonAPIImpl_mupenplus.cpp}`,
  - `custom/dependencies/libpng/` (bundled libpng with libpng-2.0 license,
    all png*.c sources enumerated in `Makefile.common`),
  - `custom/dependencies/libzlib/` (bundled zlib, all sources enumerated
    in `Makefile.common`).
- Top-level `Makefile.common` (referenced by `Android.mk` line 63:
  `include $(ROOT_DIR)/Makefile.common`).

## Excluded

- `.git/` (top-level and any nested), `.gitrepo` files (subrepo metadata),
  `.github/` (CI), `.gitlab-ci.yml` (CI).
- `README.md`, `LICENSE` (top-level — per-component license files are kept).
- `generate-ini-headers.sh` (build-tool script, not compiled).
- `switch/` (desktop platform).
- `third_party/` (desktop-only deps not compiled by Android.mk).
- `custom/ios/` (iOS-only compat.c), `custom/mman-win32/` (Windows),
  `custom/tools/` (if any).
- Test helpers, docs, desktop build scripts.

## Android build flags summary (from `libretro/jni/Android.mk`)

| Flag | armeabi-v7a | arm64-v8a |
|---|---|---|
| `WITH_DYNAREC` | `arm` (NEW_DYNAREC=3) | `aarch64` (NEW_DYNAREC=4) |
| `HAVE_NEON` | 1 | 0 (uses generic 3DMath.cpp) |
| `LLE` | 1 | 1 (defines `HAVE_LLE`) |
| `HAVE_PARALLEL_RSP` | 1 | 1 |
| `HAVE_PARALLEL_RDP` | 1 | 1 |
| `HAVE_THR_AL` | 1 | 1 |
| GLES | 1 (GLESv2) | 1 (GLESv2) |
| GLES3 | 0 (use `GLES3=1` override) | 0 (use `GLES3=1` override) |

Linker script: `LOCAL_LDFLAGS := -Wp,-version-script=$(LIBRETRO_DIR)/link.T`
Exports `retro_*` symbols, hides everything else.

`LOCAL_LDLIBS := -llog -lEGL $(GLLIB) $(CORELDLIBS)`, where `GLLIB :=
-lGLESv2` (or `-lGLESv3` when `GLES3=1`).

`LOCAL_CPPFLAGS := -std=gnu++11` plus all `$(COREFLAGS)` defines enumerated
in `Android.mk` line 65 (`__LIBRETRO__`, `OS_ANDROID`, `USE_FILE32API`,
`M64P_PLUGIN_API`, `M64P_CORE_PROTOTYPES`, `_ENDUSER_RELEASE`,
`SINC_LOWER_QUALITY`, `MUPENPLUSAPI`, `TXFILTER_LIB`, `__VEC4_OPT`,
`ANDROID`, `EGL_EGLEXT_PROTOTYPES`, `HAVE_POSIX_MEMALIGN=1`), plus
`-DHAVE_LLE` (when LLE=1), `-DHAVE_MMAP=1` (when parallel RSP is on).

## License summary (verified from files present in the vendored tree)

- `mupen64plus-core/` — **GPL-2.0** (preserved in `COPYING`).
- `GLideN64/` — **GPL-2.0** (preserved in `LICENSE`).
- `mupen64plus-rsp-hle/` — **GPL-2.0** (preserved in `COPYING`).
- `mupen64plus-rsp-cxd4/` — **CC0 1.0 Universal** (preserved in `COPYING`).
- `mupen64plus-rsp-paraLLEl/` — **dual MIT / LGPL-3.0** (preserved in
  `LICENSE.MIT` + `LICENSE` + `LICENSE.LESSER`). The build uses the MIT
  option; LGPL applies to the library itself.
- `mupen64plus-video-paraLLEl/parallel-rdp/` — **MIT** (preserved in
  `parallel-rdp/LICENSE`).
- `mupen64plus-video-angrylion/` — **GPL-2.0** (no explicit LICENSE file
  in the vendored copy; inferred from upstream convention).
- `libretro-common/` — **MIT** (per upstream header license notices).
- `xxHash/` — **CC0** (header-only distribution, no license file present;
  upstream distributes under CC0).
- `custom/dependencies/libpng/` — **libpng-2.0** (noted in `pnglibconf.h`).
- `custom/dependencies/libzlib/` — **zlib license** (standard zlib source).

No non-commercial, no-sale, patent-encumbered codec, or separately downloaded
binary component was found in the compiled closure. BIOS and game content are
not vendored.

## Firmware / BIOS requirement

> **The mupen64plus_next core requires NO firmware or BIOS.**
> The PI controller's PIF bootrom is implemented in software (`src/device/pif/bootrom_hle.c`)
> and the NUS CIC-6105 authentication is also HLE (`src/device/pif/n64_cic_nus_6105.c`).
> The core emulates the full PIF/BIOS; no external ROM file is required.

## Local build fixes

The project's CMake target
(`mupen64plus_next_core` in `app/src/main/cpp/CMakeLists.txt`) reproduces the
upstream `Android.mk` + `Makefile.common` closure for `armeabi-v7a`/`arm64-v8a`
and makes these CMake-side adaptations:

- **GLES3 override**: built with `GLES3=1` (the target TV exposes OpenGL ES 3.2),
  so the target defines `EGL`, `HAVE_OPENGLES`, `HAVE_OPENGLES3`, `GLES3`, links
  `glsym_es3.c`, adds the Android `GraphicBufferWrapper.cpp` +
  `android_hardware_buffer_compat.cpp`, and links `EGL GLESv3 log m z dl`.
- **C++ standard**: upstream `Android.mk` sets `-std=gnu++11`, but the paraLLEl
  RSP/RDP closure (this pin) uses C++14/17 (`std::make_unique`, etc.), so `CXX`
  is pinned to **17**. C stays `gnu11`. This is the one deliberate deviation
  from upstream flags.
- **Warning convention**: matching every other vendored core target, the
  target is *not* held to the project's `-Wall -Wextra`. Targeted `-Wno-*`
  flags suppress the handful of upstream warning categories emitted by the
  bundled zlib/libpng and the dynarec/RSP/GLideN64 plugins (e.g.
  `-Wno-write-strings`, `-Wno-strict-prototypes`, `-Wno-switch`,
  `-Wno-invalid-offsetof`, `-Wno-xor-used-as-pow`,
  `-Wno-incompatible-pointer-types-discards-qualifiers`). Build is warning-free.
- **Release optimization flags**: the target explicitly applies upstream's
  non-debug `CPUOPTS` (`-O3`, `-ffast-math`, `-fno-strict-aliasing`,
  `-fomit-frame-pointer`, hidden visibility, and signed `char`) instead of
  inheriting this app's less aggressive `RelWithDebInfo` `-O2` default.
- **Linker**: `-Wl,--version-script=libretro/link.T` (verified: only the 49
  `retro_*` ABI symbols are exported), plus `--gc-sections`,
  `-z,max-page-size=16384` (Android 16 KiB page-size requirement), and
  `--no-undefined`.
- `armeabi-v7a` gets `-marm -mfpu=neon` and the NEON renderer
  (`3DMathNeon.cpp`, `gSPNeon.cpp`, 3 NEON `.S` files) per `HAVE_NEON=1`; the
  ARM64 build uses `3DMath.cpp`. Both ABIs enable LLE (`HAVE_LLE`),
  `HAVE_PARALLEL_RSP`, `HAVE_PARALLEL_RDP`, `HAVE_THR_AL` (Angrylion),
  `HAVE_MMAP=1`, `PARALLEL_INTEGRATION`, `GRANITE_VULKAN_MT`, and
  `GIT_VERSION=" 98c1b0d"`.
- **Frontend-directed frame skipping**: `libretro/libretro.c` queries
  `RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE` once per `retro_run()`. When video
  is disabled, the GLideN64 integration completes the emulated DP task/interrupt
  without processing that frame's HLE display list or LLE RDP command list, and
  suppresses the final video callback. N64 CPU emulation, input, and audio still
  advance normally while expensive graphics work is skipped.

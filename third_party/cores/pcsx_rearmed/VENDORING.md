# PCSX-ReARMed vendoring notes

Upstream: https://github.com/libretro/pcsx_rearmed  
Pinned commit: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3` (2026-08-02)

The exact commit is newer than the `r26l` release tag and includes upstream's
Android 16 KiB page-size linker fix and later libretro VFS fixes. The vendored
tree is the union of the compiled closures for Android ARM and Linux x86_64;
the platform-specific CMake fragments keep those builds independent.

## Compiled closure

The original Android closure remains the source/header/license set used by
upstream's `jni/Android.mk` for `armeabi-v7a` and `arm64-v8a`:

- `libpcsxcore/` and `libpcsxcore/new_dynarec/`: the PCSX engine and ARM/ARM64
  ari64 dynarec.
- `plugins/dfsound`, `plugins/gpulib`, `plugins/gpu_neon`, and
  `plugins/cdrcimg`: the SPU, common GPU layer, NEON renderer, and compressed
  CD-image plugin. Desktop audio/video backends and plugin test tools are
  excluded.
- `frontend/`: the exact libretro frontend sources, option declarations,
  threading bridge, ARM color conversion, and upstream linker scripts.
- `deps/libretro-common/`: only the VFS/path/stream/string/time/thread sources
  named by upstream's Android build and their public headers.
- `deps/libchdr/`: libchdr plus its decoder-only LZMA SDK 25.01 and zstd 1.5.7
  closure. This is what makes `.chd` a real compiled format rather than an
  advertised-but-unavailable extension.

The project CMake target mirrors the Android.mk defaults: ari64 with
`NDRC_THREAD`, NEON GPU, asynchronous CD/GPU/SPU, libretro VFS, CHD, ARM mode
and NEON for armeabi-v7a, the ARM64 optimized LZMA decoder, and 16 KiB maximum
ELF page size.

The Linux x86_64 additions are the exact compiled source/header/license closure
selected by upstream's default
`make -f Makefile.libretro platform=unix` build. The source list and compiler
dependency files from the pinned tree were used to avoid copying unrelated
backends, tests, build generators, or documentation:

- `libpcsxcore/lightrec/` and `deps/lightrec/`: the Lightrec PCSX adapter,
  Linux custom memory map, non-threaded recompiler sources, required headers,
  and TLSF allocator. `LIGHTREC_CUSTOM_MAP=1` matches upstream's Linux default.
  The pinned PCSX tree records Lightrec commit
  `a173cf409e11fbbdd4801aacc683a3d7592f499c` from
  https://github.com/pcercuei/lightrec.git and TLSF commit
  `deff9ab509341f264addbd3c8ada533678591905` from
  https://github.com/mattconte/tlsf.
- `deps/lightning/`: only the GNU Lightning sources and generated/public
  headers compiled or textually included by its x86/x86_64 backend. Other
  architecture backends, autotools inputs, examples, and tests are excluded.
  The pinned PCSX tree records GNU Lightning commit
  `a6bb2b5a7cf36e074e12ccaed32990b437deb784` from
  https://git.savannah.gnu.org/git/lightning.git.
- `plugins/gpu_neon/psx_gpu/psx_gpu_simd.c`: despite the historical directory
  name, upstream selects this portable SIMD software renderer on x86_64 and
  builds it with SSSE3. No ARM NEON assembly is used by the Linux target.
- The default physical-CD libretro frontend and its libretro-common list,
  alignment, and CD-ROM VFS sources are included alongside the existing VFS
  closure.
- CHD remains enabled. Linux follows `Makefile.libretro` by compiling its
  decoder-only miniz 3.1.1 source instead of linking system zlib; LZMA and zstd
  remain the same pinned decoder closure used by Android.

`native/cmake/cores/pcsx_rearmed-linux.cmake` reproduces those Unix defaults:
Lightrec (never `DRC_DISABLE`), the SSSE3 software GPU, asynchronous CD/GPU/SPU,
physical CD access, libretro VFS, CHD/miniz, and the upstream GNU linker
version script. It links only the Unix libraries `pthread`, `m`, `dl`, and
`rt`; no Android definitions, linker scripts, page-size options, or `log`
library are applied. The existing Android fragment and behavior are unchanged.

## License and commercial-use audit

- The PCSX engine, libretro frontend, ari64 dynarec, and NEON GPU files used by
  this build grant GPL version 2 or later. `COPYING` preserves the complete
  GPL-2.0 text. GPL permits commercial distribution, but redistribution must
  satisfy the GPL source, notice, and corresponding-source obligations.
- Lightrec is LGPL-2.1-or-later; its `COPYING` is preserved. GNU Lightning is
  LGPL-3.0-or-later; both its GPLv3 `COPYING` and LGPLv3 `COPYING.LESSER` are
  preserved. Because PCSX-ReARMed is GPL-2.0-or-later, the combined Linux
  shared library uses the GPLv3-compatible later-version option.
- Several glue/SPU/GPU files offer GPL-2.0-or-later or LGPL-2.1-or-later at the
  distributor's option. This build uses the GPL option under the core's
  effective GPL-2.0-or-later terms.
- The compiled libretro-common files are MIT licensed in their individual
  headers.
- libchdr is BSD-3-Clause; its `LICENSE.txt` is preserved.
- LZMA SDK 25.01 is public domain; its `LICENSE` is preserved.
- miniz 3.1.1 is MIT licensed; its complete notice is preserved in `miniz.c`
  and `miniz.h`.
- The amalgamated zstd 1.5.7 decoder offers a BSD-style license or GPLv2 in its
  preserved source header. This build uses the BSD option.

No non-commercial, no-sale, patent-encumbered codec, or separately downloaded
binary component was found in the compiled closure. BIOS and game content are
not vendored.

## Frontend integration constraints

PCSX-ReARMed declares `need_fullpath=true`, so the host passes a stable
app-private content path and does not duplicate disc images into memory.
Upstream exposes memory-card slot 1 as 128 KiB `RETRO_MEMORY_SAVE_RAM` when
`pcsx_rearmed_memcard1=libretro`; that card therefore uses the app's existing
per-ROM restore/checkpoint/server-sync path. Slot 2 has only serial, shared, or
disabled file modes and defaults to the cross-game `pcsx-card2.mcd`. The
frontend explicitly sets `pcsx_rearmed_memcard2=none` so no unsynchronized
shared card can leak across ROM/account scopes; vendored behavior is unchanged.

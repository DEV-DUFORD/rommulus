# PCSX-ReARMed vendoring notes

Upstream: https://github.com/libretro/pcsx_rearmed  
Pinned commit: `da2cb8ecd17fd0932ab6d94774c0522beebce6e3` (2026-08-02)

The exact commit is newer than the `r26l` release tag and includes upstream's
Android 16 KiB page-size linker fix and later libretro VFS fixes. It is built
only for `armeabi-v7a` and `arm64-v8a`.

## Compiled closure

This directory contains only the source/header/license closure used by
upstream's `jni/Android.mk` for those two ABIs:

- `libpcsxcore/` and `libpcsxcore/new_dynarec/`: the PCSX engine and ARM/ARM64
  ari64 dynarec. Lightrec implementation sources and non-ARM backends are not
  vendored.
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

## License and commercial-use audit

- The PCSX engine, libretro frontend, ari64 dynarec, and NEON GPU files used by
  this build grant GPL version 2 or later. `COPYING` preserves the complete
  GPL-2.0 text. GPL permits commercial distribution, but redistribution must
  satisfy the GPL source, notice, and corresponding-source obligations.
- Several glue/SPU/GPU files offer GPL-2.0-or-later or LGPL-2.1-or-later at the
  distributor's option. This build uses the GPL option under the core's
  effective GPL-2.0-or-later terms.
- The compiled libretro-common files are MIT licensed in their individual
  headers.
- libchdr is BSD-3-Clause; its `LICENSE.txt` is preserved.
- LZMA SDK 25.01 is public domain; its `LICENSE` is preserved.
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

# Beetle PCE Fast (PC Engine) — vendoring record

Vendored from upstream `libretro/beetle-pce-fast-libretro` at commit
`b211204c7026dff6e86e79b00185512e2421fff8` (2026-07-31), upstream master HEAD at
pin time; upstream publishes no release tags.

## What was vendored, and why

Only the files upstream's own Android libretro build (`jni/Android.mk` +
`Makefile.common`) actually compiles were vendored — a curated subset preserving
upstream relative paths. The vendored set is **224 files (90 `.c` + 132 `.h` +
1 `link.T` + 1 license text)**. Of the 90 `.c` files, **60 are compiled** by this
project's scoped cartridge-only build; the other 30 (`deps/libchdr/`,
`deps/lzma-19.00/`, `deps/zstd/`, `deps/zlib-1.2.11/`, and
`mednafen/cdrom/CDAccess_CHD.c`) are vendored for upstream parity — upstream's
Android build compiles them under `HAVE_CHD=1` — but are deliberately **not**
compiled here (see "Build integration notes").

- **Root** (1 `.c`, 2 `.h`) — the libretro driver (`libretro.c`), core options
  headers (`libretro_core_options.h`, `libretro_core_options_intl.h`), the
  `link.T` version script, and `COPYING` (GPL-2.0 license text).

- **`mednafen/`** (8 `.c`, 15 `.h`) — core Mednafen utilities: audio CD stream
  management (`cdstream.c`), file I/O (`file.c`), general helpers (`general.c`),
  git version stub (`git.h`), endian handling, memory patching (`mempatcher.c`),
  OKI ADPCM decoder (`okiadpcm.c`), settings system, state serialization, and
  video output interface.

- **`mednafen/include/blip/`** (1 `.h`) — Blip_Buffer audio resampler header
  (`Blip_Buffer.h`); compiled via `-I$(MEDNAFEN_DIR)/include`.

- **`mednafen/pce_fast/`** (6 `.c`, 9 `.h`) — PC Engine emulation core: HuC6280
  CPU emulator (`huc6280.c`), input handling, CD-ROM drive emulation
  (`pcecd_drive.c`, `pcecd.c`), PSG audio chip (`psg.c`), VDC graphics chip
  (`vdc.c`).

- **`mednafen/hw_misc/arcade_card/`** (1 `.c`, 1 `.h`) — arcade card hardware
  support.

- **`mednafen/cdrom/`** (12 `.c`, 11 `.h`) — CD-ROM image loading subsystem:
  raw image (`CDAccess_Image.c`), CCD (`CDAccess_CCD.c`), CHD (`CDAccess_CHD.c`),
  audio reader, error correction (Reed-Solomon: `lec.c`, `galois.c`, `l-ec.c`,
  `edc_crc32.c`, `recover-raw.c`), CD-ROM interface layer.

- **`mednafen/sound/`** (1 `.c`) — Blip_Buffer audio resampler implementation.

- **`mednafen/tremor/`** (15 `.c`, 16 `.h`) — Ogg Vorbis software decoder
  (Tremor, integer-only libvorbis replacement): bitstream parsing, codebook
  decoding, floor/mapping/residue stages, MDCT synthesis, windowing.

- **`libretro-common/`** (17 `.c`, 25 `.h`) — libretro utility library subset:
  file/stream I/O (`streams/file_stream.c`), path manipulation (`file/file_path.c`),
  directory listing (`lists/dir_list.c`, `lists/string_list.c`), string utilities
  (`compat/compat_strl.c`, `compat_snprintf.c`, `compat_posix_string.c`,
  `compat_strcasestr.c`, `fopen_utf8.c`), encoding helpers (`encoding_utf.c`,
  `encoding_crc32.c`), memory alignment (`memmap/memalign.c`), timing
  (`time/rtime.c`), VFS abstraction (`vfs/vfs_implementation.c`).

- **`deps/zlib-1.2.11/`** (6 `.c`, 9 `.h`) — zlib decompressor subset: adler32,
  crc32, inflate pipeline (`inflate.c`, `inftrees.c`, `inffast.c`), utilities.

- **`deps/lzma-19.00/`** (9 `.c`, 14 `.h`) — LZMA decompressor subset: allocation,
  branch handling (`Bra86.c`, `BraIA64.c`), CPU architecture detection, delta
  decoding, LZ finder, LZMA encoder/decoder.

- **`deps/libchdr/`** (5 `.c`, 8 `.h`) — libchdr (CD image container) subset:
  bitstream parsing, CD-ROM metadata, CHD format handling, FLAC audio decoding
  wrapper (`libchdr_flac.c` uses embedded `dr_libs/dr_flac.h`), Huffman decoding.

- **`deps/zstd/`** (9 `.c`, 21 `.h`) — zstd decompressor subset: entropy coding
  (`entropy_common.c`, `fse_decompress.c`), error handling, xxhash, Huffman
  decompression, zstd dictionary/decompression pipeline.

## Deliberately excluded

- **Desktop frontends** — no desktop UI code exists in this repo (unlike other
  Mednafen cores); only the libretro driver is present.
- **Build files** — `jni/Android.mk`, `jni/Application.mk`, `Makefile.common`,
  `Makefile.libretro` are NOT vendored; their relevant flags and source lists are
  reproduced in the `beetle_pce_fast_core` CMake target.
- **CI, docs, tests** — `.github/`, `docs/`, `README.md`, `.gitignore`.
- **Translation files** — `.po` translation sources under `mednafen/` (not
  compiled; `libretro_core_options_intl.h` is auto-generated from them and IS
  vendored as a pre-built header).

## License summary

Composite top-level license is **GPL-2.0-or-later** (root `COPYING`, version 2
with "or later" clause). There is **no non-commercial / no-sale / "personal use
only" clause anywhere** in the core. Vendored subcomponents carry their own
separate, permissive/copyleft-compatible licenses that add no further restriction:

- **libretro-common** (`libretro-common/`) — **MIT**.
- **zlib** (`deps/zlib-1.2.11/`) — **zlib license** (BSD-like).
- **libchdr** (`deps/libchdr/`) — **zlib license** (same as zlib).
- **LZMA SDK** (`deps/lzma-19.00/`) — **public domain** (Igor Pavlov).
- **zstd** (`deps/zstd/`) — **BSD-2-Clause**.
- **Tremor** (`mednafen/tremor/`) — **BSD-style** (Xiph.org, same license as
  libvorbis; notice in source headers).

Controlling finding: `PERMISSIVE_OR_COPYLEFT_OK` — a GPL-2.0-or-later core loaded
as a separately licensed, dynamically loaded shared object behind the
plugin-boundary model the app already uses for every core. **GPL-2.0-or-later IS
GPL-3-compatible** (the "or later" clause permits upgrading).

## Build integration notes

The `beetle_pce_fast_core` CMake target (`app/src/main/cpp/CMakeLists.txt`) mirrors
upstream's own Android build flags exactly:

- **Defines**: `FRONTEND_SUPPORTS_RGB565=1`, `MEDNAFEN_VERSION="0.9.26"`,
  `MEDNAFEN_VERSION_NUMERIC=926`, `__LIBRETRO__`, `_LOW_ACCURACY_`,
  `INLINE=inline`, `WANT_PCE_FAST_EMU`, `NEED_CD`, `NEED_TREMOR`.
  (`NEED_CD` is emitted unconditionally upstream and is a no-op — never used as
  a preprocessor guard. Upstream's `NEED_BPP`/`NEED_STEREO_SOUND`/
  `NEED_THREADING`/`NEED_CRC32` are Makefile variables, never `-D` flags.
  `HAVE_CHD`, `_7ZIP_ST`, and `ZSTD_DISABLE_ASM` are upstream's HAVE_CHD block
  and are deliberately **not** defined here.)
- **Includes** (as `SYSTEM PRIVATE`): project root (`.`), `mednafen`,
  `mednafen/include`, `mednafen/hw_misc`, `libretro-common/include`, and the
  shared `third_party/libretro/`. (There is no `mednafen/hw_sound` or
  `mednafen/hw_cpu` directory upstream at the pinned commit — the compiled
  sources resolve all headers via `mednafen/`, `mednafen/include`, and
  `mednafen/hw_misc`.) Upstream's `deps/zlib-1.2.11`, `deps/lzma-19.00/include`,
  `deps/libchdr/include`, and `deps/zstd/lib` include paths are conditional on
  `HAVE_CHD=1` and are not needed here.
- **Language**: C only (no C++ sources in the compiled set).
- **Link**: upstream's own `link.T` version script, `-Wl,-version-script=link.T`.
- **No per-ABI conditional** — there is no inline ARM assembly anywhere in the
  vendored tree (verified before vendoring), so armeabi-v7a needs no `-marm`
  workaround.
- **CD support note (deliberate scoping)**: this build defines **neither**
  `HAVE_CHD` nor `NEED_CD`-gated support; the 30-file CHD dependency tree
  (libchdr/lzma/zstd/zlib and `mednafen/cdrom/CDAccess_CHD.c`) is vendored for
  upstream parity but **not compiled**. The remaining CD-image sources
  (`mednafen/cdrom/*.c` minus `CDAccess_CHD.c`) are compiled because upstream
  compiles them unconditionally, but `CDAccess.c`'s CHD accessor is
  `#ifdef HAVE_CHD`-guarded, so `.chd` falls through to the plain-image path
  (fails at runtime) — acceptable because this manifest advertises **only
  `.pce`** (HuCard) content: cartridge content never triggers the CD code path.
  PC Engine CD (cue/ccd/chd, System Card BIOS) support would require CD BIOS
  firmware provisioning and is out of scope, mirroring the genesis_plus_gx
  segacd exclusion.

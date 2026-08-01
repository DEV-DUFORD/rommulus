# mGBA (Game Boy Advance) — vendoring record

Vendored from upstream `libretro/mgba` (the maintained libretro fork of the
mGBA emulator; the canonical `mgba-emu/mgba` repository no longer ships the
libretro core, which is why this fork exists and is authoritative for this
project's purposes).

**Pin:** commit `32de792178a3662cd0402c8568fccfaad4a764a1` (upstream `master`
HEAD at pin time, 2026-07-28). Upstream carries no usable release tags — only
ancient pre-release tags `0.1.0`/`0.1.1` (2011, mGBA 0.1.x development era) —
so the pin is a commit SHA, the same untagged-HEAD precedent as Genesis Plus
GX and fceumm. No submodules. `LICENSE` at repo root is MPL-2.0 (verified:
file begins `Mozilla Public License Version 2.0`).

## What was vendored, and why

Only the files upstream's own Android libretro build (`libretro-build/jni/Android.mk`
+ `libretro-build/Makefile.common`) actually compiles, plus the headers those
files need:

- **98 `.c` files** (the complete compiled set, verified file-by-file against
  the Android.mk/Makefile.common source lists — pure C, no `.cpp`, no `.S`):
  `src/arm/` (ARM/Thumb decoders), `src/core/` (core interface, cheats, config),
  `src/gb/` (Game Boy core — compiled by upstream's defines but not claimed in
  `CoreManifest`; see "Build integration notes" below), `src/gba/` (GBA core
  incl. `bios.c`/`hle-bios.c`), `src/sm83/` (SM83 CPU core), `src/util/`
  (VFS, configuration, CRC32, MurmurHash3, tables), `src/platform/libretro/`
  (the libretro wrapper: `libretro.c`, options, etc.), and
  `src/third-party/inih/ini.c` (the only compiled third-party component).
- **All headers under `src/` and `include/`** needed by the compiled set
  (excluding the non-compiled third-party directories below).
- **`libretro-build/`** — upstream's own Android build files kept for
  reference: `jni/Android.mk`, `Makefile.common`, and the per-ABI
  `Makefile.android_*` files.
- **`link.T`** — upstream's linker version script (exports only the `retro_*`
  symbols, hides everything else).
- **`LICENSE`** — the MPL-2.0 license text (see "License summary" below).

No build-generated files: the entire compiled set is committed source
(verified — no codegen step). There is **no libretro-common dependency**:
mGBA implements its own VFS/utilities, so no libretro-common subtree was
needed (contrast with Genesis Plus GX and fceumm).

## Deliberately excluded

- **`src/third-party/{discord-rpc, libpng, lzma, sqlite3, zlib}`** — present in
  upstream's tree but **not compiled** by the Android build (`MINIMAL_CORE=2`
  build; no `HAVE_LZMA` etc. defined). Licenses, in case a future build ever
  enables them: discord-rpc (MIT), libpng (PNG Reference Library License v2),
  lzma (Public Domain, Igor Pavlov), sqlite3 (Public Domain), zlib (zlib
  License). `blip_buf` does not exist at this commit.
- **Top-level build scripts** (`Makefile`, `Makefile.libretro`,
  `CMakeLists.txt`, `CMakePresets.json`, `Tupfile`, `version.cmake`) — the
  CMake target in `app/src/main/cpp/CMakeLists.txt` is the build now.
- **Non-build content**: `doc/`, `intl/`, `res/`, `tools/`, `cinema/`, `opt/`,
  `README_*`, `CHANGES`, `CONTRIBUTING.md`, `PORTING.md`. No desktop/other
  frontends exist in this fork's compiled path.

## License summary

- **mGBA core source** → **MPL-2.0** — file-level weak copyleft; commercial
  use permitted; no non-commercial restriction. Verified against the actual
  vendored source: 93 of 98 compiled files carry an explicit MPL-2.0 header,
  and an exhaustive sweep of all 98 compiled files for
  non-commercial/personal-use/no-sale clauses returned **zero hits**.
- **inih** (`src/third-party/inih/`) → **BSD-3-Clause** — vendored INI parser;
  the only compiled third-party component.
- **crc32** (`src/util/crc32.c`) → **Public Domain** (Gary S. Brown, "as
  desired without restriction").
- **MurmurHash3** (`src/util/hash.c`) → **Public Domain** (Austin Appleby).
- **`hle-bios.c`, `gbk-table.c`** → data-only files with no per-file license
  header; covered by the project-level MPL-2.0 `LICENSE`.
- **Not compiled (excluded, present upstream only)**: discord-rpc (MIT),
  libpng (PNG Reference Library License v2), lzma (Public Domain), sqlite3
  (Public Domain), zlib (zlib License).

Controlling finding for `CoreManifest`: **`PERMISSIVE_OR_COPYLEFT_OK`** — no
owner risk-acceptance required (same category as SameBoy and fceumm).

## Build integration notes

`mgba_core` in `app/src/main/cpp/CMakeLists.txt` mirrors upstream's
`libretro-build/jni/Android.mk` COREFLAGS and `libretro-build/Makefile.common`
RETRODEFS exactly:

- Defines: `HAVE_XLOCALE`, `HAVE_STRTOF_L`, `DISABLE_THREADING`,
  `MINIMAL_CORE=2`, `__LIBRETRO__`, `M_CORE_GBA`, `M_CORE_GB`, `ENABLE_VFS`,
  `ENABLE_DIRECTORIES`, `ENABLE_VFS_FD`, `HAVE_STDINT_H`, `HAVE_INTTYPES_H`,
  `INLINE=inline`, `COLOR_16_BIT`, `RESAMPLE_LIBRARY=2`, `M_PI`,
  `MGBA_STANDALONE`, `PATH_MAX=4096`, `NDEBUG`, `HAVE_LOCALTIME_R`,
  `COLOR_5_6_5`, `GIT_VERSION="32de792"`.
- **`-marm` for `armeabi-v7a` only**, mirroring upstream's
  `LOCAL_ARM_MODE := arm`. Note: unlike Genesis Plus GX's Tremor, this is a
  precaution, **not** a Thumb-2 workaround — an exhaustive search found zero
  inline ARM assembly anywhere in the compiled set (the fceumm outcome).
  `SSIZE_MAX=2147483648` is also added for `armeabi-v7a` only, matching
  upstream's "non-arm64" define.
- C99 (`C_STANDARD 99`), links `log` + `m`, links with
  `--version-script=link.T` and `--no-undefined`, include dirs
  `src`, `src/arm`, `include`, `src/platform/libretro`. Vendored code is
  exempt from this project's own `-Wall -Wextra` warning bar (existing
  convention).
- Host test-compile of all 98 files (macOS clang, upstream defines) passed
  modulo a macOS-only artifact: 14 files trip the SDK's fortified `strlcpy`
  variadic macro conflicting with mGBA's own `strlcpy` declaration — the NDK
  does not define `_USE_FORTIFY_LEVEL`, so Android builds are unaffected
  (same class of benign macOS-only finding as the Snes9x host-check).
- **BIOS / firmware**: the core embeds a complete HLE BIOS
  (`src/gba/hle-bios.c`, wired as the default in `src/gba/memory.c`). An
  external BIOS file is only consulted when the `mgba_use_bios` core option
  (default ON) finds one in the system directory; otherwise the HLE path is
  used silently. **GBA content launches without external BIOS provisioning —
  no `requiredFirmware` work needed.** (GB/GBC need no BIOS at all: no
  hardware BIOS exists on those platforms.)
- **Scope note**: upstream's defines compile the GB/GBC cores into the binary
  (`-DM_CORE_GB`, `src/gb/`, `src/sm83/`), but `CoreManifest.supportedSystems`
  is deliberately **`["gba"]` only** — the GB/GBC system pair belongs to the
  already-approved SameBoy core, and the SameBoy-vs-`gambatte` question is an
  open conflict to resolve with the owner (see `LIBRETRO_REFACTOR.md`,
  "Open conflict to resolve with the owner before Game Boy/Game Boy Color
  work resumes"). Do not claim `gb`/`gbc` for mgba without the owner's
  decision.

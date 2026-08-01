# fceumm — vendoring notes

Pinned upstream `https://github.com/libretro/libretro-fceumm` at commit
`b5e3566515c27dc66c9c20572171673126532e06` (upstream `master` HEAD at pin time,
2026-07-28; upstream carries **no release tags**, so an exact commit SHA is the
pin). License finding: **GPL-2.0-or-later**, classified
`PERMISSIVE_OR_COPYLEFT_OK` in `CoreManifest.kt` — no non-commercial restriction,
no owner risk-acceptance needed. Cross-references: `CoreManifest.kt`'s `fceumm`
entry, `docs/PHASE0_DECISIONS.md`, and `HANDOFF.md`'s "Phase 7 progress: fceumm"
section.

## What was vendored, and why

Only the files upstream's own Android libretro build (`jni/Android.mk` +
`Makefile.common` + `Makefile.libretro`) actually compiles were vendored — a
curated subset of `src/` plus the in-tree `libretro-common` subtree. The vendored
set is **633 source files (505 `.c` + 126 `.h` + 2 `.inc`)**:

- `src/boards/` (432 `.c`, 28 `.h`, 2 `.inc`) — all cartridge mapper/board
  emulation, including the PIC16C5X opcode tables pulled via `.inc`. This whole
  directory is wildcard-compiled by upstream, so it is vendored in full.
- `src/input/` (17 `.c`, 3 `.h`) — input-device emulation (gamepads, zapper,
  powerpad, etc.).
- `src/` top level (23 `.c`, 34 `.h`) — core CPU (`x6502`), PPU, cart/INES/UNIF
  loaders, cheat, crc32, fceu core, file I/O, filter, general, md5, nsf, palette,
  sound, state, unif, video, vsuni, and the `fceumm` libretro glue headers.
- `src/ntsc/nes_ntsc.c` + its 3 headers — Blargg's NTSC video filter
  (`HAVE_NTSC_FILTER`), plus `src/ntsc/license.txt`.
- `src/hdpack/` (6 `.c`, 2 `.h`) — HD-pack graphics support (`HAVE_HDPACK`,
  enabled to match upstream's own Android build).
- `src/palettes/` (6 `.h`) and `src/fir/` (10 `.h`) — palette and filter
  coefficient tables.
- `src/drivers/libretro/` — the libretro driver (`libretro.c`,
  `libretro_dipswitch.c`, `libretro_core_options*.h`), the `link.T` version
  script, and the vendored `libretro-common/` subtree (24 `.c` + 37 `.h` across
  `compat/`, `encodings/`, `file/`, `formats/{dds,png,vorbis,vp8,wav,webp}/`,
  `streams/`, `string/`, `time/`, `vfs/`).
- License texts: root `Copying` (full GPLv2 text) and `src/ntsc/license.txt`
  (LGPL-2.1).

The vendored file set was verified in three independent ways: file-by-file
against upstream's `Makefile.common`/`jni/Android.mk` source lists, a full host
`clang` test-compile of all 505 `.c` (all passed, shared library linked with zero
undefined symbols), and the `fceumm_core` CMake target's explicit source list.

## Deliberately excluded

- **FDS (Family Disk System)** — `.fds` content needs `disksys.rom` firmware,
  which this app does not provision. `CoreManifest` scope is cartridge-only:
  `nes`/`famicom`, extensions `.nes`/`.unf`. If `.fds` support is ever wanted, a
  `requiredFirmware = ["disksys.rom"]` decision and provisioning path must be
  added first.
- **Upstream's bundled zlib** — upstream itself deliberately leaves `HAVE_ZLIB`
  undefined (`Makefile.common`), so the bundled zlib is **never compiled**; the
  build uses libretro-common's clean-room DEFLATE codec (`encoding_deflate.c`)
  instead. No zlib symbols are vendored.
- **Top-level build files** — `Makefile`, `Makefile.libretro`, `Makefile.common`,
  and `jni/{Android.mk,Application.mk}` are NOT vendored; their relevant flags
  and source lists are reproduced in the `fceumm_core` CMake target (see Build
  integration notes).
- **CI, docs, and localization** — `.github/` workflows, `intl/` (Crowdin Python
  scripts), and the docs/notes (`README.md`, `Authors`, `changelog.txt`,
  `whatsnew.txt`, `zzz_todo.txt`) are not vendored; they are not compiled.
- **Optional firmware/aux files** — `gamegenie.nes` and `nes.pal` (only used if
  their optional core options are enabled) are not provisioned; the default build
  does not enable them.

## License summary

Composite top-level license is **GPL-2.0-or-later** (root `Copying`, and every
source header: "either version 2 of the License, or (at your option) any later
version"). There is **no non-commercial / no-sale / "personal use only" clause
anywhere** in the core. Vendored subcomponents carry their own separate,
permissive/copyleft-compatible licenses that add no further restriction:

- **`libretro-common/` subtree** — **MIT** (each file's permission header).
- **Blargg NTSC filter** (`src/ntsc/nes_ntsc.c`, `src/ntsc/license.txt`) —
  **LGPL-2.1-or-later** (Shay Green).
- **YM2413 emulator** (`src/boards/emu2413.c`) — **GPL-2.0-or-later**, covered by
  the core's own license (Mitsutaka Okazaki).
- **Bundled zlib** — present upstream but **not compiled** (see exclusions).

Controlling finding: `PERMISSIVE_OR_COPYLEFT_OK` — a GPL-2.0-or-later core loaded
as a separately licensed, dynamically loaded shared object behind the
plugin-boundary model the app already uses for every core. Unlike Genesis Plus GX
and Snes9x, no owner risk-acceptance is required; obligations are limited to
preserving the GPLv2+/MIT/LGPL notices and license texts with the core. See
`docs/PHASE0_DECISIONS.md` and `HANDOFF.md`'s "Phase 7 progress: fceumm" section.

## Build integration notes

The `fceumm_core` CMake target (`app/src/main/cpp/CMakeLists.txt`) mirrors
upstream's own Android build flags exactly:

- **Defines**: `__LIBRETRO__`, `PATH_MAX=1024`, `FCEU_VERSION_NUMERIC=9900`,
  `FRONTEND_SUPPORTS_RGB565`, `HAVE_NTSC_FILTER`, `HAVE_HDPACK`, `PSS_STYLE=1`,
  and `GIT_VERSION="b5e3566"` (upstream auto-generates this from git; pinned to
  the short SHA of the vendored commit for reproducibility).
- **Includes** (as `SYSTEM PRIVATE`, so vendored headers don't inherit the
  project's own `-Wall -Wextra` bar): `src/drivers/libretro`,
  `src/drivers/libretro/libretro-common/include`, `src`, `src/input`,
  `src/boards`, `src/ntsc`.
- **Warning suppressions** (matching upstream's own `Android.mk`):
  `-Wno-write-strings -Wsign-compare -Wundef -Wmissing-prototypes`.
- **Link**: upstream's own `src/drivers/libretro/link.T` version script,
  `--no-undefined`, `log m`; `-z max-page-size=16384` is left to the NDK default.
- **Language**: global `CMAKE_C_STANDARD 11` (no per-target override needed; host
  compile used `gnu11`). **No per-ABI conditional** — there is no `.S`/`.s` or
  inline ARM assembly anywhere in the vendored tree (verified before vendoring),
  so armeabi-v7a needs no `-marm`/`-D_ARM_ASSEM_` workaround.

Verified: full host `clang` test-compile of all 505 `.c` passed and linked with
zero undefined symbols before any Android/CMake work; `assembleRelease` built
both `armeabi-v7a` and `arm64-v8a` with **zero warnings** from `fceumm_core`;
per-ABI SHA-256 of the release, stripped `.so` files are recorded in
`CoreManifest.kt`'s `fceumm` entry.

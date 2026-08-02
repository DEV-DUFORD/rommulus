# VENDORING.md — prosystem (Atari 7800, "ProSystem")

Vendored 2026-08-02 by the native-core integration effort. This document
records why each file is here, what is excluded, how the core is built, and
the include-closure gate. It mirrors the vendoring record kept for the other
Phase 7 cores (`handy`, `mednafen_wswan`, `mednafen_ngp`, etc.).

## Upstream source and pin

- Repo: `https://github.com/libretro/prosystem-libretro` (branch `master`).
- Pinned commit: `363b6dfbd3e240762e022c2b4897b4fe55722be3` (`363b6df`,
  2026-06-04, master HEAD — the repo has **no release tags**, so master HEAD
  is pinned), verified via `git rev-parse`. Vendored 2026-08-02.
- Core display name: "ProSystem" (library version `1.3e`). RetroArch
  namespace / `TARGET_NAME`: `prosystem` — this is the `coreId` used
  throughout the app.
- Upstream build system is GNU Make; there is no upstream CMakeLists.txt.
  The CMake target in this repo reproduces the Unix/Android build path (see
  "Native build" below).

## License audit (file-by-file)

The core is **GPL-2.0-or-later** effective (GPL core engine + zlib audio
helpers + MIT libretro-common helpers), with **no non-commercial / no-sale
restriction** — classified `PERMISSIVE_OR_COPYLEFT_OK`, no owner
risk-acceptance fields. File-by-file:

| File(s) | License | Copyright |
|---|---|---|
| `core/` (C emulator engine: `*.c`, `*.h`, `libretro.c`, `libretro_core_options*.h`) | GPL-2.0-or-later | Copyright 2003,2004 Greg Stanton |
| `bupboop/` and `bupboop/coretone/` (zlib audio synth) | zlib/libpng-style permissive (see `bupboop/License.txt`) | Copyright (C) 2015–2016 Osman Celimli |
| `libretro-common/` (compiled `.c` + headers) | MIT | Copyright (C) 2010–2020 The RetroArch team |

Notes: the zlib audio license explicitly permits commercial use; the core
engine is GPL-2.0-or-later (GPL-3-compatible). No GPL-incompatible
components are in the compiled subset. `License.txt` at the tree root is the
GPL-2.0 text; `bupboop/License.txt` is the zlib text.

## Scope and firmware

- Atari 7800 **cartridge only**. Extensions: `a78 | cdf | cdfj` (from
  `info->valid_extensions` in `core/libretro.c`).
- **BIOS-free**: the optional 7800 BIOS is only consulted if present;
  otherwise the core runs via its built-in hardware-emulation fallback.
  `requiredFirmware` is empty in `CoreManifest.kt`.
- **No firmware / no BIOS blob vendored.**

## What is vendored (85 files, including this VENDORING.md)

32 C compile units (always compiled by upstream `Makefile.common`):

```
core/libretro.c  Bios.c  BupChip.c  Cartridge.c  Database.c  Hash.c
Maria.c  Memory.c  Palette.c  Pokey.c  ProSystem.c  Region.c
Riot.c  Sally.c  Tia.c
bupboop/coretone/channel.c  coretone.c  music.c  sample.c
libretro-common/compat/compat_posix_string.c  compat_snprintf.c
compat_strcasestr.c  compat_strl.c  fopen_utf8.c
libretro-common/encodings/encoding_utf.c
libretro-common/file/file_path.c  file_path_io.c
libretro-common/streams/file_stream.c  file_stream_transforms.c
libretro-common/string/stdstring.c
libretro-common/time/rtime.c
libretro-common/vfs/vfs_implementation.c
```

Plus all headers under `core/` and `bupboop/` (incl. `bupboop/types.h`),
`bupboop/License.txt`, the full `libretro-common/include/` tree (including
its own `libretro.h`), and the root `License.txt`, `link.T`, `Makefile`,
`Makefile.common`. The tree-root `Makefile`/`Makefile.common` are kept as the
authoritative record of the compile flags reproduced in CMake.

**Excluded:** `jni/` (`Android.mk`, `Application.mk` — the build config is
reproduced in this repo's CMake target instead), `README.md`, `.github/`,
`.gitlab-ci.yml`, `.travis.yml`, and all desktop-only / CI code.

**Include closure:** a full transitive include-closure check was run at
vendoring time over all 32 compile units (`gcc -fsyntax-only` with
`-D__LIBRETRO__ -DANDROID -fsigned-char -DGIT_VERSION=\"363b6df\"`); every
non-system `#include` resolves inside this tree plus the shared
`third_party/libretro`. **Zero backfills were required.** Note:
`libretro.h` ships inside this tree at `libretro-common/include/libretro.h`,
so both `<libretro.h>` and `"libretro.h"` resolve locally.

## Native build (CMake)

Target `prosystem_core` in `app/src/main/cpp/CMakeLists.txt` (lines
1858–1943), mirroring upstream `Makefile` + `Makefile.common`:

- **C standard:** `gnu11` (`C_STANDARD 11`), matching upstream's
  `-std=gnu11` usage for the libnx target and the `gnu11` host gate.
- **Compile options:** `-fsigned-char` (upstream unix/arm flags), `-Wno-unused-value` (suppresses a pre-existing upstream warning at `core/ProSystem.c:272` — "expression result unused"; not introduced by this integration).
- **Defines:** `ANDROID`, `__LIBRETRO__`, `GIT_VERSION=\"363b6df\"` (short
  hash of the pin; `libretro.c` provides a `""` fallback under
  `#ifndef GIT_VERSION`).
- **Includes (SYSTEM PRIVATE):** `core`, `bupboop/coretone`, `bupboop`, tree
  root, and `libretro-common/include` (matching upstream `INCFLAGS`), plus
  the shared `third_party/libretro` include dir used by the other cores.
- **Link:** upstream `link.T` version script
  (`{ global: retro_*; local: *; };`) + `--no-undefined`, links `log m`.
  `bupboop/coretone/*.c` and `libretro.c` include `<math.h>`, so `m` is
  required. `log` is linked per project convention for all cores.
- The Android build is **not** run as part of this vendoring pass (host
  syntax-only gate only); see the other Phase 7 cores for the build record.

## Host syntax-only gate (2026-08-02)

Run for every one of the 32 vendored `.c` compile units:

```
gcc -std=gnu11 -fsyntax-only -D__LIBRETRO__ -DANDROID -fsigned-char \
  -DGIT_VERSION=\"363b6df\" \
  -Ithird_party/cores/prosystem/core \
  -Ithird_party/cores/prosystem/libretro-common/include \
  -Ithird_party/cores/prosystem/bupboop \
  -Ithird_party/cores/prosystem/bupboop/coretone \
  -Ithird_party/libretro \
  <file.c>
```

Result: **32/32 passed** with no errors; zero backfills and zero
vendored-from-checkout additions required.

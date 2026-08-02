# VENDORING.md — handy (Atari Lynx, "Handy")

Vendored 2026-08-01 by the Phase 7 native-core integration effort. This
document records why each file is here, what is excluded, how the core is
built, and the release checksums. It mirrors the vendoring record kept for
the other Phase 7 cores (`mednafen_wswan`, `mednafen_ngp`, etc.).

## Upstream source and pin

- Repo: `https://github.com/libretro/libretro-handy` (branch `master`).
- Pinned commit: `bc55d462f0b2d6b073ea93dc552ebd73cec60fd1` ("…", 2026-04-20,
  master HEAD — the repo has **no release tags**, so master HEAD is pinned),
  verified via `git rev-parse`.
- Core display name: "Handy" (`HANDYVER` = "0.97"). RetroArch namespace /
  `TARGET_NAME`: `handy` — this is the `coreId` used throughout the app.
- Upstream build system is GNU Make + `jni/Android.mk` (NDK); there is no
  upstream CMakeLists.txt. The CMake target in this repo reproduces the
  Android build path (see "Native build" below).

## License audit (file-by-file)

The core is **GPL-2.0-or-later** effective (permissive core + LGPL/GPL audio
helpers + MIT helpers), with **no non-commercial / no-sale restriction** —
classified `PERMISSIVE_OR_COPYLEFT_OK` in `CoreManifest.kt`, no owner
risk-acceptance fields. File-by-file:

| File(s) | License | Copyright |
|---|---|---|
| `lynx/` (core emulator: `*.cpp`, `*.h`), `libretro/libretro.cpp`, `handy.h`, `libretro_core_options.h`, `libretro_core_options_intl.h` | zlib/libpng-style permissive (see `lynx/license.txt`) | Copyright (c) 2004 K. Wilkins |
| `blip/Blip_Buffer.cpp`, `Blip_Buffer.h` | LGPL-2.1-or-later | Copyright (C) 2003-2006 Shay Green |
| `blip/Stereo_Buffer.cpp`, `Stereo_Buffer.h` | GPL-2.0-or-later | Copyright (C) 2003-2006 Shay Green |
| `libretro-common/*` (compiled `.c` + headers) | MIT | Copyright (C) 2010-2020 The RetroArch team |

Notes: the zlib-style core license explicitly permits commercial use; the
LGPL-2.1 and GPL-2.0 audio helpers are GPL-compatible to link. Effective
license is GPL-2.0-or-later, which is **GPL-3-compatible**. No
GPL-incompatible components are in the compiled subset.

## Scope and firmware

- Atari Lynx **cartridge only**. Extensions: `lnx | lyx | o` (from
  `info->valid_extensions` in `libretro.cpp`).
- **BIOS-free**: optional HLE. `ROM_FILE` = `lynxboot.img` is only consulted
  if present (CRC 0xD973C9D); otherwise the core runs via its built-in
  hardware-emulation fallback. `requiredFirmware` is empty in
  `CoreManifest.kt`.
- **No firmware / no BIOS blob vendored.**

## What is vendored (70 files)

12 C++ compile units (always compiled by upstream `Makefile.common`):

```
libretro/libretro.cpp
lynx/lynxdec.cpp  lynx/cart.cpp    lynx/memmap.cpp
lynx/mikie.cpp    lynx/ram.cpp     lynx/rom.cpp
lynx/susie.cpp    lynx/system.cpp  lynx/eeprom.cpp
blip/Blip_Buffer.cpp                blip/Stereo_Buffer.cpp
```

13 C compile units (compiled only when `STATIC_LINKING != 1` — this project's
builds are dynamic, so they **are** required):

```
libretro-common/compat/compat_posix_string.c  compat_snprintf.c
compat_strcasestr.c  compat_strl.c  fopen_utf8.c
libretro-common/encodings/encoding_utf.c
libretro-common/file/file_path.c  file_path_io.c
libretro-common/streams/file_stream.c  file_stream_transforms.c
libretro-common/string/stdstring.c
libretro-common/time/rtime.c
libretro-common/vfs/vfs_implementation.c
```

Plus all headers under `lynx/` and `blip/`, `libretro/handy.h`,
`libretro/link.T`, `libretro_core_options.h`, `libretro_core_options_intl.h`
(which includes `libretro.h`), the full `libretro-common/include/` tree
(excluding `libretro.h` — the shared `third_party/libretro/libretro.h` is used
instead), and `lynx/license.txt`. No root `COPYING`/`LICENSE` exists upstream;
the permissive core license lives in `lynx/license.txt` and the LGPL/GPL/MIT
components carry per-file headers.

**Excluded:** `Makefile`, `Makefile.common`, `jni/` (`Android.mk`,
`Application.mk` — the build configuration is reproduced in this repo's CMake
target instead), `intl/`, `README.md`, `.github/`, `.gitlab-ci.yml`,
`.travis.yml`, and all desktop-only / CI code.

**Include closure:** a full transitive include-closure check was run at
vendoring time over all 25 compile units (`g++`/`gcc -fsyntax-only` for the
C++ and C sets); every non-system `#include` resolves inside this tree plus
the shared `third_party/libretro`. **Zero backfills were required.**

## Native build (CMake)

Target `handy_core` in `app/src/main/cpp/CMakeLists.txt` (lines 1776–1863),
mirroring upstream `jni/Android.mk` + `Makefile.common`:

- **C++ standard:** `gnu++11` (`CXX_STANDARD 11`), matching upstream
  `Makefile.common`. No inline ARM assembly anywhere, so no `-marm`.
- **Defines:** `ANDROID`, `__LIBRETRO__`, `HAVE_STRINGS_H`, `HAVE_STDINT_H`,
  `WANT_CRC32`, `GIT_VERSION=\"bc55d46\"` (short hash of the pin;
  `libretro.cpp` provides a `""` fallback under `#ifndef GIT_VERSION`).
- **INLINE omitted:** handy's compiled units contain no `INLINE` macros; only
  `libretro_core_options.h` uses retro_inline's self-defined `INLINE`, which
  needs no `-D`.
- **Includes (SYSTEM PRIVATE):** `lynx`, `libretro`, tree root, and
  `libretro-common/include` (matching upstream `INCFLAGS` exactly), plus the
  shared `third_party/libretro` include dir used by the other cores — this is
  where `#include <libretro.h>` and `#include "libretro.h"` resolve.
- **Link:** upstream `libretro/link.T` version script
  (`{ global: retro_*; local: *; };`) + `--no-undefined`, links `log m`.
  Upstream `Android.mk` also lists `-lz`, but that is **vestigial**: the core
  ships its own CRC32 table in `lynx/scrc32.h` (guarded by `WANT_CRC32`) and
  references no zlib symbols; `blip/Blip_Buffer.cpp` includes `<math.h>`, so
  `m` is required. No android-log calls exist; `log` is linked per project
  convention for all cores.
- `assembleDebug` and `assembleRelease` succeed cleanly for both ABIs.

## Checksums (SHA-256 of the release, stripped .so per ABI)

- `armeabi-v7a`: `5382a30ef80671b5b949b8c0e36699966b92d4c29e1c4351760a897ddd9f70cc`
- `arm64-v8a`: `ab7a28fbed62be8483af91aa92bd1207e99b1ba95cb2d53716644bdbea460cd9`

Methodology: SHA-256 of the stripped `.so` files from
`app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/<abi>/libhandy_core.so`,
matching every other Phase 7 core.

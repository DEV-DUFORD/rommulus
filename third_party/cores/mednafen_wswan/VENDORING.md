# VENDORING.md — mednafen_wswan (WonderSwan / WonderSwan Color, "Beetle WonderSwan")

Vendored 2026-08-01 by the Phase 7 native-core integration effort. This
document records why each file is here, what is excluded, how the core is
built, and the release checksums. It mirrors the vendoring record kept for
the other Phase 7 cores (`mednafen_ngp`, `beetle_pce_fast`, etc.).

## Upstream source and pin

- Repo: `https://github.com/libretro/beetle-wswan-libretro` (formerly
  `libretro/beetle-cygne-libretro` — upstream renamed the repository, so any
  older reference to "beetle_cygne" is stale; Kodi's
  `game.libretro.beetle-cygne` mirrors the same codebase).
- Pinned commit: `4b01295838ea89e3f1355bbe4cb5cf98aa6108cd` ("minor wording
  nitpick (#104)", 2026-07-31, master HEAD — the repo has **no release
  tags**, so master HEAD is pinned), verified via `git rev-parse`.
- Core display name: "Beetle WonderSwan" (`MEDNAFEN_CORE_NAME`), version
  v0.9.35.1. RetroArch namespace / `TARGET_NAME`: `mednafen_wswan` — this is
  the `coreId` used throughout the app.
- Upstream build system is GNU Make + `jni/Android.mk` (NDK); there is no
  upstream CMakeLists.txt. The CMake target in this repo reproduces the
  Android build path (see "Native build" below).

## License audit (file-by-file)

The core is **GPL-2.0-or-later** (root `COPYING` carries GPL-2.0 AND the
source headers carry "or (at your option) any later version") with **no
non-commercial / no-sale restriction** — classified
`PERMISSIVE_OR_COPYLEFT_OK` in `CoreManifest.kt`, no owner risk-acceptance
fields. File-by-file:

| File(s) | License | Copyright |
|---|---|---|
| `libretro.c`, `libretro_core_options.h`, `libretro_core_options_intl.h` | GPL-2.0-or-later (no per-file header; inherit `COPYING`) | — |
| `mednafen/settings.c`, `mednafen/state.c`, `mednafen/mempatcher.c`, `mednafen/wswan/comm.c` | GPL-2.0-or-later | Mednafen Team |
| `mednafen/wswan/wswan-memory.c`, `gfx.c`, `eeprom.c`, `tcache.c` | GPL-2.0-or-later | "Cygne" project, Copyright (C) 2002 Dox dox@space.pl |
| `mednafen/wswan/rtc.c` | GPL-2.0-or-later | Copyright (C) 2014-2020 Mednafen Team |
| `mednafen/wswan/v30mz.c` | **PERMISSIVE custom (NOT GPL)** — "may be used for purposes both commercial and noncommercial if you give the author, Bryan McPhail, a small credit somewhere" | Bryan McPhail (mish@tendril.co.uk), Oliver Bergmann, Fabrice Frances, David Hedley |
| `mednafen/sound/Blip_Buffer.c`, `mednafen/include/blip/Blip_Buffer.h` | LGPL-2.1-or-later | Copyright (C) 2003-2006 Shay Green |
| `libretro-common/` (compiled: `compat/compat_snprintf.c`, `compat/compat_strl.c` + headers) | MIT | Copyright (C) 2010-2020 The RetroArch team |

Notes: the NEC V30MZ CPU interpreter (`v30mz.c`) is the only non-GPL
compiled unit; it is a permissive license that explicitly allows commercial
use (with attribution) and is GPL-compatible to link. No GPL-incompatible
components are in the compiled subset.

## Scope and firmware

- WonderSwan / WonderSwan Color **cartridge only**. Extensions:
  `ws | wsc | pc2` (from `MEDNAFEN_CORE_EXTENSIONS` in `libretro.c`).
- **BIOS-free**: zero BIOS / boot-ROM references in the source; the ROM is
  loaded from the frontend memory buffer (`retro_load_game` `info->data`),
  not from a file path. `requiredFirmware` is empty in `CoreManifest.kt`.
- **No CHD/CD-ROM subsystem** (no cue/chd handling; the only "chd" hits are
  the `i_mov_chd8` V30MZ opcode — a register-move instruction, unrelated to
  disk formats).

## What is vendored (59 files)

16 C compile units (pure C — this core has **no C++ translation units**;
upstream removed the last one, `mempatcher.cpp`; no `-fexceptions` needed):

```
libretro.c
mednafen/wswan/sound.c          mednafen/wswan/interrupt.c
mednafen/wswan/comm.c           mednafen/wswan/rtc.c
mednafen/wswan/tcache.c         mednafen/wswan/gfx.c
mednafen/wswan/wswan-memory.c   mednafen/wswan/v30mz.c
mednafen/wswan/eeprom.c
mednafen/sound/Blip_Buffer.c
mednafen/mempatcher.c           mednafen/state.c     mednafen/settings.c
libretro-common/compat/compat_strl.c
libretro-common/compat/compat_snprintf.c
```

Plus ~40 headers (`mednafen/`, `mednafen/include/blip/`,
`mednafen/wswan/`, `libretro-common/include/`), `mednafen/wswan/start.inc`
(a 256-byte I/O-port dispatch data table `#include`d into `libretro.c` at
line 16 — **not** a compile unit), `link.T`, and `COPYING`.

**Excluded:** `Makefile`, `Makefile.common`, `jni/`, `Android.mk` (the
build configuration is reproduced in this repo's CMake target instead),
README, `.github/`, CI configs, docs.

**Include closure:** a full transitive include-closure check was run at
vendoring time over all 16 compile units; every non-system `#include`
resolves inside this tree. The only references that resolve outside are
under guards never compiled on Android/NDK (`windows.h`/`Xtl.h` under
`_WIN32`, `direct.h` under `_MSC_VER`, `config.h` under
`RARCH_INTERNAL && HAVE_CONFIG_H`). Zero backfills were required.

## Native build (CMake)

Target `mednafen_wswan_core` in `app/src/main/cpp/CMakeLists.txt`
(lines 1704–1774), mirroring upstream `jni/Android.mk` +
`Makefile.common`:

- **Defines:** `__LIBRETRO__`, `FRONTEND_SUPPORTS_RGB565=1`,
  `MEDNAFEN_VERSION_NUMERIC=926` (Android.mk value; the Makefile's 931
  belongs to the desktop build path), `WANT_16BPP`, `WANT_STEREO_SOUND`,
  `SIZEOF_DOUBLE=8`, `MPC_FIXED_POINT`, `STDC_HEADERS`,
  `__STDC_LIMIT_MACROS`, `_LOW_ACCURACY_`, `NDEBUG`, `INLINE=inline`
  (UNQUOTED — the stringified form breaks every `static INLINE` function,
  the same bug previously hit beetle_pce_fast and mednafen_ngp),
  `GIT_VERSION=\"4b01295\"` (short hash of the pin; `libretro.c` provides a
  `""` fallback).
- `ANDROID_ARM` (Android.mk defines it for `armeabi-v7a` only) is **omitted**:
  `v30mz.c` contains zero inline assembly, so the macro is vestigial.
- **Includes (SYSTEM PRIVATE):** tree root, `mednafen`, `mednafen/include`,
  `libretro-common/include`, plus the shared `third_party/libretro` include
  dir used by the other cores. (Upstream's `-I mednafen/intl`,
  `hw_sound`, `hw_cpu`, `hw_misc` do not exist in this repo.)
- **Link:** upstream `link.T` version script (`{ global: retro_*; local: *; };`)
  + `--no-undefined`, links `log m`. Pure C — no C++ runtime linked.
- `assembleDebug` and `assembleRelease` succeed cleanly for both ABIs with
  **zero warnings** from this target.

## Checksums (SHA-256 of the release, stripped .so per ABI)

- `armeabi-v7a`: `3a009628d9f21896442f214d6489c0e5ea620b85a23368a5b11094001718745c`
- `arm64-v8a`: `995233d8d0b9354a01dbc9e37b5121659bfb7e06f9e1a66e5607ef0314d6452f`

Methodology: SHA-256 of the stripped `.so` files from
`app/build/intermediates/stripped_native_libs/release/stripReleaseDebugSymbols/out/lib/<abi>/libmednafen_wswan_core.so`,
matching every other Phase 7 core.

# Beetle NeoPop (Neo Geo Pocket / Pocket Color) — vendoring record

Vendored from upstream `libretro/beetle-ngp-libretro` at commit
`a50d5ac288a81f2104ddf43195a4efdd15c72227` (2026-06-14), upstream master HEAD at
pin time; upstream publishes no release tags.

## What was vendored, and why

Only the files upstream's own Android libretro build (`jni/Android.mk` +
`Makefile.common`) actually compiles were vendored — a curated subset preserving
upstream relative paths. The vendored set is **101 files (5 `.cpp` + 37 `.c` +
56 `.h` + 1 `link.T` + 1 license text + 1 VENDORING.md)**. Of the 37 `.c` files,
**32 are compiled** by this project's scoped cartridge-only build; the other 5
(`mednafen/hw_cpu/z80-fuse/` auxiliary opcode tables: `opcodes_base.c`, `z80_cb.c`,
`z80_ddfd.c`, `z80_ddfdcb.c`, `z80_ed.c`) are vendored because they are `#include`d
into the compiled
`z80_ops.c` via `opcodes_base.c` — they are not separate compilation units.

- **Root** (1 `.c`, 1 `.h`) — the libretro driver (`libretro.c`), core options
  header (`libretro_core_options.h`), the `link.T` version script, and `COPYING`
  (GPL-2.0-or-later license text).

- **`mednafen/`** (3 `.c`, 1 `.cpp`, 10 `.h`) — core Mednafen utilities: state
  serialization (`state.c`, `state.h`, `state_helpers.h`), memory patching
  (`mempatcher.cpp`, `mempatcher.h`, `mempatcher-driver.h`), settings system
  (`settings.c`, `settings.h`), type definitions (`mednafen-types.h`), general
  helpers (`general.h`), file I/O stubs (`file.h`), git version stub (`git.h`),
  video output interface (`video.h`), and memory allocation (`masmem.h`).

- **`mednafen/ngp/`** (13 `.c`, 2 `.cpp`, 13 `.h`) — Neo Geo Pocket emulation
  core: TLCS-900h CPU interface, Z80 co-processor interface, memory management
  (`mem.c`), video/GPU (`gfx.c`), DMA controller (`dma.c`), interrupt handling
  (`interrupt.c`), BIOS HLE implementation (`biosHLE.c`), raw BIOS data (`bios.c`),
  cartridge ROM loading (`rom.c`), flash memory emulation (`flash.c`), audio
  synthesis (`sound.cpp`, `T6W28_Apu.cpp`), and real-time clock (`rtc.c`).

- **`mednafen/ngp/TLCS-900h/`** (6 `.c`, 6 `.h`) — TLCS-900h RISC CPU emulator:
  instruction interpretation pipeline (source/destination/register/single operand
  decoders), register state management.

- **`mednafen/hw_cpu/z80-fuse/`** (7 `.c`, 4 `.h`) — Z80 co-processor emulator
  (FUSE-derived): two compiled sources (`z80_ops.c`, `z80.c`) plus five auxiliary
  opcode tables (`opcodes_base.c`, `z80_cb.c`, `z80_ddfd.c`, `z80_ddfdcb.c`,
  `z80_ed.c`) which are `#include`d into the compilation unit, not compiled
  independently.

- **`mednafen/sound/`** (2 `.cpp`) — Blip_Buffer audio resampler implementation
  (`Blip_Buffer.cpp`) and stereo mixing buffer (`Stereo_Buffer.cpp`).

- **`mednafen/include/blip/`** (2 `.h`) — Blip_Buffer audio resampler headers
  (`Blip_Buffer.h`, `Stereo_Buffer.h`); compiled via `-I$(MEDNAFEN_DIR)/include`.

- **`libretro-common/`** (10 `.c`, 19 `.h`) — libretro utility library subset:
  file/stream I/O (`streams/file_stream.c`), path manipulation (`file/file_path.c`),
  string utilities (`compat/compat_strl.c`, `compat/compat_snprintf.c`,
  `compat/compat_posix_string.c`, `string/stdstring.c`, `compat/fopen_utf8.c`),
  encoding helpers (`encodings/encoding_utf.c`), VFS abstraction
  (`vfs/vfs_implementation.c`), timing (`time/rtime.c`), plus all headers needed
  to resolve their `#include` chains: boolean, retro_common_api, retro_inline,
  retro_miscellaneous, compat (strl, strcasestr, posix_string, msvc, fopen_utf8),
  encodings (utf), file (file_path), streams (file_stream), string (stdstring),
  time (rtime), vfs (vfs, vfs_implementation).

## Deliberately excluded

- **Build files** — `jni/Android.mk`, `jni/Application.mk`, `Makefile.common`,
  `Makefile` are NOT vendored; their relevant flags and source lists are
  reproduced in the `mednafen_ngp_core` CMake target.
- **CI, docs, tests** — `.github/`, `README.md`, `.gitignore`.
- **Nonexistent upstream include paths** — upstream's `INCFLAGS` references
  `-I$(MEDNAFEN_DIR)/intl`, `-I$(MEDNAFEN_DIR)/hw_sound`, and
  `-I$(MEDNAFEN_DIR)/hw_misc`, but none of these directories exist in the repo
  at the pinned commit. They are harmless no-ops (no source file includes anything
  from those paths) and are omitted from the CMake include list.

## License summary

Composite top-level license is **GPL-2.0-or-later** (root `COPYING`, version 2
with "or later" clause). There is **no non-commercial / no-sale / "personal use
only" clause anywhere** in the core. The `libretro.c` preamble credits
"neopop_tk" as the original project. Vendored subcomponents carry their own
separate, permissive/copyleft-compatible licenses that add no further restriction:

- **Blip_Buffer** (`mednafen/sound/Blip_Buffer.cpp`, `mednafen/include/blip/`) —
  **LGPL-2.1-or-later** (Shay Green). The `Stereo_Buffer` variant is GPL-licensed
  per its header comment.
- **z80-fuse** (`mednafen/hw_cpu/z80-fuse/`) — **GPL-2.0-or-later** (Philip
  Kendall / FUSE project).
- **libretro-common** (`libretro-common/`) — **MIT** (RetroArch team).

Controlling finding: `PERMISSIVE_OR_COPYLEFT_OK` — a GPL-2.0-or-later core loaded
as a separately licensed, dynamically loaded shared object behind the
plugin-boundary model the app already uses for every core. **GPL-2.0-or-later IS
GPL-3-compatible** (the "or later" clause permits upgrading). The LGPL-2.1
Blip_Buffer component is compatible with GPL-2.0 linking.

## Build integration notes

The `mednafen_ngp_core` CMake target (`app/src/main/cpp/CMakeLists.txt`) mirrors
upstream's own Android build flags exactly:

- **Defines**: `FRONTEND_SUPPORTS_RGB565=1`, `MEDNAFEN_VERSION_NUMERIC=926`,
  `WANT_16BPP`, `__LIBRETRO__`, `WANT_NGP_EMU`, `LOAD_FROM_MEMORY=1`,
  `INLINE=inline`. Upstream writes `INLINE="inline"` (with literal quotes in the
  `-D` flag); in CMake this must be expressed as `INLINE=inline` (the value is
  passed unquoted to the preprocessor, which handles it correctly). Note:
  `MEDNAFEN_VERSION_NUMERIC=926` matches the Android.mk pin; the generic Makefile
  uses 931 but the Android build overrides to 926.
- **Includes** (as `SYSTEM PRIVATE`): project root (`.`), `mednafen`,
  `mednafen/include`, `mednafen/hw_cpu`, `libretro-common/include`, and the
  shared `third_party/libretro/`. Upstream's `-I mednafen/intl`, `-I mednafen/hw_sound`,
  and `-I mednafen/hw_misc` are omitted — those directories do not exist in the repo.
- **Language**: C + C++ with `-fexceptions` (upstream sets `LOCAL_CPP_FEATURES := exceptions`).
- **Link**: upstream's own `link.T` version script, `-Wl,-version-script=link.T`.
- **No per-ABI conditional** — there is no inline ARM assembly anywhere in the
  vendored tree (verified before vendoring), so armeabi-v7a needs no `-marm`
  workaround.
- **Firmware note**: this core uses HLE BIOS (`biosHLE.c`) for cartridge-only
  operation; no `requiredFirmware` entries are needed in the manifest.

## Checksums (SHA-256 of the release, stripped `.so` per ABI)

Same verified methodology as the prior cores — hashes of the release-variant
stripped `libmednafen_ngp_core.so`:

- `armeabi-v7a`: `9cdd4f0bd6fc74de04e4e293dfb595c420e63f7479cc29473242cdc7918aa6f6`
- `arm64-v8a`: `2b7dd03031850e447decb3772f0e30df4b8d24651331a3813b3ffaef16fbc512`

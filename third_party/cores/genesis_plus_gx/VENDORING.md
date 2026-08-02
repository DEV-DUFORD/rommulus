# Genesis Plus GX — vendoring notes

Upstream: https://github.com/libretro/Genesis-Plus-GX
Pinned commit: `ca93fec870378f3bff65931bcd828d5e756cce75` (default branch `master`
HEAD at review time, 2026-07-31; upstream carries no release tags, so an exact
commit SHA is the only stable pin).
License: custom non-commercial redistribution license covering the core code
itself, plus several vendored subcomponents under their own permissive
licenses (`LICENSE.txt`, copied verbatim from the repository root — see
"License summary" below). See `CoreManifest.kt`'s `genesis_plus_gx` entry
(`app/src/main/java/com/romm/androidtv/emulation/model/CoreManifest.kt`) for
the full review record (reviewer, date, commercial-use finding, and the
recorded owner risk-acceptance this core relies on — see
`docs/PHASE0_DECISIONS.md` and `HANDOFF.md`'s 2026-07-31 Phase 7 entry).

## What was vendored, and why

Unlike SameBoy (vendored as a complete 1:1 mirror of two small subtrees),
Genesis Plus GX's upstream repository is large and includes several build
configurations (Gamecube/Wii, PSP2, UWP, MAME-derived debug tooling, Sega
CD CHD-compressed disc image support) this project does not use. Only the files this core's Android build actually compiles are vendored,
matching upstream's own `libretro/Makefile.common` `SOURCES_C` list, including
the CHD dependency sources (see "CHD dependencies" below):

- `core/*.c`, `core/*.h` — the shared engine: bus/memory management
  (`genesis.c`, `io_ctrl.c`, `loadrom.c`, `mem68k.c`, `membnk.c`, `memz80.c`,
  `state.c`, `system.c`), and the VDP (`vdp_ctrl.c`, `vdp_render.c`).
- `core/z80/` — Marat Fayzullin's Z80 core (as adapted by upstream).
- `core/m68k/` — the Musashi 68000 core (`m68kcpu.c`) plus its Sega CD
  co-processor variant (`s68kcpu.c`).
- `core/ntsc/` — Blargg's NTSC video filters (`md_ntsc.c`, `sms_ntsc.c`).
- `core/sound/` — PSG/YM2413/YM2612/YM3438 sound chip emulation, `blip_buf`
  resampling, and (see below) the vendored `tremor`/`minimp3` decoders.
- `core/input_hw/` — controller/peripheral emulation (gamepad, lightgun,
  paddle, team player, etc.) — all standard Genesis/MD, SMS, and Game Gear
  peripherals; none require external firmware.
- `core/cart_hw/` (including `svp/`) — cartridge mappers, save
  RAM/EEPROM/flash emulation, and the SVP co-processor (used by a small
  number of MD cartridges, e.g. Virtua Racing).
- `core/cd_hw/` — Sega/Mega CD hardware emulation. Vendored because it is
  unconditionally part of upstream's `GENPLUS_SRC_DIR` (compiled into every
  build, not behind a system-selection flag) even though this project's
  `CoreManifest` entry does not currently declare `segacd` as a supported
  system; see "Deliberately excluded" for the one part of this directory
  that is *not* vendored.
- `libretro/libretro.c`, `libretro_core_options.h`,
  `libretro_core_options_intl.h`, `osd.h`, `scrc32.h`, `link.T` — the
  libretro API wrapper, its declared core options, and upstream's own
  version script (kept as-is: exports only `retro_*` symbols, same
  convention this project already uses for `sameboy_core`).
  `Makefile.common`, `jni/Android.mk`, `jni/Application.mk` are kept for
  reference/audit only — this project's own `CMakeLists.txt` is what
  actually builds the core.
- `libretro/libretro-common/{compat,encodings,file,lists,memmap,streams,
  string,vfs}` — the specific MIT-licensed (per-file header, "The
  following license statement only applies to this file") libretro-common
  helper sources upstream's non-static-linking build compiles
  (`file_stream.c`, `file_stream_transforms.c`, `fopen_utf8.c`,
  `compat_snprintf.c`, `compat_strl.c`, `compat_strcasestr.c`,
  `compat_posix_string.c`, `encoding_utf.c`, `file_path.c`,
  `retro_dirent.c`, `string_list.c`, `dir_list.c`, `memalign.c`,
  `stdstring.c`, `vfs_implementation.c`), plus every public header those
  files transitively need. The CD-ROM-device (`cdrom/`, `HAVE_CDROM`) and
  UWP (`vfs_implementation_uwp.cpp`) variants are excluded — this project
  never sets `HAVE_CDROM` and never targets UWP.
- `libretro/deps/zlib-1.2.11` — only the six zlib-licensed `.c` files
  upstream's build always compiles (`adler32.c`, `crc32.c`, `inffast.c`,
  `inflate.c`, `inftrees.c`, `zutil.c`), used for ROM/state (de)compression,
  plus the headers they need and zlib's own `README`.

## CHD dependencies

Sega CD `.chd` support vendors and compiles the `libchdr`, LZMA SDK, and zstd
trees from Genesis Plus GX's own `libretro/deps/` directory at the same pinned
commit as the core. Their controlling licenses are libchdr's zlib license, LZMA
SDK's public-domain dedication, and zstd's BSD-2-Clause license. The copied
`LICENSE.txt`, `LICENSE`, and `LICENSE`/`COPYING` files preserve those notices.
`core/cd_hw/cdd.h` uses the dependency's installed-style
`<libchdr/chd.h>`/`<libchdr/cdrom.h>` include paths because this pinned
libchdr tree stores public headers under `include/libchdr/`, not the older
`src/` paths still named by the core header.

## Deliberately excluded

- **`libretro/libretro-common/cdrom/`** — only used when `HAVE_CDROM` is
  set (reading from a physical/host CD-ROM drive). Not applicable to an
  Android TV app; never set here.

## License summary

`LICENSE.txt` (copied verbatim from the upstream repository root) is a
composite notice covering the whole upstream repository, not only the
files vendored here. Skimming its full ~1,250 lines against what is
actually vendored:

- **Genesis Plus GX core code** (Copyright Charles MacDonald 1998-2003,
  Eke-Eke 2007-2026, portions Nicola Salmoria/MAME team): a custom
  BSD-style redistribution license with two added conditions — no sale/
  commercial use, and modified redistributions must offer complete
  corresponding source. This is the `NON_COMMERCIAL_RESTRICTED` finding
  recorded in `CoreManifest` and the reason this core relies on the
  owner's recorded Phase 7 risk-acceptance decision rather than a
  `PERMISSIVE_OR_COPYLEFT_OK` finding.
  `libretro/libretro.c` carries the same license text at the top of the
  file (Copyright Eke-Eke, Copyright Daniel De Matteis/RetroArch team).
- **Z80 core** (Marat Fayzullin): permissive, non-commercial-use notice
  bundled in the same upstream `LICENSE.txt`; does not add terms beyond
  the core's own finding above.
- **Musashi 68000 core** (`core/m68k/`): historically BSD-style/permissive
  per upstream's own licensing notes; no separate restrictive terms found
  layered on top in the reviewed `LICENSE.txt`.
- **Nuked OPN2** (`ym3438.c`; Copyright Alexey Khokholov "Nuke.YKT"):
  LGPL-2.1-or-later. Permissive/copyleft-compatible with a GPLv3
  application; imposes no further restriction on top of the core's own
  license.
- **Tremor** (`core/sound/tremor/`; Copyright Xiph.Org Foundation):
  BSD-style, permissive.
- **minimp3** (`core/sound/minimp3/`): CC0 1.0 Universal (public-domain
  equivalent), per its own `LICENSE.txt`, copied alongside upstream's.
- **zlib** (`libretro/deps/zlib-1.2.11/`): the zlib license, permissive.
- **libretro-common** vendored files: MIT, per each file's own header
  ("The following license statement only applies to this file").
- **CHD dependencies**: libchdr (zlib license), LZMA SDK (public domain), and
  zstd (BSD-2-Clause), copied from the same pinned Genesis Plus GX commit.

None of the permissive/copyleft subcomponents relax the core's own
non-commercial restriction; the core-code finding is controlling. See
`docs/PHASE0_DECISIONS.md` for how a `NON_COMMERCIAL_RESTRICTED` finding
interacts with GPLv3 section 10, and the 2026-07-31 Phase 7 entry in
`HANDOFF.md` for the owner's explicit, dated risk-acceptance for this core.

## Build integration notes

- Built with `-D_ARM_ASSEM_` defined only for `armeabi-v7a` (matching
  upstream's own `ifeq ($(TARGET_ARCH),arm)` gate), since
  `core/sound/tremor/asm_arm.h`'s inline assembly is 32-bit-ARM-only and
  must not be defined for `arm64-v8a`.
- `armeabi-v7a` is also compiled with `-marm`, matching upstream's own
  `LOCAL_ARM_MODE := arm` for this module: the NDK's default `armeabi-v7a`
  codegen is Thumb-2, where `asm_arm.h`'s predicated instructions
  (`addne`/`movne`/`moveq`/`subeq` outside an `IT` block) do not assemble.
  `-marm` selects classic 32-bit ARM encoding for this compilation unit
  instead of modifying vendored upstream assembly.
- `WANT_CRC32`/`USE_PER_SOUND_CHANNELS_CONFIG`/core flags mirror upstream's
  `libretro/jni/Android.mk` `COREFLAGS`, including `USE_LIBCHDR`, `_7ZIP_ST`,
  and `ZSTD_DISABLE_ASM`.
- Same version-script (`libretro/link.T`) and vendored-code warning
  exemption convention as `sameboy_core`: not held to this project's own
  `-Wall -Wextra`.
- `libretro/libretro.c` and `libretro/link.T` carry a small ROMM integration
  extension (`romm_get_save_memory_*` / `romm_apply_save_memory`) that exposes
  Sega CD's non-contiguous 8 KiB internal BRAM and optional backup-cartridge
  BRAM as one versioned save image. Upstream persists these as separate `.brm`
  files and reports no `RETRO_MEMORY_SAVE_RAM` for Sega CD, which otherwise
  makes the app's atomic checkpoint and RomM sync pipeline reject valid saves.

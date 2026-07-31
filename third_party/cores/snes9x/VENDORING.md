# Snes9x — vendoring notes

Upstream: https://github.com/snes9xgit/snes9x
Pinned ref: tag `1.63`, commit `921f9f7b83660eb44ad263022a57a4a029057c37`
(2024-07-07). Unlike Genesis Plus GX, upstream Snes9x publishes real,
signed-off release tags, so this core is pinned to the latest tagged release
rather than a moving branch HEAD — a strictly more auditable provenance
point.
License: the project's own non-commercial redistribution license (`LICENSE`,
copied verbatim from the repository root — see "License summary" below), plus
one vendored LGPL-2.1 subcomponent. See `CoreManifest.kt`'s `snes9x` entry
(`app/src/main/java/com/romm/androidtv/emulation/model/CoreManifest.kt`) for
the full review record (reviewer, date, commercial-use finding, and the
recorded owner risk-acceptance this core relies on — see
`docs/PHASE0_DECISIONS.md` and `HANDOFF.md`'s Phase 7 entries).

## What was vendored, and why

Snes9x's upstream repository is large: it includes GTK+/Win32/macOS desktop
GUI frontends, a Vulkan-based shader/filter pipeline with several vendored
third-party libraries (`external/cubeb`, `external/fmt`, `external/glad`,
`external/glslang`, `external/imgui`, `external/SPIRV-Cross`,
`external/vulkan-headers`, `external/VulkanMemoryAllocator-Hpp`), a netplay
client/server, an interactive debugger, and its own JMA/ZIP ROM-archive
readers. None of that is used by the libretro Android build. Only the files
upstream's own `libretro/Makefile.common` actually compiles (or `#include`s
inline as part of its "unity build" pattern for a few subsystems) are
vendored here, mirroring the file list exactly:

- `core/*.cpp`, `core/*.h` — the SNES core: CPU (`cpu.cpp`, `cpuexec.cpp`,
  `cpuops.cpp`), PPU/graphics (`ppu.cpp`, `gfx.cpp`, `tile.cpp` and its
  `tileimpl-*.cpp` variants), memory mapping (`memmap.cpp`, `dma.cpp`), and
  the libretro-facing `snes9x.cpp`/`controls.cpp`/`movie.cpp`/`cheats*.cpp`/
  `conffile.cpp`/`clip.cpp`/`crosshairs.cpp`/`screenshot.cpp`/
  `snapshot.cpp`/`stream.cpp`/`fscompat.cpp`/`sha256.cpp`/`bml.cpp`.
- Enhancement-chip emulation actually compiled by the libretro build:
  `bsx.cpp` (BS-X), `c4.cpp`/`c4emu.cpp` (Cx4), `dsp1.cpp`–`dsp4.cpp`
  (NEC DSP series), `fxemu.cpp`/`fxinst.cpp` (Super FX), `obc1.cpp` (OBC-1),
  `sa1.cpp`/`sa1cpu.cpp` (SA-1), `sdd1.cpp`/`sdd1emu.cpp` (S-DD1),
  `seta.cpp`/`seta010.cpp`/`seta011.cpp`/`seta018.cpp` (ST-01x/ST-018),
  `spc7110.cpp` (+ its inline-included `spc7110emu.cpp`/`spc7110dec.cpp`),
  `srtc.cpp` (+ its inline-included `srtcemu.cpp`), `msu1.cpp` (MSU-1).
- `core/apu/apu.cpp`, `core/apu/apu.h`, `core/apu/resampler.h`, and the full
  `core/apu/bapu/` tree (SNES sound: `dsp/` — byuu's `SPC_DSP`, `smp/` — the
  S-SMP core and its `core/`/`debugger/` unity-build-included pieces,
  `snes/snes.hpp`) — the whole subtree is vendored as-is because upstream's
  three separately-compiled translation units (`sdsp.cpp`, `smp.cpp`,
  `smp_state.cpp`) `#include` the rest inline; it is not meaningfully
  separable.
- `core/filter/snes_ntsc.c`, `snes_ntsc.h`, `snes_ntsc_config.h`,
  `snes_ntsc_impl.h`, `snes_ntsc-license.txt` — Blargg's NTSC video filter,
  the only file from `filter/` the libretro build compiles (the 2xSaI/EPX/
  HQ2x/sharp-bilinear/xBRZ filters in the same directory are for other
  frontends and are not compiled here).
- `libretro/libretro.cpp`, `libretro.h`, `libretro_core_options.h`,
  `libretro_core_options_intl.h`, `link.T` — the libretro API wrapper, its
  declared core options, and upstream's own version script (kept as-is:
  exports only `retro_*` symbols, same convention as `sameboy_core` and
  `genesis_plus_gx_core`). `Makefile`, `Makefile.common`, `jni/Android.mk`
  are kept for reference/audit only — this project's own `CMakeLists.txt` is
  what actually builds the core.
- `libretro/libretro-common/include/retro_inline.h` — the single
  libretro-common header this build actually uses (an `INLINE` macro
  compatibility shim); MIT-licensed per its own file header ("The following
  license statement only applies to this file").
- `core/LICENSE`, `core/README.md` — copied for provenance.

## Deliberately excluded

- **`jma/`, `unzip/`** — Snes9x's own JMA-compressed-ROM and ZIP readers.
  Upstream's `libretro/Makefile.common` does not compile either directory
  (verified: neither `jma.cpp`/`s9x-jma.cpp` nor `unzip.c` appear in
  `SOURCES_C`/`SOURCES_CXX`), and `memmap.cpp`'s only reference to JMA
  (`#include "jma/s9x-jma.h"`) is compiled out behind `#ifdef JMA_SUPPORT`,
  which this build never defines. This project already resolves and
  decompresses ROM content itself before handing raw data to the core, so
  neither dependency is needed. JMA's license (`jma/license.txt`) is GPL/LGPL
  with upstream's own stated exception for use inside Snes9x; not vendored
  since it is not compiled.
- **`external/` (cubeb, fmt, glad, glslang, imgui, SPIRV-Cross,
  vulkan-headers, VulkanMemoryAllocator-Hpp)** — desktop Vulkan
  renderer/GUI dependencies for the GTK+/Win32/macOS frontends. Not
  referenced anywhere in `libretro/Makefile.common`; not applicable to a
  libretro core.
- **`debug.cpp`, `netplay.cpp`, `server.cpp`, `loadzip.cpp`,
  `statemanager.cpp`, `spc7110dec.cpp`/`spc7110emu.cpp` as standalone
  translation units** — not in `SOURCES_CXX`. `debug.h`/`netplay.h` are still
  vendored (as headers) because a few compiled files `#include` them for
  declarations, but their bodies are entirely guarded by `#ifdef DEBUGGER`/
  runtime-only declarations never defined in this build, so their `.cpp`
  counterparts are not needed and are not vendored.  `spc7110dec.cpp`/
  `spc7110emu.cpp` *are* vendored (see above) because `spc7110.cpp`
  `#include`s `spc7110emu.cpp` inline, which in turn `#include`s
  `spc7110dec.cpp` inline — neither is a separately compiled unit, but both
  are required source.
- **GTK+/Win32/macOS/Qt frontend code** (`gtk/`, `win32/`, `macosx/`, `qt/`,
  `unix/`) — desktop-only ports; irrelevant to an Android libretro core.
- **`data/`** — desktop GUI icon/resource assets; not referenced by any
  vendored source file.

## License summary

`core/LICENSE` (copied verbatim from the upstream repository root) is a
composite notice covering the whole upstream repository, not only the files
vendored here. Skimming its full text against what is actually vendored:

- **Snes9x core code** (copyrights spanning Gary Henderson, Jerremy Koot, and
  many other contributors 1996-2023; the libretro port itself additionally
  copyrighted 2011-2017 by Hans-Kristian Arntzen and Daniel De Matteis "under
  no circumstances will commercial rights be given"): permission to use,
  copy, modify, and distribute in source and binary form **for
  non-commercial purposes only**; explicitly "freeware for PERSONAL USE
  only." This is the `NON_COMMERCIAL_RESTRICTED` finding recorded in
  `CoreManifest` and the reason this core relies on the owner's recorded
  Phase 7 risk-acceptance decision rather than a `PERMISSIVE_OR_COPYLEFT_OK`
  finding — the same posture already recorded for Genesis Plus GX.
- **Enhancement-chip emulation** (BS-X, C4, DSP1-4, OBC1, SA-1, S-DD1,
  SETA/ST-01x, SPC7110, S-RTC, Super FX): each individually copyrighted by
  named contributors per the same `LICENSE` file, all covered by the same
  overall Snes9x license terms above; no separate, more permissive terms
  found layered on top.
- **S-SMP/S-DSP sound core** (`core/apu/bapu/`; byuu, 2016 for the S-SMP
  core used in 1.54+; Shay Green's sound emulator lineage for 1.52+): no
  distinct license header found in the vendored files themselves; covered by
  the same blanket Snes9x project license (the top-level `LICENSE`
  explicitly covers "Specific ports contains the works of other authors. See
  headers in individual files" only where such headers exist — none do
  here, so the main license governs).
- **snes_ntsc** (`core/filter/snes_ntsc.c`; Copyright Shay Green,
  2006-2007): **LGPL-2.1**, per the accompanying
  `snes_ntsc-license.txt`. Permissive/copyleft-compatible with a GPLv3
  application; imposes no further restriction on top of the core's own
  license.
- **libretro-common** (`retro_inline.h`): MIT, per the file's own header.

None of the permissive/copyleft subcomponents relax the core's own
non-commercial restriction; the core-code finding is controlling. See
`docs/PHASE0_DECISIONS.md` for how a `NON_COMMERCIAL_RESTRICTED` finding
interacts with GPLv3 section 10, and the Phase 7 entries in `HANDOFF.md` for
the owner's explicit, dated risk-acceptance covering both Genesis Plus GX and
Snes9x.

## Build integration notes

- No vendored inline ARM assembly was found in any compiled Snes9x source
  file (verified by search across the full compiled file list before
  vendoring), so — unlike Genesis Plus GX's Tremor dependency — no `-marm`
  or Thumb-2 workaround is needed for `armeabi-v7a`.
- Requires `-std=c++14` (matching upstream's own `libretro/Makefile`); the
  rest of `COREFLAGS` mirrors upstream's `libretro/jni/Android.mk` exactly
  (`-DANDROID -D__LIBRETRO__ -DHAVE_STRINGS_H -DRIGHTSHIFT_IS_SAR`).
- Verified this vendored file set compiles cleanly (host `g++ -std=c++14`,
  all upstream-listed `SOURCES_C`/`SOURCES_CXX` translation units) before
  wiring the Android/CMake build; only benign `sprintf`-deprecation warnings
  were observed, all inside vendored upstream code.
- Same version-script (`libretro/link.T`) and vendored-code warning
  exemption convention as `sameboy_core`/`genesis_plus_gx_core`: not held to
  this project's own `-Wall -Wextra`.

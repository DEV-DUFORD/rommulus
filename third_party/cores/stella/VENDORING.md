# Stella (Atari 2600) — vendoring record

Vendored from upstream `stella-emu/stella` at release tag **`7.0`**, commit
`d55b1aec0d067a4c901a6dcdf81cb8f579685659` (2024-10-05). The upstream default
branch is `master`; the release tag was preferred over master HEAD for provenance
precedent (matching Snes9x's tag-pin approach). Master HEAD at pin time was
`154a467c3a1ba6fa1d85c6776ac4f3b27558e5ad` (2026-07-29) and uses `-std=c++23`;
tag 7.0 uses `-std=c++20`. Tag 7.0 builds cleanly under NDK r27.2 / CMake 3.22.1
with zero compilation errors, so the fallback to master was not needed.

## What was vendored, and why

Only the files upstream's own Android libretro build
(`src/os/libretro/jni/Android.mk` + `src/os/libretro/Makefile.common`) actually
compiles were vendored — a curated subset of `src/` preserving upstream relative
paths. The vendored set is **382 files (148 `.cxx` + 229 `.hxx/.hpp` + 1 `.c` +
1 `.ins` + 1 `link.T` + 2 license texts)**:

- **`os/libretro/`** (3 `.cxx`, headers) — the libretro driver (`libretro.cxx`,
  `FSNodeLIBRETRO.cxx`, `StellaLIBRETRO.cxx`) and the `link.T` version script.
- **`common/`** (28 `.cxx`, headers) — core utilities: audio queue/settings,
  base types, bezel, FPS meter, ZIP handler, key/joy mapping, logging, mouse
  control, palette/phosphor handlers, joystick/keyboard handlers, rewind/state/
  timer management, video mode, TV filters (AtariNTSC, NTSCFilter), repository
  system (JSON/configfile/property-file key-value stores).
- **`common/audio/`** (headers only) — audio resampler interfaces (Lanczos,
  SimpleResampler); used by `AudioQueue.cxx`.
- **`common/tv_filters/`** (2 `.cxx`, headers) — Atari NTSC video filter.
- **`common/repository/`** (5 `.cxx`, headers) — composite key-value repository
  system (JSON adapter, configfile, JSON file, property file backends).
- **`common/exception/`** (headers only) — `FatalEmulationError.hxx`,
  `EmulationWarning.hxx`; referenced by emucore sources.
- **`common/sdl_blitter/`** (headers only) — blitter interface headers; included
  transitively but not compiled as `.cxx` for the libretro build.
- **`emucore/`** (80 `.cxx`, headers, 1 `.ins`) — complete emulation core:
  cartridge bankswitch types (47 cart classes), M6502 CPU (`M6502.cxx` +
  `M6502.ins` opcode table), M6532 CIA, TIA video/audio chip (15 `.cxx` in
  `tia/` and `tia/frame-manager/`), ELF loader (`elf/`), controllers, genesis
  cartridge support, serialisation, settings system.
- **`emucore/exception/`** (headers only) — emulation-level exception types.
- **`lib/json/`** (header-only nlohmann/json: `json.hpp`, `json_lib.hxx`,
  `LICENSE.MIT`) — MIT-licensed JSON library, included inline by repository
  sources.
- **`lib/nanojpeg/`** (`nanojpeg.c`, `nanojpeg_lib.hxx`, license notice in
  source header) — MIT-licensed JPEG decoder, compiled inline via `#include`
  from the header wrapper (not listed separately in SOURCES_CXX).

No libretro-common dependency: Stella implements its own I/O and utilities; the
upstream Makefile.common's `LIBRETRO_COMM_DIR` variable is only referenced for
an MSVC-specific include path that is never exercised on Android.

## Deliberately excluded

- **Desktop GUIs** — `src/gui/`, `src/os/macos/`, `src/os/windows/`,
  `src/os/unix/` (GTK/Qt/macOS native UIs, not compiled by the libretro build).
- **Debugger** — `src/debugger/` (interactive debugger, not used in libretro).
- **Cheat engine** — `src/cheat/` (not compiled for libretro Android).
- **Tools** — `src/tools/` (cart creator, image tools, etc.).
- **Vendored third-party libs NOT compiled by the Android build** —
  `src/lib/zlib/` (.c files, not in SOURCES_CXX; zlib is header-only for this
  build), `src/lib/libpng/` (conditional on `IMAGE_SUPPORT`, undefined for
  Android), `src/lib/sqlite/` (not compiled by libretro build),
  `src/lib/httplib/` (header-only, not included by any compiled source),
  `src/lib/tinyexif/` (not included by any compiled source).
- **Top-level build files** — Makefiles, CMakeLists.txt, Android.mk/Application.mk
  are NOT vendored; their relevant flags and source lists are reproduced in the
  `stella_core` CMake target.
- **CI, docs, tests, localization** — `.github/`, `docs/`, `tests/`, `intl/`.

## License summary

Composite top-level license is **GPL-2.0-only** (root `License.txt`, version 2
without "or later" clause). There is **no non-commercial / no-sale / "personal
use only" clause anywhere** in the core. Vendored subcomponents carry their own
separate, permissive/copyleft-compatible licenses that add no further restriction:

- **nlohmann/json** (`lib/json/json.hpp`, `lib/json/LICENSE.MIT`) — **MIT**.
- **NanoJPEG** (`lib/nanojpeg/nanojpeg.c`) — **MIT** (Martin J. Fiedler,
  license notice in source header).
- **libretro.h** — included from the project's shared `third_party/libretro/`
  directory; each file carries its own permissive license header.

Controlling finding: `PERMISSIVE_OR_COPYLEFT_OK` — a GPL-2.0-only core loaded as
a separately licensed, dynamically loaded shared object behind the plugin-boundary
model the app already uses for every core. Note: **GPL-2.0-only is NOT
GPL-3-compatible** (the "or later" clause is absent); this core ships as a
separately-licensed .so, same posture as the existing NON_COMMERCIAL_RESTRICTED
cores. No owner risk-acceptance is required for `PERMISSIVE_OR_COPYLEFT_OK`.

## Build integration notes

The `stella_core` CMake target (`app/src/main/cpp/CMakeLists.txt`) mirrors
upstream's own Android build flags exactly:

- **Defines**: `ANDROID`, `__LIB_RETRO__`, `HAVE_STRINGS_H`, `SOUND_SUPPORT`,
  and `GIT_VERSION="d55b1ae"` (pinned to the short SHA of the vendored commit).
- **Includes** (as `SYSTEM PRIVATE`): `os/libretro`, project root (`.`),
  `emucore`, `emucore/elf`, `emucore/tia`, `common`, `common/audio`,
  `common/tv_filters`, `common/sdl_blitter`, `common/repository/sqlite`,
  `lib/json`, `lib/nanojpeg`, and the shared `third_party/libretro/`.
- **Language**: `-std=c++20` (upstream tag 7.0; master HEAD uses c++23 but tag
  was preferred for provenance).
- **Warning suppressions**: none beyond SYSTEM include treatment — upstream's
  own sources use `#pragma clang diagnostic ignored "-Weverything"` around
  third-party includes (nlohmann/json, nanojpeg), so no additional per-target
  warning flags are needed.
- **Link**: upstream's own `link.T` version script, `--no-undefined`, `log m`.
- **`-fexceptions`** from upstream's `Application.mk` (`APP_CPPFLAGS`).
- **No per-ABI conditional** — there is no inline ARM assembly anywhere in the
  vendored tree (verified before vendoring), so armeabi-v7a needs no `-marm`
  workaround.
- **Zero warnings** from `stella_core` in a clean `rm -rf app/.cxx` rebuild
  (both ABIs).

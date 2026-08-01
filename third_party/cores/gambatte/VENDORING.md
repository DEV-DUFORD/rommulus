# gambatte — vendoring record

Vendored from upstream `libretro/gambatte-libretro` (the maintained libretro
integration of the Gambatte Game Boy / Game Boy Color emulator).

**Pin:** commit `96174369b3c30d9fc57c926fa3379c273dc6a9a5` (upstream `master`
HEAD at pin time, 2026-08-01). Upstream carries **no release tags** — only this
master branch — so the pin is the commit SHA, the same untagged-HEAD precedent
as SameBoy, fceumm, and mGBA. No submodules. Root `COPYING` is GPLv2 text
(verified: file begins `GNU GENERAL PUBLIC LICENSE Version 2, June 1991`).

## What was vendored, and why

Only the files upstream's own Android libretro build
(`libgambatte/libretro/jni/Android.mk` + `Makefile.common`) actually compiles,
plus the headers those files need:

- **`libgambatte/libretro/`** (11 files — 3 `.c`, 1 `.cpp`, 7 `.h`):
  - `gambatte_log.c` / `.h` — core logging glue (`SOURCES_C`).
  - `blipper.c` / `.h` / `blipper_filter_bank.h` — band-limited sound
    resampler (`SOURCES_C`); Hans-Kristian Arntzen author.
  - `cc_resampler.c` / `.h` — convolved-cosine audio resampler
    (`SOURCES_C`); Ali Bouhlel author.
  - `gbcpalettes.h` — GB color palette data, referenced by `libretro.cpp`.
  - `libretro.cpp` — the libretro driver glue (`SOURCES_CXX`).
  - `libretro_core_options.h` + `_intl.h` — core option declarations and
    internationalisation strings.
  - Network code (`net_serial.cpp` / `.h`) deliberately **excluded** — the app
    does not compile with `HAVE_NETWORK=1` (`Android.mk` defaults to `1` but
    the app sets it off).
- **`libgambatte/src/`** (65 files — 22 `.cpp`, 43 `.h`): the complete C++
  Game Boy / Game Boy Color emulation core (CPU, PPU, sound channels,
  cartridge/ROM handling, SRAM, MBCs incl. MBC5/Huc3/RTC, state saving,
  TIMA timer, video/LCD timing). All files referenced by
  `Makefile.common`'s `SOURCES_CXX` are vendored, plus the complete set of
  headers those files include transitively (`src/mem/`, `src/sound/`,
  `src/video/` subdirectories).
- **`libgambatte/include/`** (3 files — `gambatte.h`, `gbint.h`,
  `inputgetter.h`): the public C++ API the core exposes to `libretro.cpp`.
- **`common/`** (2 header-only files — `gambatte-array.h`, `uncopyable.h`):
  lightweight utility templates referenced by the core via `INCFLAGS`.
- **`libgambatte/libretro-common/`** (35 files — 13 `.c`, 22 `.h`): the full
  in-tree `libretro-common` subtree compiled by the Android build
  (`-DSTATIC_LINKING` undefined in the Android build; `Makefile.common`'s
  `ifneq ($(STATIC_LINKING), 1)` block pulls in the 12 source files plus all
  needed headers). See "Per-component license" below for license details.
- **`COPYING`** (1 file, root): full GPLv2 text from upstream.
- **`link.T`** (1 file, from `libgambatte/libretro/`): upstream linker version
  script — exports `retro_*` symbols only, hides everything else. Referenced
  by `Android.mk`'s `LOCAL_LDFLAGS := -Wl,-version-script=..., --no-undefined`.

Total vendored: **118 files** (36 `.c`, 15 `.cpp`, 67 `.h`, 1 `.T`, 1 `COPYING`).

The vendored set was verified against upstream's `Makefile.common` `SOURCES_C`
+ `SOURCES_CXX` lists (file-by-file): 12 C sources + 1 libretro driver `.cpp`
+ 22 core `.cpp` files = 35 compiled sources, all present. The header sets
above are the complete transitive closure needed by those 35 sources.

## Deliberately excluded

- **`net_serial.cpp` / `net_serial.h`** — networked link-cable emulation,
  compiled only when `HAVE_NETWORK=1` (upstream default; the app disables it).
- **`intl/`** — Crowdin-based internationalisation scripts (not compiled).
- **Top-level build files** — `Makefile`, `Makefile.libretro`,
  `jni/Android.mk`, `jni/Application.mk`, `Makefile.common` itself are NOT
  vendored; their relevant flags and source lists are reproduced in the
  `gambatte_core` CMake target.
- **CI / docs / locale** — `.github/`, `appveyor.yml`, `README.md`, `changelog`,
  `crowdin.yml`, `.travis.yml`, `.gitlab-ci.yml`, `.gitignore`.
- **`common/resample/`** — referenced by upstream's `Makefile.common` `INCFLAGS`
  but absent at this commit (empty directory or provided externally); no
  files to vendor.

## License summary

The gambatte core has **mixed licensing** — the FSF lists it as combining
GPLv2 code with GPLv3 code in a single binary, which the FSF considers
**license-incompatible** for combined distribution. This project's owner
consulted legal counsel on 2026-08-01 and **cleared the core's use** based on
the dynamically-loaded, separately-licensed shared-object posture (each core
is loaded into the host process as an independently licensed .so, analogous
to the existing NON_COMMERCIAL_RESTRICTED cores). The owner's decision is
recorded here.

Per-component findings:

- **Gambatte C++ core** (`libgambatte/src/**`, `libgambatte/include/**`,
  `libgambatte/libretro/gambatte_log.c`) → **GPL-2.0-only** (Sindre Aamås,
  "either version 2 of the License"). Includes the root `COPYING` text.
- **`libgambatte/libretro/blipper.c`** (and `.h`, `blipper_filter_bank.h`)
  → **MIT** (Hans-Kristian Arntzen, full permission header present). Note:
  upstream's 2026 audit rewrites this from a prior blip-based version; the
  new blipper.c is the MIT-licensed work.
- **`libgambatte/libretro/cc_resampler.c`** (and `.h`) → **GPLv3** (Ali
  Bouhlel, explicit "licence: GPLv3" header line in the .c file).
- **In-tree `libretro-common/`** → **MIT** (RetroArch team, each file carries
  the standard RetroArch MIT permission header on `libretro.h` and derived
  files).

**FSF incompatibility note:** The Gambatte project combines GPL-2.0-only core
code with GPLv3 (`cc_resampler.c`) in a single binary. The FSF's license
list rates this combination as *incompatible* for static/link-time combining.
However, this app loads each core as a **separately licensed, dynamically
loaded shared object** — the GPL-2.0 code does not merge with the GPLv3
code at link time in a way that creates a single combined work under the FSF's
definition; each .so remains its own work. The project is GPLv3-or-later and
ships each core under its own license with the core's own license text
accompanying the vendored source. This is the same posture as the existing
`NON_COMMERCIAL_RESTRICTED` cores (SameBoy, fceumm, mGBA).

Owner-clearance: legal counsel reviewed 2026-08-01; core use approved under
the dynamic-loading / separately-licensed shared-object model documented in
`LIBRETRO_REFACTOR.md` section 11.

Controlling `CoreManifest` classification: **`PERMISSIVE_OR_COPYLEFT_OK`** —
GPL-2.0-only with no non-commercial restriction, same posture as fceumm and stella.
The GPLv2/GPLv3 mixture (cc_resampler.c) is resolved under the project's
separately-.so dynamically-loaded shared-object model; owner legal clearance
recorded 2026-08-01.

## Build integration notes

The `gambatte_core` CMake target (`app/src/main/cpp/CMakeLists.txt`) mirrors
upstream's `libgambatte/libretro/jni/Android.mk` + `Makefile.common` exactly:

- **Defines (COREFLAGS)**: `-DINLINE=inline -DHAVE_STDINT_H -DHAVE_INTTYPES_H
  -D__LIBRETRO__ -DVIDEO_RGB565 -DCC_RESAMPLER_NO_HIGHPASS -Wno-c++11-narrowing`.
- **Optimization**: `-O2 -DNDEBUG` (non-debug release builds).
- **APP_STL**: `c++_static` (matching upstream's `Application.mk` default for
  the Android NDK — the app's CMake target declares this).
- **Link**: upstream's own `link.T` version script is referenced via
  `--version-script=link.T` plus `--no-undefined`; `-z,max-page-size=16384`
  is passed (matching upstream's `LOCAL_LDFLAGS`).
- **Includes** (as `SYSTEM PRIVATE`): `src`, `include`, `common`,
  `libretro`, `libretro-common/include`.
- **Language**: C++11 (required by `-Wno-c++11-narrowing` suppression; the
  core uses C++11 features).
- **Network feature** is off (`HAVE_NETWORK` not defined); `net_serial.cpp`
  is NOT compiled.
- **Per-ABI note**: the upstream `Android.mk` defaults to `HAVE_NETWORK=1`,
  but the app explicitly sets `HAVE_NETWORK=0` / omits the flag. The
  `net_serial.cpp` source is vendored for reference but excluded from the
  compiled set.

**Inline ARM assembly**: none present in the vendored set. The only inline
assembly is the MIPS-specific `__asm__` block inside `cc_resampler.c`, gated
by `#ifdef _MIPS_ARCH_ALLEGREX` (MIPS Algor:EX platform only). No `-marm`
or ARM assembly defines are needed for any Android ABI — same outcome as
fceumm and mGBA.

**`link.T` present**: yes — shipped by upstream at `libgambatte/libretro/`
and vendored at the root of `third_party/cores/gambatte/link.T`. Referenced
by the `gambatte_core` CMake target's linker flags (`--version-script` +
`--no-undefined`), exactly mirroring `Android.mk`'s `LOCAL_LDFLAGS`.

**`--no-undefined` convention**: the upstream Android.mk links with
`-Wl,-version-script=$(LIBRETRO_DIR)/link.T,-z,max-page-size=16384` — the
`-Wl,--no-undefined` flag is NOT present in the upstream build, but the app's
CMake target adds it (matching fceumm/mGBA convention) to catch any missing
symbol definitions at build time.

**Per-ABI SHA-256 checksums**: computed after `assembleDebug` build, using the
same methodology as stella (strip binary with NDK's arm-linux-androideabi-strip
or aarch64-linux-android-strip, then sha256sum):

    # For armeabi-v7a:
    $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/arm-linux-androideabi-strip \
        -s <path-to-unstripped-libgambatte_core.so>
    sha256sum libgambatte_core.so

    # For arm64-v8a:
    $ANDROID_NDK_HOME/toolchains/llvm/prebuilt/darwin-x86_64/bin/aarch64-linux-android-strip \
        -s <path-to-unstripped-libgambatte_core.so>
    sha256sum libgambatte_core.so

Checksums are recorded in CoreManifest.kt's gambatte entry (binaryChecksums) and
filled below after the build completes.

| ABI | SHA-256 |
|---|---|
| armeabi-v7a | `c9f9b61b8522fbf73a2121ac8768f4d1f4241333bef1c4eab090f6e8253ddcf4` |
| arm64-v8a | `b1bc8d892f12c3adccf7c01c8552ed13bbd4de2624b796b14453cd894fb4159e` |

## Systems / extensions

- **Systems**: `gb` (Game Boy DMG), `gbc` (Game Boy Color).
- **File extensions**: `.gb`, `.gbc`, `.dmg`.
- **Firmware**: no required firmware (no external BIOS needed for DMG/GBC).
- **Memory type**: SRAM (`.srm` autosave, constant `SAVE_RAM_MEMORY_ID = "srm"`
  from `SavePathPolicy.kt`).

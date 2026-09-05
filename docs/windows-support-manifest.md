# Windows x86_64 Core Support Manifest

Factual record of the current Windows x86_64 work (`plans/WINDOWS_IMPL.md`). Unlike
`docs/linux-support-manifest.md`, this manifest is **not an enablement record**: `windows-x86_64`
appears in **no** `CoreManifest.kt` `supportedAbis` and in **no**
`packaging/share/rommulus/core-manifest.json` entry — the validator
`packaging/validate-linux-targets.py` fails the build if any approved/production core advertises
`windows-x86_64` — and no player launch path can select a Windows core. Windows is **not a
supported platform** and no Windows release artifacts exist. A core becomes launchable on Windows
only after its full per-core gate (`plans/WINDOWS_IMPL.md` §6.4, 15 criteria, including physical
Windows 10/11 qualification) passes and its enablement is recorded here.

## Core status

Tiers per `plans/WINDOWS_IMPL.md` §6.2. All 16 cores of the Linux manifest appear exactly once;
every Windows build target is a separate `<core>-windows.cmake` fragment — none of the Linux
`*-linux.cmake` fragments is ever included on WIN32.

| Core | Tier | Windows build target | Gate status | Enabled |
| --- | --- | --- | --- | --- |
| `test_core` | 0 | `native/player/CMakeLists.txt` (WIN32 block, `add_library(test_core SHARED …)`, `PREFIX ""` → `test_core.dll`) | IMPLEMENTED — Win32 player foundation + `test_core.dll` complete; local MinGW-w64 UCRT64 PE32+ cross-build and local macOS synthetic-core E2E passed; **awaiting first live `windows-2022` run** (CI jobs 3–5) | no |
| `gambatte` | 1 | `native/cmake/cores/gambatte-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — Windows build, exact 22-export allowlist, and recursive PE32+/import-closure audit wired into CI (job 4); local MinGW UCRT64 cross-build and local macOS real-core E2E passed; **Windows-hosted run and physical Win10/11 qualification pending** | no |
| `fceumm` | 1 | `native/cmake/cores/fceumm-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — Windows build, exact 22-export allowlist, and recursive PE32+/import-closure audit wired into CI (job 4); local MinGW UCRT64 cross-build and local macOS real-core E2E passed; **Windows-hosted run and physical Win10/11 qualification pending** | no |
| `prosystem` | 1 | `native/cmake/cores/prosystem-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — Windows build, exact 22-export allowlist, and recursive PE32+/import-closure audit wired into CI (job 4); local MinGW UCRT64 cross-build and local macOS real-core E2E passed; **Windows-hosted run and physical Win10/11 qualification pending** | no |
| `handy` | 1 | — (no `handy-windows.cmake`) | NOT STARTED | no |
| `mednafen_ngp` | 1 | — (no `mednafen_ngp-windows.cmake`) | NOT STARTED | no |
| `mednafen_wswan` | 1 | — (no `mednafen_wswan-windows.cmake`) | NOT STARTED | no |
| `stella` | 1 | — (no `stella-windows.cmake`) | NOT STARTED | no |
| `beetle_pce_fast` | 1 | — (no `beetle_pce_fast-windows.cmake`) | NOT STARTED | no |
| `mgba` | 1 | — (no `mgba-windows.cmake`) | NOT STARTED | no |
| `snes9x` | 1 | — (no `snes9x-windows.cmake`) | NOT STARTED | no |
| `genesis_plus_gx` | 1 | — (no `genesis_plus_gx-windows.cmake`) | NOT STARTED | no |
| `pcsx_rearmed` | 2 | — (no `pcsx_rearmed-windows.cmake`) | NOT STARTED | no |
| `mupen64plus_next` | 2 | — (no `mupen64plus_next-windows.cmake`) | NOT STARTED | no |
| `dolphin` | 3 | — (no `dolphin-windows.cmake`) | NOT STARTED | no |
| `lrps2` | 3 | — (no `lrps2-windows.cmake`) | NOT STARTED | no |

Counts: Tier 0 = 1, Tier 1 = 11, Tier 2 = 2, Tier 3 = 2 (16 total, matching the 16 rows of
`docs/linux-support-manifest.md`).

## Platform foundation and desktop shell (Phase 1)

Implemented in this working tree; local macOS validation passed (desktop test suite green, with
host-gated Windows integration tests skipped off-Windows). The **first live `windows-2022` run is
pending** — CI jobs 1–2 of `.github/workflows/windows-x64.yml` are the gate:

- Windows Known Folder paths (`desktop/src/main/kotlin/com/romm/desktop/storage/paths/WindowsAppPaths.kt`,
  JNA `SHGetKnownFolderPath` seam in `JnaWindowsKnownFolderResolver.kt`);
- Windows Credential Manager storage (`desktop/src/main/kotlin/com/romm/desktop/storage/secret/windows/`
  — `CREDENTIALW` layout, generic/local-machine persistence, framed UTF-8 token data, exact
  deletion, **no plaintext fallback**);
- current-user/SYSTEM DACL hardening behind an audited JNA seam (fail-closed, formatted Win32
  errors);
- cross-process advisory file lock with two-process rejection/crash-release coverage;
- controller environment policy split (Linux vs Windows; Windows never loads Linux JInput or Steam
  behavior) and a Windows no-op virtual-keyboard launcher;
- logger installation from selected `AppPaths` (no hard-coded `~/.local/state` default).

## Win32 player foundation and `test_core` (Phase 2/3)

Implemented: complete Win32 platform sources under `native/platform/windows/src/` (safe DLL
loading, durable/atomic files, canonical/security-aware paths, session lock, executable/default
roots, console termination + five-second watchdog, peak-memory diagnostics); the WIN32
source/link split in `native/player/CMakeLists.txt`; the toolchain contract
`native/cmake/toolchains/windows-mingw-ucrt-x86_64.cmake`; and the CMake presets
`windows-x86_64` and `windows-x86_64-software-only` in `native/player/CMakePresets.json`.

`test_core` (Tier 0, synthetic, project-owned) is the only core the Windows player build enables;
it exists for host/player protocol and lifecycle validation only, never for game content. Local
evidence: MinGW-w64 UCRT64 cross-build of `rommulus-player.exe` + `test_core.dll` as PE32+
x86_64, and a full local macOS synthetic-core E2E via the host-portable harness
`native/player/tests/e2e/player_e2e.py`. **Awaiting first live `windows-2022` run** (CI jobs 3–5:
engine CTest suite, SDL3 software-only player build + recursive PE/import audit, staged-artifact
E2E).

## gambatte — Windows x86_64 candidate build identity

Provenance fields (coreId `gambatte`; the upstream pin is identical to the approved Android/Linux
pin in `CoreManifest.kt`):

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/gambatte-libretro |
| Exact commit | `96174369b3c30d9fc57c926fa3379c273dc6a9a5` (no upstream release tags; commitSha is the exact pin) |
| Vendored source subset | `third_party/cores/gambatte/{libretro,src,libretro-common}` — the curated 46-source set from `native/cmake/cores/gambatte-sources.cmake` (identical to Android/Linux; network code excluded, `HAVE_NETWORK` undefined) — see `third_party/cores/gambatte/VENDORING.md` |
| Local patches | none |
| Windows fragment | `native/cmake/cores/gambatte-windows.cmake` (WIN32 block of `native/player/CMakeLists.txt` only) |
| Export control | `native/cmake/cores/gambatte-windows.def` — exactly the 22 `retro_*` exports the player's `CoreLibrary` resolves; no `romm_*` save extensions (this core defines none at this pin) |
| Compiler / build | MinGW-w64 UCRT64 (MSYS2) + CMake + Ninja, `-O2 -DNDEBUG`, `-Wl,--no-undefined`; canonical output name `gambatte_core.dll` (`PREFIX ""` — never `libgambatte_core.dll`) |
| Binary hash | **NOT RECORDED** — the SHA-256 of `gambatte_core.dll` is written only into the CI artifact's `import-audit.txt` by the pinned `windows-2022` run (job 4); no binary hash is committed here until that run produces it |
| Supported systems | `gb`, `gbc` |
| Supported extensions | `.gb`, `.gbc` |
| Required firmware | none |
| Renderer | software, RGB565 (runs under the temporary software-only boundary; no ANGLE required) |
| Saves | SRAM via `RETRO_MEMORY_SAVE_RAM` (8192-byte battery SRAM for the generated E2E ROM) |

Candidate posture: CI stages `gambatte_core.dll` separately under `cores-candidate/` (never an
enabled-core path) and audits it (PE32+ machine, recursive import closure, exact 22-symbol export
allowlist, repeated load/`retro_api_version`/`retro_init`/`retro_deinit` smoke) **without
promoting it**. `windows-x86_64` is in no `supportedAbis`, no package manifest entry, and no
launch path.

### Qualification evidence and generated test ROM

- **Local (macOS host) — passed:** MinGW-w64 UCRT64 cross-build of the player + `test_core.dll` +
  `gambatte_core.dll` as PE32+ x86_64 with the exact export allowlist, and a full **real-core
  E2E** of the native macOS player + gambatte core via the host-portable harness
  `native/player/tests/e2e/player_e2e.py` (offscreen video, software render, dummy audio):
  bounded-frame runs, result schema, SRAM save/restore/adoption across relaunch, repeated load,
  force-kill lock recovery.
- **Windows-hosted — pending:** the first live run of `.github/workflows/windows-x64.yml`
  (jobs 4–5) on the pinned `windows-2022` runner has not happened yet; that run is the
  target-runtime gate and will record the candidate DLL's SHA-256 in `import-audit.txt`.
- **Physical Windows 10/11 — pending:** per-core gate `plans/WINDOWS_IMPL.md` §6.4 (15 criteria,
  including item 15, physical qualification) is not complete. Hosted CI must not be treated as
  physical qualification.

**Generated ROM (original content, hash-pinned).** `native/player/tests/e2e/gambatte_rom.py`
deterministically generates a 32 KiB (0x8000) Game Boy cartridge image — title `ROMMULUS E2E GB`,
cartridge type `0x03` (MBC1 + RAM + battery), 8 KiB battery SRAM (8192 bytes as Gambatte exposes
it), valid Nintendo header/global checksums, no Nintendo logo and no third-party bytes.
**SHA-256 of the generated ROM: `7eeb9a0ab9bf958dc98b0d04378529dd4687259a1f644e1dbb7e46973e18707d`**
(pinned by `PINNED_GAMBATTE_ROM_SHA256` in `native/player/tests/e2e/test_player_e2e.py`; the
generator is a pure function of its commit-fixed constants, so any byte drift changes this digest
and fails the gate).

## fceumm — Windows x86_64 candidate build identity

Provenance fields (coreId `fceumm`; the upstream pin is identical to the approved Android/Linux
pin in `CoreManifest.kt`):

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/libretro-fceumm |
| Exact commit | `b5e3566515c27dc66c9c20572171673126532e06` (no upstream release tags; commitSha is the exact pin) |
| Vendored source subset | `third_party/cores/fceumm/src` — the curated 505-source set from `native/cmake/cores/fceumm-sources.cmake` (identical to Android/Linux; network code excluded, FDS excluded) — see `third_party/cores/fceumm/VENDORING.md` |
| Local patches | none |
| Windows fragment | `native/cmake/cores/fceumm-windows.cmake` (WIN32 block of `native/player/CMakeLists.txt` only) |
| Export control | `native/cmake/cores/fceumm-windows.def` — exactly the 22 `retro_*` exports the player's `CoreLibrary` resolves; no `romm_*` save extensions (this core defines none at this pin); the three upstream extras (`retro_cheat_reset`, `retro_cheat_set`, `retro_load_game_special`) stay local to the DLL |
| Compiler / build | MinGW-w64 UCRT64 (MSYS2) + CMake + Ninja, `-O2 -DNDEBUG`, `-Wl,--no-undefined`; canonical output name `fceumm_core.dll` (`PREFIX ""` — never `libfceumm_core.dll`) |
| Binary hash | **NOT RECORDED** — the SHA-256 of `fceumm_core.dll` is written only into the CI artifact's `import-audit.txt` by the pinned `windows-2022` run (job 4); no binary hash is committed here until that run produces it |
| Supported systems | `nes`, `famicom` |
| Supported extensions | `.nes`, `.unf` |
| Required firmware | none (cartridge-only scope; FDS is excluded from the vendored build) |
| Renderer | software, RGBX888 with RGB565 fallback (runs under the temporary software-only boundary; no ANGLE required) |
| Saves | SRAM via `RETRO_MEMORY_SAVE_RAM` (8192-byte battery WRAM for the generated E2E ROM) |

Candidate posture: CI stages `fceumm_core.dll` separately under `cores-candidate/` (never an
enabled-core path) and audits it (PE32+ machine, recursive import closure, exact 22-symbol export
allowlist, repeated load/`retro_api_version`/`retro_init`/`retro_deinit` smoke) **without
promoting it**. `windows-x86_64` is in no `supportedAbis`, no package manifest entry, and no
launch path.

### Qualification evidence and generated test ROM

- **Local (macOS host) — passed:** MinGW-w64 UCRT64 cross-build of the player + `test_core.dll` +
  `gambatte_core.dll` + `fceumm_core.dll` as PE32+ x86_64 under the software-only preset
  (`build/player-windows-x86_64-software-only/`; `fceumm_core.dll` verified locally to export
  exactly the 22-symbol allowlist), and a full **real-core E2E** of the native macOS player +
  fceumm core via the host-portable harness `native/player/tests/e2e/player_e2e.py`
  (`--video-driver cocoa`, software render, dummy audio): bounded-frame runs, result schema, SRAM
  save/restore/adoption across relaunch, repeated load, force-kill lock recovery — all four FCEUmm
  qualification scenarios green (report `build/reports/fceumm-e2e-local/e2e-report.json`,
  `passed: true` on macOS arm64).
- **Windows-hosted — pending:** the first live run of `.github/workflows/windows-x64.yml`
  (jobs 4–5) on the pinned `windows-2022` runner has not happened yet; that run is the
  target-runtime gate and will record the candidate DLL's SHA-256 in `import-audit.txt`.
- **Physical Windows 10/11 — pending:** per-core gate `plans/WINDOWS_IMPL.md` §6.4 (15 criteria,
  including item 15, physical qualification) is not complete. Hosted CI must not be treated as
  physical qualification.

**Generated ROM (original content, hash-pinned).** `native/player/tests/e2e/fceumm_rom.py`
deterministically generates a 40976-byte iNES cartridge image — classic iNES header (mapper 0
NROM: PRG size code 2 = 32 KiB, CHR size code 1 = 8 KiB, battery bit set, no trainer), an original
RomMulus-authored 6502 program at the $8000 entry with a hardware reset vector at $FFFC pointing
at that entry (FCEUmm's `X6502_Power` queues a hardware reset taken from that vector on the first
frame), 8 KiB battery WRAM exposed as `RETRO_MEMORY_SAVE_RAM`, and no third-party bytes.
**SHA-256 of the generated ROM: `d1d4869696dcf53aeb7f207890d6f0cc7ad87fdcbc3054064fd935f042c281ea`**
(pinned by `PINNED_FCEUMM_ROM_SHA256` in `native/player/tests/e2e/test_player_e2e.py`; the
generator is a pure function of its commit-fixed constants, so any byte drift changes this digest
and fails the gate).

## prosystem — Windows x86_64 candidate build identity

Provenance fields (coreId `prosystem`; the upstream pin is the vendored-tree
master HEAD of `libretro/prosystem-libretro`):

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/prosystem-libretro |
| Exact commit | `363b6dfbd3e240762e022c2b4897b4fe55722be3` (no upstream release tags; commitSha is the exact pin) |
| Vendored source subset | `third_party/cores/prosystem/{core,bupboop/coretone,libretro-common}` — the curated 32-source set from `native/cmake/cores/prosystem-sources.cmake` (identical to Android/Linux; network code excluded) — see `third_party/cores/prosystem/VENDORING.md` |
| Local patches | none |
| Windows fragment | `native/cmake/cores/prosystem-windows.cmake` (WIN32 block of `native/player/CMakeLists.txt` only) |
| Export control | `native/cmake/cores/prosystem-windows.def` — exactly the 22 `retro_*` exports the player's `CoreLibrary` resolves; no `romm_*` save extensions (this core defines none at this pin); the three upstream extras (`retro_cheat_reset`, `retro_cheat_set`, `retro_load_game_special`) stay local to the DLL |
| Compiler / build | MinGW-w64 UCRT64 (MSYS2) + CMake + Ninja, `-O2 -DNDEBUG`, `-Wl,--no-undefined`; canonical output name `prosystem_core.dll` (`PREFIX ""` — never `libprosystem_core.dll`) |
| Binary hash | **NOT RECORDED** — the SHA-256 of `prosystem_core.dll` is written only into the CI artifact's `import-audit.txt` by the pinned `windows-2022` run (job 4); no binary hash is committed here until that run produces it |
| Supported systems | `atari7800` |
| Supported extensions | `.a78` |
| Required firmware | none (the optional BIOS is only consulted when present in the system directory; the E2E ROM is BIOS-free) |
| Renderer | software (Maria/TIA emulation; no HW-render request — runs under the temporary software-only boundary) |
| Saves | **none** — this pin exposes no `RETRO_MEMORY_SAVE_RAM` region (`retro_get_memory_size(RETRO_MEMORY_SAVE_RAM)` returns 0; only SYSTEM_RAM). The E2E asserts a rigorous no-persistent-save gate instead of SRAM invariants |

Candidate posture: CI stages `prosystem_core.dll` separately under `cores-candidate/` (never an
enabled-core path) and audits it (PE32+ machine, recursive import closure, exact 22-symbol export
allowlist, repeated load/`retro_api_version`/`retro_init`/`retro_deinit` smoke) **without
promoting it**. `windows-x86_64` is in no `supportedAbis`, no package manifest entry, and no
launch path.

### Qualification evidence and generated test ROM

- **Local (macOS host) — passed:** MinGW-w64 UCRT64 cross-build of `prosystem_core.dll` as PE32+
  x86_64 under the software-only preset (`build/player-windows-x86_64-software-only/`; verified
  locally to export exactly the 22-symbol allowlist and to import only system DLLs), and a full
  **real-core E2E** of the native macOS player + ProSystem core via the host-portable harness
  `native/player/tests/e2e/player_e2e.py` (`--video-driver cocoa`, software render, dummy audio):
  bounded-frame runs, result schema, and the rigorous no-persistent-save gate (checkpointWritten
  false, saveSize/saveHash null, zero `.srm` artifacts — no adoption chain exists) across valid
  launch, repeated load, and force-kill lock recovery — all three ProSystem qualification
  scenarios green alongside every test_core/Gambatte/FCEUmm scenario (report
  `build/reports/prosystem-e2e-local/e2e-report.json`, `passed: true` on macOS arm64).
- **Windows-hosted — pending:** the first live run of `.github/workflows/windows-x64.yml`
  (jobs 4–5) on the pinned `windows-2022` runner has not happened yet; that run is the
  target-runtime gate and will record the candidate DLL's SHA-256 in `import-audit.txt`.
- **Physical Windows 10/11 — pending:** per-core gate `plans/WINDOWS_IMPL.md` §6.4 (15 criteria,
  including item 15, physical qualification) is not complete. Hosted CI must not be treated as
  physical qualification.

**Generated ROM (original content, hash-pinned).** `native/player/tests/e2e/prosystem_rom.py`
deterministically generates a 16384-byte raw `.a78` cartridge image — no "ATARI7800" header and
no CC2 marker (ProSystem's `CARTRIDGE_TYPE_NORMAL` path, whole image mapped to CPU
`$C000-$FFFF`), an original RomMulus-authored 6502 program at the `$C000` entry with the in-cart
reset vector at file offsets `0x3FFC/0x3FFD` pointing at that entry, a 4-color palette +
deterministic pattern fill + Maria display program + 242-header chain (ROM-resident template
copied into RAM at boot) + TIA audio tone, and no third-party bytes. The program is assembled
against the vendored core's actual opcode table (`core/Sally.c`), which deviates from a stock
6502 in exactly the spots the program touches. **SHA-256 of the generated ROM:
`1d6b8f17eb536b015f7f42fa6897aa765cfe4702b0681029bf625c9b868c8afc`** (pinned by
`PINNED_PROSYSTEM_ROM_SHA256` in `native/player/tests/e2e/test_player_e2e.py`; the generator is a
pure function of its commit-fixed constants, so any byte drift changes this digest and fails the
gate).

## Temporary software-only boundary (pre-ANGLE) — not yet qualified

The Windows player currently builds under the `windows-x86_64-software-only` preset
(`ROMM_WIN32_SOFTWARE_ONLY=ON`; the option defaults OFF globally and is meaningful only for
WIN32). This is a **temporary, fail-closed boundary**: the GLES3/ANGLE hardware-context source,
import libraries, and include directory are excluded, a no-op `SdlHardwareContext` is compiled
instead (no unresolved GL API can exist), and the player **fails closed with `launch_failed`**
for every known hardware-rendering core and every Libretro `SET_HW_RENDER` /
`GET_HW_RENDER_INTERFACE` request — it never silently downgrades. SDL3 (video/audio/input)
remains required; software cores such as `test_core`, `gambatte`, `fceumm`, and `prosystem` keep
full functionality.

The pinned ANGLE distribution is **not yet built, staged, or qualified** in Windows CI — the
workflow explicitly does not yet build the player with the pinned ANGLE distribution — so the
Windows hardware-rendering path is unqualified, and the full `windows-x86_64` preset (which
requires the pinned ANGLE EGL/GLES libraries at configure time) is not exercised by CI yet. No
hardware-rendering core may be advertised or enabled on Windows until ANGLE (or a deliberate
successor) passes the per-core gate.

## Build inputs (pinned)

- Runner: `windows-2022` (pinned; never `windows-latest`) — `.github/workflows/windows-x64.yml`.
- Toolchain: MinGW-w64 UCRT64 via MSYS2 (`mingw-w64-ucrt-x86_64-toolchain`), CMake, Ninja
  (`native/cmake/toolchains/windows-mingw-ucrt-x86_64.cmake`); static `libgcc`/`libstdc++`
  contract, dynamically linked `libwinpthread-1.dll` staged explicitly when imported.
- SDL3: pinned `release-3.4.16` source archive, SHA-256
  `7322236cd12090c3eb40b9728be4d49c76f66ad17d04369584d4ecad5cf77c68` (verified before
  extraction; built shared-only into a job-local prefix).
- JDK: Temurin 17 (JVM jobs only).
- Artifacts are **unsigned by design** at this stage; signing lands with the packaging lane
  (`plans/WINDOWS_IMPL.md` §7.3).

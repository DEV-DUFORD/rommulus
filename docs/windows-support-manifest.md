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
| `mednafen_wswan` | 1 | `native/cmake/cores/mednafen_wswan-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — Windows build, exact 22-export allowlist, and recursive PE32+/import-closure audit wired into CI (job 4); local MinGW UCRT64 cross-build and local macOS real-core E2E passed; **Windows-hosted run and physical Win10/11 qualification pending** | no |
| `stella` | 1 | — (no `stella-windows.cmake`) | NOT STARTED | no |
| `beetle_pce_fast` | 1 | `native/cmake/cores/beetle_pce_fast-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — Windows PE32+ cross-build, exact 22-export allowlist, deterministic original HuCard, and local BRAM lifecycle E2E passed; hosted `windows-2022` and physical Win10/11 qualification pending | no |
| `mgba` | 1 | — (no `mgba-windows.cmake`) | NOT STARTED | no |
| `snes9x` | 1 | — (no `snes9x-windows.cmake`) | NOT STARTED | no |
| `genesis_plus_gx` | 1 | `native/cmake/cores/genesis_plus_gx-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted build, PE/import closure, exact 26-export boundary, 64 KiB SRAM lifecycle E2E, and load smoke passed; physical Win10/11 qualification remains required | no |
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

## mednafen_wswan — Windows x86_64 candidate build identity

Provenance fields (coreId `mednafen_wswan`; the upstream pin is the vendored-tree master HEAD
of `libretro/beetle-wswan-libretro`):

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/beetle-wswan-libretro |
| Exact commit | `4b01295838ea89e3f1355bbe4cb5cf98aa6108cd` (no upstream release tags; commitSha is the exact pin) |
| Vendored source subset | `third_party/cores/mednafen_wswan/{libretro.c,mednafen,wswan sources,libretro-common/compat}` — the curated 16-source set from `native/cmake/cores/mednafen_wswan-sources.cmake` (identical to Android/Linux; network code excluded) |
| Local patches | none |
| Windows fragment | `native/cmake/cores/mednafen_wswan-windows.cmake` (WIN32 block of `native/player/CMakeLists.txt` only) |
| Export control | `native/cmake/cores/mednafen_wswan-windows.def` — exactly the 22 `retro_*` exports the player's `CoreLibrary` resolves; no `romm_*` save extensions (this core defines none at this pin) |
| Compiler / build | MinGW-w64 UCRT64 (MSYS2) + CMake + Ninja, `-O2 -DNDEBUG`, `-Wl,--no-undefined`; canonical output name `mednafen_wswan_core.dll` (`PREFIX ""` — never `libmednafen_wswan_core.dll`) |
| Binary hash | **NOT RECORDED** — the SHA-256 of `mednafen_wswan_core.dll` is written only into the CI artifact's `import-audit.txt` by the pinned `windows-2022` run (job 4); no binary hash is committed here until that run produces it |
| Supported systems | `wonderSwan`, `wonderSwanColor` |
| Supported extensions | `.ws`, `.wsc` |
| Required firmware | none (the E2E ROM is BIOS-free — the NEC V30's reset fetch at physical `0xFFFF0` lands in cart ROM) |
| Renderer | software (no HW-render request — runs under the temporary software-only boundary) |
| Saves | 8192-byte battery SRAM (`RETRO_MEMORY_SAVE_RAM`; cart header code `0x01`). The E2E drives a deterministic SRAM counter oracle against the player-reported per-run frame counts |

Candidate posture: CI stages `mednafen_wswan_core.dll` separately under `cores-candidate/`
(never an enabled-core path) and audits it (PE32+ machine, recursive import closure, exact
22-symbol export allowlist, repeated load/`retro_api_version`/`retro_init`/`retro_deinit`
smoke) **without promoting it**. `windows-x86_64` is in no `supportedAbis`, no package manifest
entry, and no launch path.

### Qualification evidence and generated test ROM

- **Local (macOS host) — passed:** MinGW-w64 UCRT64 cross-build of `mednafen_wswan_core.dll` as
  PE32+ x86_64 under the software-only preset (`build/player-windows-x86_64-software-only/`;
  verified locally to export exactly the 22-symbol allowlist and to import only system DLLs), and
  a direct dlopen oracle check of the real vendored core (the macOS dylib build) against the
  generated ROM: fresh loads of 1/2/10/60 frames and restore chains of 1+2, 2+10, and 10+60
  frames all match the exact counter oracle — proving both the deterministic per-frame iteration
  count and SRAM persistence across power-ons (the restored counter keeps counting).
- **Windows-hosted — pending:** the first live run of `.github/workflows/windows-x64.yml`
  (jobs 4–5) on the pinned `windows-2022` runner has not happened yet; that run is the
  target-runtime gate and will record the candidate DLL's SHA-256 in `import-audit.txt`.
- **Physical Windows 10/11 — pending:** per-core gate `plans/WINDOWS_IMPL.md` §6.4 (15 criteria,
  including item 15, physical qualification) is not complete. Hosted CI must not be treated as
  physical qualification.

**Generated ROM (original content, hash-pinned).** `native/player/tests/e2e/wswan_rom.py`
deterministically generates a 524288-byte raw `.ws/.wsc` cartridge image — no WonderWitch
"ELISA" firmware signature at the bank-F base, no Detective Conan header pattern, no third-party
bytes. The program is entirely RomMulus-authored NEC V30 bytecode crafted against the vendored
core's actual semantics: a 5-byte reset stub at physical `0xFFFF0` (far jump to the main code),
a one-shot setup that loads `DS = 0x1000` (segment base `DS<<4 = 0x10000`, the bank-1 SRAM
window) via `push ax`/`pop ds`, and an 11-byte / 12-cycle loop that increments a byte counter at
`SRAM[0x100]` and mirrors it into `SRAM[0]`. The cart header's last-10-byte SRAM code selects the
8 KiB battery region. Because the counter lives IN SRAM, restored saves keep counting across
power-ons. Under the core's exact chunked ICount semantics (three `v30mz_execute(128/96/32)`
calls per scanline with a continuous instruction stream), one power-on of F presented frames
executes exactly `3392*F - 299` increments and every run ends mid-iteration after its final
unmirrored increment — so the oracle is `SRAM[0x100] = total mod 256`, `SRAM[0] = (total-1) mod
256`, all other bytes zero. **SHA-256 of the generated ROM:
`6a0857a6f787ac650e3b3be4191a2db59fc6c06ff7ad353188149945a8074d38`** (pinned by
`PINNED_WSWAN_ROM_SHA256` in `native/player/tests/e2e/test_player_e2e.py`; the generator is a
pure function of its commit-fixed constants, so any byte drift changes this digest and fails the
gate).

## beetle_pce_fast — Windows x86_64 candidate build identity

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/beetle-pce-fast-libretro |
| Exact commit | `b211204c7026dff6e86e79b00185512e2421fff8` |
| Vendored source subset | `third_party/cores/beetle_pce_fast/{libretro.c,mednafen,libretro-common}` — the guarded 60-source cartridge build in `native/cmake/cores/beetle_pce_fast-sources.cmake`, shared unchanged by Android, Linux, and Windows |
| Local patches | none |
| Windows fragment | `native/cmake/cores/beetle_pce_fast-windows.cmake` |
| Export control | `native/cmake/cores/beetle_pce_fast-windows.def` — exactly the 22 `retro_*` symbols required by `CoreLibrary` |
| Compiler / build | MinGW-w64 UCRT64 + CMake + Ninja, `-O2 -DNDEBUG`, `-Wl,--no-undefined`; canonical `beetle_pce_fast_core.dll` |
| Hosted Windows DLL SHA-256 | `72c2a79fbf7df452e3cffa76a2807e9e17164f7fb6dd42af118d350744d6ad75` |
| Supported systems / extensions | PC Engine / TurboGrafx-16; `.pce` |
| Required firmware | none for HuCard content; CD content remains outside this candidate scope |
| Renderer | software, RGB565 |
| Saves | 2048-byte BRAM via `RETRO_MEMORY_SAVE_RAM` |

Candidate posture: the DLL is staged only under `cores-candidate/`. It is included in recursive
PE32+/import-closure auditing, exact export auditing, provenance hashing, 50-cycle native load
smoke, and the player E2E gate, but remains absent from every `windows-x86_64` supported ABI.

**Windows-hosted qualification — passed.** Run
[33972683164](https://github.com/DEV-DUFORD/rommulus/actions/runs/33972683164) passed all five
`windows-2022` jobs and all 26 player E2E scenarios. The four Beetle PCE Fast scenarios passed:
valid launch/checkpoint, relaunch persistence, repeated load, and force-kill lock recovery. The
hosted artifact passed its SHA-256 manifest check, recursive PE/import audit, exact 22-export
audit, and 50-cycle native load/init/deinit smoke. This is hosted evidence only; physical Windows
10/11, controller, interactive audio/video, sleep/resume, and soak qualification remain pending.

**Generated ROM (original content, hash-pinned).**
`native/player/tests/e2e/pce_rom.py` generates an 8192-byte raw HuCard containing only
RomMulus-authored HuC6280 bytecode and deterministic fill. It boots from the physical bank-zero
reset vector without firmware, maps physical bank `0xF7` into CPU page 2, preserves the core's
required `HUBM\x00\x88\x10\x80` BRAM prefix, generates software video and PSG audio, and counts
VBlank events into BRAM offsets 8–10. The lifecycle gate covers checkpoint creation, adoption and
restore into fresh processes, repeated load, and force-kill lock recovery.
**SHA-256: `db6dce97515cb1730e927358dcbffb55acbadaecc9e320efdc07499d262b342f`.**

## Temporary software-only boundary (pre-ANGLE) — not yet qualified

The Windows player currently builds under the `windows-x86_64-software-only` preset
(`ROMM_WIN32_SOFTWARE_ONLY=ON`; the option defaults OFF globally and is meaningful only for
WIN32). This is a **temporary, fail-closed boundary**: the GLES3/ANGLE hardware-context source,
import libraries, and include directory are excluded, a no-op `SdlHardwareContext` is compiled
instead (no unresolved GL API can exist), and the player **fails closed with `launch_failed`**
for every known hardware-rendering core and every Libretro `SET_HW_RENDER` /
`GET_HW_RENDER_INTERFACE` request — it never silently downgrades. SDL3 (video/audio/input)
remains required; software cores such as `test_core`, `gambatte`, `fceumm`, `prosystem`,
`mednafen_wswan`, and `beetle_pce_fast` keep full functionality.

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

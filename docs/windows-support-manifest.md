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
| `test_core` | 0 | `native/player/CMakeLists.txt` (WIN32 block, `add_library(test_core SHARED …)`, `PREFIX ""` → `test_core.dll`) | IMPLEMENTED — Win32 player foundation, PE/import audit, load smoke, and hosted synthetic lifecycle E2E passed | no |
| `gambatte` | 1 | `native/cmake/cores/gambatte-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted PE/import/export/load and SRAM lifecycle gates passed; physical Win10/11 qualification pending | no |
| `fceumm` | 1 | `native/cmake/cores/fceumm-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted PE/import/export/load and SRAM lifecycle gates passed; physical Win10/11 qualification pending | no |
| `prosystem` | 1 | `native/cmake/cores/prosystem-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted PE/import/export/load and no-save lifecycle gates passed; physical Win10/11 qualification pending | no |
| `handy` | 1 | — (no `handy-windows.cmake`) | NOT STARTED | no |
| `mednafen_ngp` | 1 | — (no `mednafen_ngp-windows.cmake`) | NOT STARTED | no |
| `mednafen_wswan` | 1 | `native/cmake/cores/mednafen_wswan-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted PE/import/export/load and SRAM lifecycle gates passed; physical Win10/11 qualification pending | no |
| `stella` | 1 | `native/cmake/cores/stella-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted build, PE/import closure, exact 22-export boundary, no-persistent-save lifecycle E2E, and load smoke passed; physical Win10/11 qualification remains required | no |
| `beetle_pce_fast` | 1 | `native/cmake/cores/beetle_pce_fast-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted PE/import/export/load and BRAM lifecycle gates passed; physical Win10/11 qualification pending | no |
| `mgba` | 1 | `native/cmake/cores/mgba-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted build, PE/import closure, exact 25-export boundary, 32 KiB SRAM lifecycle E2E, and load smoke passed; physical Win10/11 qualification remains required | no |
| `snes9x` | 1 | `native/cmake/cores/snes9x-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted build, PE/import closure, exact 22-export boundary, 2 KiB SRAM lifecycle E2E, and load smoke passed; physical Win10/11 qualification remains required | no |
| `genesis_plus_gx` | 1 | `native/cmake/cores/genesis_plus_gx-windows.cmake` (included by `native/player/CMakeLists.txt`, WIN32 block only) | CANDIDATE — hosted build, PE/import closure, exact 26-export boundary, 64 KiB SRAM lifecycle E2E, and load smoke passed; physical Win10/11 qualification remains required | no |
| `pcsx_rearmed` | 2 | — (no `pcsx_rearmed-windows.cmake`) | NOT STARTED | no |
| `mupen64plus_next` | 2 | — (no `mupen64plus_next-windows.cmake`) | NOT STARTED | no |
| `dolphin` | 3 | — (no `dolphin-windows.cmake`) | NOT STARTED | no |
| `lrps2` | 3 | — (no `lrps2-windows.cmake`) | NOT STARTED | no |

Counts: Tier 0 = 1, Tier 1 = 11, Tier 2 = 2, Tier 3 = 2 (16 total, matching the 16 rows of
`docs/linux-support-manifest.md`).

## snes9x — Windows x86_64 candidate build identity

Candidate posture: the canonical `snes9x_core.dll` is staged only under
`cores-candidate/`; it is not advertised by any production `CoreManifest`.

- Upstream: `snes9xgit/snes9x` 1.63
  (`921f9f7b83660eb44ad263022a57a4a029057c37`).
- Shared sources: `native/cmake/cores/snes9x-sources.cmake` validates the
  54-source inventory reused by Android, Linux, and the candidate target.
- ABI and saves: `native/cmake/cores/snes9x-windows.def` permits exactly 22
  `retro_*` exports. The original 32 KiB LoROM fixture uses standard 2 KiB
  battery SRAM through `RETRO_MEMORY_SAVE_RAM`.
- Hosted evidence: workflow
  [33989376162](https://github.com/DEV-DUFORD/rommulus/actions/runs/33989376162)
  passed all five `windows-2022` jobs, including recursive PE/import closure,
  export/provenance audits, 50-cycle load/init/deinit smoke, and the complete
  save adoption/restore, repeated-load, and force-kill recovery E2E path.
  Hosted `snes9x_core.dll` SHA-256:
  `54aeef176ac87c61d452af95be1f144bbe9c287556cc571895361fa8fbb497ac`.

Snes9x remains candidate-only. Physical Windows 10/11, controller,
interactive audio/video, sleep/resume, and soak qualification remain required
before any `CoreManifest.supportedAbis` enablement.

## stella — Windows x86_64 candidate build identity

Candidate posture: the canonical `stella_core.dll` is staged only under
`cores-candidate/`; it is not advertised by any production `CoreManifest`.

- Upstream: `stella-emu/stella` 7.0
  (`d55b1aec0d067a4c901a6dcdf81cb8f579685659`).
- Shared sources: `native/cmake/cores/stella-sources.cmake` validates the
  148-source inventory reused by Android, Linux, and the candidate target.
- ABI and saves: `native/cmake/cores/stella-windows.def` permits exactly 22
  `retro_*` exports. Stella exposes only `RETRO_MEMORY_SYSTEM_RAM`; its
  original BIOS-free Atari 2600 fixture therefore verifies the strict
  no-checkpoint/no-`.srm` lifecycle gate.
- Hosted evidence: workflow
  [33992192421](https://github.com/DEV-DUFORD/rommulus/actions/runs/33992192421)
  passed all five `windows-2022` jobs, including recursive PE/import closure,
  export/provenance audits, 50-cycle load/init/deinit smoke, and valid launch,
  repeated-load, and force-kill recovery E2E coverage.
  Hosted `stella_core.dll` SHA-256:
  `300c89d2ca6a74c5d1cfa061b345300663c4c9483b199dc28935d84ebdd628a2`.

Stella remains candidate-only. Physical Windows 10/11, controller,
interactive audio/video, sleep/resume, and soak qualification remain required
before any `CoreManifest.supportedAbis` enablement.

## Platform foundation and desktop shell (Phase 1)

Implemented and exercised on pinned `windows-2022`; final workflow
[33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
passed the shared and desktop jobs, including host-gated Windows integration tests:

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
`native/player/tests/e2e/player_e2e.py`. Workflow
[33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
passed the engine CTest suite, SDL3 software-only player/candidate build,
recursive PE/import audits, load smoke, and staged-artifact E2E.

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
| Binary hash | `f9810c07fbc1862ce3c12dc813dd095ead49f0042dd0b2d8f7c71f88276f9a87` (hosted run `33999863049`) |
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
- **Windows-hosted — passed:** final workflow
  [33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
  passed PE/import/export audits, 50-cycle load smoke, and all Gambatte
  lifecycle scenarios.
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
| Binary hash | `1853cf6ac3a42a5be591634df4c5f5d63b9fb55d5270268fc7bea1533008d63b` (hosted run `33999863049`) |
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
- **Windows-hosted — passed:** final workflow
  [33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
  passed PE/import/export audits, 50-cycle load smoke, and all FCEUmm
  lifecycle scenarios.
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
| Binary hash | `185af5850b4e3ce4f4241b7389ead796ffa62113aec4ad22cff654e988a180cc` (hosted run `33999863049`) |
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
- **Windows-hosted — passed:** final workflow
  [33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
  passed PE/import/export audits, 50-cycle load smoke, and all ProSystem
  no-save lifecycle scenarios.
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
| Binary hash | `19712e75c3290368adf75324f011413e72ad17fda683c4a9323c1aa219a8c7b5` (hosted run `33999863049`) |
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
- **Windows-hosted — passed:** final workflow
  [33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049)
  passed PE/import/export audits, 50-cycle load smoke, and all WonderSwan
  lifecycle scenarios.
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

## Windows ANGLE/GLES3 graphics frontend spike

The production candidate bundle continues to use the
`windows-x86_64-software-only` preset
(`ROMM_WIN32_SOFTWARE_ONLY=ON`; the option defaults OFF globally and is meaningful only for
WIN32). This is a **temporary, fail-closed boundary**: the GLES3/ANGLE hardware-context source,
import libraries, and include directory are excluded, a no-op `SdlHardwareContext` is compiled
instead (no unresolved GL API can exist), and the player **fails closed with `launch_failed`**
for every known hardware-rendering core and every Libretro `SET_HW_RENDER` /
`GET_HW_RENDER_INTERFACE` request — it never silently downgrades. SDL3 (video/audio/input)
remains required; software cores such as `test_core`, `gambatte`, `fceumm`, `prosystem`,
`mednafen_wswan`, and `beetle_pce_fast` keep full functionality.

The frontend-only ANGLE spike passed hosted Windows CI in workflow
[33999863049](https://github.com/DEV-DUFORD/rommulus/actions/runs/33999863049).
That run built the full `windows-x86_64` player, staged SDL3 plus ANGLE under a
sanitized loader `PATH`, passed recursive PE32+/import-closure and SHA-256
audits, and ran a hidden-window probe through SDL's EGL path. The probe
created an OpenGL ES 3.0 context, compiled GLSL ES 3.00 shaders, validated an
FBO, drew and read back a deterministic pixel, and blitted to the default
framebuffer.

Hosted runtime identity:

- ANGLE `2.1.1`, git revision
  `a96fca8d5ee2ca61e8de419e38cd577579281c9e`;
- `OpenGL ES 3.0` / `OpenGL ES GLSL ES 3.00`;
- vendor `Google Inc. (Microsoft)`;
- renderer `ANGLE (Microsoft, Microsoft Basic Render Driver (0x0000008C)
  Direct3D11 vs_5_0 ps_5_0, D3D11-10.0.20348.5386)`.

Hosted artifact SHA-256 values:

| File | SHA-256 |
| --- | --- |
| `SDL3.dll` | `9ee5ad00e3e80a4bb2b701540888bf2d3a531223544b04a8a67eca389d84b8c8` |
| `libEGL.dll` | `93bad311fa0747c910f832c0a93960d8735d9b8c7f90cc437d584a46f2d35017` |
| `libGLESv2.dll` | `0b172ed570ebf6d82a57807b44619dc4102ce0373449bbab1645cc8ffad873f0` |
| `libwinpthread-1.dll` | `92e996ab2cb61f5106b8b67a4dc23dd2959958cdb3afe6d2f9c6fc3afef85258` |
| `rommulus-player.exe` | `411fcfe39f2ced221b5a35bd2c769717664029a9663181b792471179b00acbb4` |
| `angle_gles3_smoke.exe` | `9fb92a1c2e5ab29cfa12c9023b7d629ce494fcee00b344d228502dc73d9ef3cb` |

The spike resolves the frontend direction, not any hardware core's support
gate:

- Dolphin's pinned Libretro renderer has GLES 3.0/3.1/3.2 paths, so the
  Windows player retains the GLES3 contract for a later Dolphin candidate
  qualification.
- Mupen64Plus-Next's current GLideN64 integration needs an explicit GLES
  build. Its existing desktop-OpenGL/WGL build cannot be relabeled as GLES;
  use a separately configured GLideN64 GLES candidate or a deliberate
  desktop-OpenGL player variant.
- The pinned lrps2 integration negotiates GLES3 but leaves its context
  version unset, selects the desktop GLAD loader, and requires desktop
  `GL_ARB_shading_language_420pack`. It therefore cannot use this frontend
  reliably without core-side profile/loader work; a separate desktop
  OpenGL path or deliberate D3D/Vulkan frontend extension remains necessary.

The hosted runner covers only Microsoft's Basic **Render** Driver through
ANGLE's D3D11 backend. Intel, AMD, NVIDIA, hybrid-GPU, physical Microsoft
Basic Display Driver, Remote Desktop, physical Windows 10/11, interactive
graphics/audio, sleep/resume, and soak evidence remain pending. No Tier 2/3
core was built or advertised, and no production manifest may add
`windows-x86_64` from this frontend-only result.

## Build inputs (pinned)

- Runner: `windows-2022` (pinned; never `windows-latest`) — `.github/workflows/windows-x64.yml`.
- Toolchain: MinGW-w64 UCRT64 via MSYS2 (`mingw-w64-ucrt-x86_64-toolchain`), CMake, Ninja
  (`native/cmake/toolchains/windows-mingw-ucrt-x86_64.cmake`); static `libgcc`/`libstdc++`
  contract, dynamically linked `libwinpthread-1.dll` staged explicitly when imported.
- SDL3: pinned `release-3.4.16` source archive, SHA-256
  `7322236cd12090c3eb40b9728be4d49c76f66ad17d04369584d4ecad5cf77c68` (verified before
  extraction; built shared-only into a job-local prefix).
- ANGLE: `XCSoar/angle-libs` release `a96fca8`, built from ANGLE revision
  `a96fca8d5ee2ca61e8de419e38cd577579281c9e`; Windows x64 archive SHA-256
  `82723e19795d683e6af2afadf39fb00d248d6a5a2cb2af9faeebc017a7f4f5d8`.
  CI derives MinGW import libraries from the verified DLL export tables;
  the package's `.lib` files are MSVC-format and are not used by UCRT64.
- JDK: Temurin 17 (JVM jobs only).
- Artifacts are **unsigned by design** at this stage; signing lands with the packaging lane
  (`plans/WINDOWS_IMPL.md` §7.3).

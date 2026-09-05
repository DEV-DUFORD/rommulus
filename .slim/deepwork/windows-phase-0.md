# Windows implementation — Phase 0

## Goal

Begin `plans/WINDOWS_IMPL.md` with its Phase 0 architecture guardrails while preserving Linux and Android behavior. Development may continue from Apple Silicon; native Windows compilation and packaging will use pinned `windows-2022` GitHub runners. There is no current requirement to move development to a Windows machine.

## Confirmed repository context

- Desktop startup in `desktop/src/main/kotlin/com/romm/desktop/Main.kt` directly constructs Linux paths and credential backends.
- The shared `AppPaths` contract already exists; `XdgAppPaths` is its Linux implementation.
- Linux ABI, core-library naming, installed-core scanning, and player executable lookup are coupled in `PlayerProcessLauncher.kt`.
- `CoreManifest.kt` has 18 entries: 16 approved/package entries and 2 unapproved. No core advertises `windows-x86_64`.
- The Linux package manifest, support table, and native player currently represent 16 packaged targets: 15 production cores plus `test_core`.
- Confirmed inventory drift includes stale counts and an mGBA `sourceOfferSatisfied` mismatch. A reported missing Genesis Plus GX `buildCommand` was rechecked and is not real.
- `desktop/build.gradle.kts` uses JDK 17 and host-specific Compose dependencies. Existing workflows pin reusable actions and use Temurin 17.
- The native player CMake currently links Linux-only libraries and includes Linux core fragments unconditionally, so Phase 0 Windows CI must initially be JVM-only. Native Win64 CI begins after the Phase 2 platform split.
- Strictly rejecting macOS at process startup would break the present development workflow. Platform detection must be injectable and explicit without preventing ordinary host-neutral tests and configuration on macOS.

## Phase 0 implementation plan

### Lane A — platform and artifact architecture

1. Introduce shared typed platform/build identity including `linux-x86_64` and `windows-x86_64`, referenced by domain manifests and desktop launch code.
2. Add injectable normalized OS/architecture detection with clear unsupported-host diagnostics. Preserve an explicit macOS development mode rather than treating Apple Silicon as a production Linux package target.
3. Extract native artifact layout from `PlayerProcessLauncher`: ABI identity, player executable name, canonical core names/aliases, and installed-core scanning.
4. Preserve Linux defaults and behavior; add Windows naming (`rommulus-player.exe`, `<core-id>_core.dll`) without enabling production cores.
5. Add focused tests for Linux, Windows, unsupported hosts, and naming/scanning.

### Lane B — inventory reconciliation

1. Correct stale package/support-document counts.
2. Reconcile package-manifest metadata with `CoreManifest.kt` where current evidence is unambiguous: mGBA's package `sourceOfferSatisfied` must be true.
3. Add validation that approved/package/table/native target inventories agree and that no production core advertises Windows support.

### Lane C — initial Windows CI

1. Add `.github/workflows/windows-x64.yml` using pinned `windows-2022` and Temurin JDK 17.
2. Run shared JVM tests and desktop build/tests; do not add a headless Compose window smoke or native/package task yet.
3. Do not pretend the native player is Windows-buildable until the Phase 2 CMake/source split lands.

## Quality gates

- Existing Linux artifact behavior and tests remain unchanged.
- Shared/domain and desktop tests pass locally on macOS where host-neutral.
- Workflow syntax and Gradle commands are validated.
- No production core gains Windows support.
- Independent architectural review precedes implementation; an independent audit follows implementation.

## Status

- Foundational plan read: complete.
- Repository investigation: complete.
- Architecture review: revisions accepted and incorporated.
- Platform/build identities and native artifact layouts: complete.
- Production startup guard: complete. Linux proceeds unchanged, macOS proceeds explicitly in development-only mode, and Windows fails safely until Phase 1 paths/credentials exist.
- Inventory reconciliation and automated validation: complete; 16 approved Linux package targets agree and no production core advertises Windows.
- Initial pinned `windows-2022` JVM CI: complete; native/package jobs remain intentionally deferred.
- Local validation: shared/domain and desktop tests, inventory validator/tests, and workflow syntax passed.
- Final architecture audit: accepted.

## Next phase

Phase 1 should implement Windows paths, Credential Manager, logger/file-security injection, controller and virtual-keyboard policies, then replace the current deliberate Windows startup refusal with fully injected Windows adapters. The first GitHub run of `windows-x64.yml` is also the host-native confirmation gate for the current JVM-only work.

## Phase 1 execution plan

### Confirmed current constraints

- `Main.kt` still constructs `XdgAppPaths`, the Linux D-Bus/file-secret chain, and the file lock directly. Windows therefore continues to fail before insecure Linux adapters can be used.
- `AppPaths` is already shared and suitable for a Windows implementation. Windows Known Folder access and Credential Manager require narrow, fakeable Win32 seams; their pure policy logic can be tested on macOS and their real bindings on `windows-2022`.
- POSIX permission calls are scattered across paths, logging, locking, journals, player logs, SQLite, BIOS, and file-secret storage. These must be consolidated without weakening Linux behavior.
- `DesktopLogger` supports an explicit directory but still has a hard-coded XDG default/eager singleton path that must be initialized from selected `AppPaths`.
- `FileChannel.tryLock` is the preferred initial Windows per-user lock. Cross-process NTFS and crash-release behavior must be proven on a Windows runner before adding a named mutex fallback.
- JInput's portable polling/mapping is reusable. Linux plugin reflection and `/dev/input` diagnostics must move behind a Linux policy; Windows uses the default environment and bounded re-enumeration without loading Linux classes.
- Steam virtual keyboard must become an injected Linux implementation with a Windows no-op.
- No Windows core is enabled, so the Phase 1 shell can browse/configure/download while emulation remains unavailable until the player/core phases.

### Implementation lanes and dependencies

1. **Platform bundle and paths/security foundation**
   - Extend the desktop platform startup decision into one selected adapter bundle.
   - Add `WindowsAppPaths` using an injectable known-folder resolver whose production implementation calls Windows Known Folder APIs and whose test seam supplies `%APPDATA%`/`%LOCALAPPDATA%`; use Unicode `Path` values, `%APPDATA%\\RomMulus` for config, and `%LOCALAPPDATA%\\RomMulus/{data,cache,state}` for other data.
   - Add Linux and Windows `FileSecurityPolicy` implementations and route existing permission hardening through the policy. Windows sensitive-path policy must verify containment in the selected user roots and fail explicitly when it cannot establish required safety; real ACL application sits behind a fakeable Win32 seam.
   - Add an explicit logger installation/reconfiguration API that replaces handlers safely, then install `DesktopLogger` from `AppPaths.logsDir()` before ordinary logging; preserve Linux behavior.
   - Make existing desktop tests Windows-safe before using them as a CI gate: route production permission operations through policies and condition POSIX-specific assertions on filesystem support rather than executing them unconditionally on NTFS.

2. **Credential Manager**
   - Add a narrow JNA Platform-backed Credential Manager seam and backend with lazy Win32 loading.
   - Use a deterministic generic-credential target scoped by RomMulus, a canonicalized server origin, and the existing user identity. Define and test normalization and safe encoding rather than relying on ambiguous separators; do not add a device identifier unless the authentication model actually provides one.
   - Map not-found, unavailable, malformed, and denied outcomes without logging secret bytes; replace on write and delete exact credentials.
   - Windows receives Credential Manager only—never `FileSecretBackend` fallback. Linux/macOS development retain current behavior.
   - Keep Linux D-Bus classes out of Windows runtime execution; selection tests must prove they are never selected or constructed on Windows. Defer strict classpath/package exclusion to the packaging phase if Gradle host-specific resolution cannot safely express it yet.

3. **Instance lock and controller/keyboard policies**
   - Reuse one injected per-user state-directory `FileLockAppInstanceLock`, remove duplicate construction, and add a two-process Windows integration test for rejection and crash release.
   - Extract controller environment policy: Linux retains plugin refresh and diagnostics; Windows uses default JInput environment, no Linux classes/probes, and bounded re-enumeration on poll failure.
   - Add injected Linux Steam and Windows no-op virtual-keyboard launchers.

4. **Startup integration and Windows CI**
   - After secure paths and credentials exist, change Windows startup from deliberate fail-fast to a complete Windows adapter bundle using `WindowsNativeArtifactLayout`.
   - Add macOS-runnable selection/backend tests and real `windows-2022` tests for Credential Manager, paths, lock behavior, and dependency isolation.
   - Use `:desktop:assemble :desktop:test` in Windows CI instead of `:desktop:test :desktop:build` so tests are not redundantly invoked through `build`.
   - Preserve the no-production-Windows-core guard and do not add native player/package work prematurely.

### Phase 1 quality gate

- Linux/macOS development tests and behavior remain intact.
- Windows startup cannot select an XDG path, file-token fallback, Linux JInput plugin, or Steam keyboard launcher.
- Real Credential Manager round-trip and two-process file locking pass on `windows-2022`.
- Logs resolve below selected state paths and sensitive-path failures are explicit.
- The desktop shell builds on Windows with zero production core enablement.
- Physical controller and interactive shell qualification remain recorded follow-ups rather than falsely claimed CI coverage.

### Execution correction

- Concurrent Phase 1 workers shared one working tree, creating avoidable overwrite/test-loop risk. The combined tree was stabilized and audited before further edits: it compiles and all 848 desktop tests pass on macOS, with only expected OS-gated skips.
- The controller/virtual-keyboard worker did not actually deliver the planned policy abstractions. Its output is treated as research only; controller implementation will be redone by one bounded implementation owner.
- Future implementation lanes that touch related composition code run sequentially in the shared tree. `@myrmidon-4` is restricted to research/investigation and will not be assigned implementation work.

### Phase 1 result

- Added a single host-adapter composition path for Linux, macOS development, and Windows x86_64.
- Added Windows Known Folder paths and current-user/SYSTEM DACL hardening through audited JNA seams. The ACL implementation uses `WRITE_DAC`, extracts the PACL from the SDDL-generated security descriptor, and fails closed with formatted Win32 errors.
- Added explicit logger installation from selected `AppPaths`, including install-before-first-get regression coverage.
- Added Windows Credential Manager storage with the authoritative `CREDENTIALW` layout, generic/local-machine persistence, framed UTF-8 token data, exact deletion, aggregate enumeration freeing, lazy native loading, and no plaintext fallback.
- Added injected Linux/default JInput environment policies and Linux/no-op virtual-keyboard launchers; Windows never loads Linux JInput or Steam behavior.
- Added cross-process file-lock coverage and Windows-gated real Known Folder, ACL, and Credential Manager tests.
- Windows startup now selects complete Windows adapters. No production core is enabled, so native launch remains unavailable until later phases.
- Local validation reached 878 desktop tests with zero failures; final architectural/security audit accepted the phase. First live execution of the Windows-gated tests remains assigned to pinned `windows-2022` CI and does not require moving development to a Windows machine.

## Phase 2 execution plan — Win32 player foundation

### Confirmed native boundaries

- The engine dynamic-library contract is already abstract; the standalone player implementation uses POSIX `dlopen`/`dlsym`/`dlclose`.
- `native/engine/src/atomic_file_store.cpp` is the principal platform leak in the otherwise-neutral engine: `fsync`, `fileno`, POSIX metadata, and rename semantics must move behind platform-selected source implementations without changing callers or protocol bytes.
- Player POSIX behavior is concentrated in `validation.cpp` and `main.cpp`: realpath/stat/UID checks, `flock`, `/proc/self/exe`, XDG/home lookup, signals/alarm, and resource diagnostics.
- `native/player/CMakeLists.txt` unconditionally discovers GLESv2, links `m/dl/pthread`, uses GCC assumptions, and includes every Linux production-core fragment. A Windows configuration must initially include only `test_core`.
- The synthetic core does not need SDL3, ANGLE, or NASM by itself, but `rommulus-player.exe` requires SDL3 and the existing GLES contract requires ANGLE or a deliberately temporary software-only build boundary. Production hardware cores remain disabled.

### Dependency-ordered implementation

1. **Portable foundation with Linux regression gates**
   - Split atomic file storage into a stable public contract plus POSIX and Win32 implementations selected by CMake.
   - Extract dynamic-library, executable-path/default-root, session-lock, process-control/watchdog, and path-security responsibilities from `main.cpp`/`validation.cpp` into narrow platform APIs.
   - Keep protocol, request/result models, core behavior, SDL loop, save metadata, and binding sidecars shared.
   - First land the POSIX implementations as behavior-preserving moves and run existing native tests/player build before adding Win32 code.
   - Mirror platform source selection in `native/tests/CMakeLists.txt`; the native test library currently compiles atomic storage directly.

2. **CMake common/platform split**
   - Separate common sources from `UNIX` and `WIN32` sources/libraries/flags.
   - Keep Linux production-core includes in the UNIX block; Windows builds only `test_core.dll` until individual core gates exist.
   - Remove unconditional `GLESv2`, `m`, `dl`, and `pthread` from all targets, including `test_core`; gate compiler warnings and platform libraries per target and use deterministic output names.
   - Keep the Steam Deck legacy player target and hardware-context source UNIX-only.
   - Add a Windows x86_64 preset/toolchain contract suitable for MSYS2 UCRT64 + Ninja.

3. **Win32 platform implementations**
   - Dynamic library: wide canonical paths, safe DLL directories, `LoadLibraryExW`, immediate Win32 error capture, exact exports, and `FreeLibrary`.
   - Durable files: same-directory temporary file, `WriteFile`, `FlushFileBuffers`, close, atomic replace/move with write-through, explicit cleanup.
   - Trusted paths: handle-based final paths, volume-aware/case-insensitive containment, reparse/ADS/device/reserved-name defenses, and fail-closed metadata checks.
   - Session lock: retained `CreateFileW` handle plus nonblocking `LockFileEx`.
   - Executable/default roots: `GetModuleFileNameW` and LocalAppData fallback, with environment roots retained only as validated launch/test seams.
   - Termination/watchdog: console-control handling plus an independent five-second guard that can write the result without depending on a deadlocked core and then terminate the exact process.

4. **Windows runner and synthetic E2E**
   - Provision pinned `windows-2022` with MSYS2 UCRT64/MinGW-w64, CMake, and Ninja; report and enforce the compiler/runtime family.
   - Build pinned SDL3 and pinned ANGLE for the player, recording hashes and dependency closure. The synthetic core library itself has no SDL/ANGLE dependency, but the current player always creates a GLES context, so every player E2E requires ANGLE unless an explicit temporary software-only context boundary lands first.
   - Build `rommulus-player.exe` and `test_core.dll`, run native unit tests, audit PE x86_64 architecture/exports/dependencies, and upload unsigned debug artifacts.
   - Add request/result/journal E2E, repeated load/unload, normal close, forced-close recovery, Unicode/space paths, and lock-release checks.

### Phase 2 quality gates

- Linux native tests and standalone-player build remain green after every extraction.
- Platform-neutral engine sources contain no Win32/POSIX/SDL/JNI/Android imports.
- A Windows build cannot include Linux core fragments or Linux-only link libraries.
- Win32 path validation and atomic-write tests cover adversarial and abrupt-failure cases before production cores are enabled.
- `test_core.dll` is the only enabled Windows native core and the Compose launch protocol remains byte-compatible.
- All work can continue from Apple Silicon; native verification is assigned to `windows-2022`. No local Windows machine is a hard implementation blocker.

### Phase 2 result

- Extracted all native platform seams and retained behavior-equivalent POSIX implementations with Linux/Android source selection.
- Added complete Win32 implementations for safe DLL loading, durable files, canonical/security-aware paths, session locking, executable/default roots, console termination/watchdog, and peak-memory diagnostics.
- Added audited long-path, Unicode, reparse/ACL, lock, watchdog, and dynamic-loading native tests; the project cross-builds as PE32+ x86_64 with MinGW UCRT64.
- Added a temporary fail-closed Windows software-only player mode for synthetic-core qualification before ANGLE. Hardware-core launches and Libretro hardware-context requests are rejected explicitly.
- Added pinned SDL3 3.4.16 source build, recursive DLL-closure audit and staging, sanitized-PATH smoke, Windows native CTest, and synthetic `test_core.dll` E2E workflows.
- The E2E covers valid no-content execution, exact result/save hashes, restore, concurrent lock rejection, forced termination and lock release, negative validation, Unicode/space paths, no orphan process, and deletable state.
- Local gates pass: 25 POSIX native tests, MinGW PE cross-builds, Python harness tests, YAML checks, and a full macOS synthetic-core E2E. Final Phase 2 architectural/security audit accepted.
- Live `windows-2022` execution remains the target-runtime gate. It requires the in-progress working tree to be present on a remote ref; no local Windows machine is required.
- Atomic replacement currently inherits the hardened parent-directory ACL rather than explicitly preserving/applying a per-file ACL. This does not block synthetic Phase 2 but is an explicit blocker before enabling the first production Windows core.

### Pre-Tier-1 security gate result

- Win32 atomic replacement now applies the intended DACL to the private same-directory temporary file before exposing it. Existing destinations preserve their DACL; new destinations receive current-user and SYSTEM full access; NULL-DACL inputs are upgraded rather than preserved.
- DACL retrieval/freeing, SID conversion, failure cleanup, and MinGW linkage are covered by dedicated Windows native tests and real PE cross-builds. Target-runtime execution remains part of the live Windows CTest gate.

## Phase 3 first core — Gambatte

### Confirmed source and build facts

- Vendored source: `third_party/cores/gambatte`, pinned to `libretro/gambatte-libretro` commit `96174369b3c30d9fc57c926fa3379c273dc6a9a5`.
- Gambatte is an ordinary in-repository vendored snapshot, not a submodule. The upstream SHA is therefore expected not to exist in this repository's object database. Its tracked source inventory and VENDORING count comments need reconciliation, but this is not a pin mismatch.
- The existing Android and Linux fragments carry a curated network-disabled source subset. Reuse that exact subset rather than importing upstream's make-only Windows path or enabling Winsock/network behavior.
- Gambatte is software-rendered and can run in the temporary Windows software-only player without ANGLE.
- The MinGW DLL requires an explicit export definition/allowlist because the vendored Libretro entry points do not declare `__declspec(dllexport)`. The canonical package name is `gambatte_core.dll` with no `lib` prefix.
- No redistributable legal Game Boy ROM is currently vendored. Qualification will use a repository-owned generator that emits a minimal original cartridge image with a valid header/checksums and deterministic SRAM behavior; no third-party ROM bytes may be copied.

### Implementation and qualification order

1. Reconcile Gambatte's stale vendored-file/source-count comments, then add `gambatte-windows.cmake` using the exact existing 46-source curated list (16 C + 30 C++), Windows-safe defines/includes, network disabled, UCRT64-compatible flags, canonical output name, static compiler runtimes, and an explicit `.def` file enumerating the approved Libretro exports. Verify `--no-undefined` under UCRT64 rather than assuming the ELF link contract transfers unchanged.
2. Build Gambatte as a candidate in Windows CI without adding `windows-x86_64` to `CoreManifest.supportedAbis` yet. Audit PE machine type, exact exports, dependency closure, and repeated load/init/deinit.
3. Add a documented source generator for an original minimal `.gb` image. Pin its byte-level output/hash and test the Game Boy header/global checksums. The program should boot without external firmware and exercise battery-backed cartridge RAM deterministically if the core exposes it.
4. Extend the test-only software-player harness with a generic bounded-frame exit controlled by a compile-time definition plus environment variable, unavailable in production packaging and not exposed as a new CLI option. Run Gambatte with the generated ROM under dummy audio/offscreen software video; assert frames/audio, result schema, save size/hash, restore/adoption, repeated launch, forced-close recovery, and no locked files.
5. Only after all automated gates pass, add `windows-x86_64` to Gambatte's supported ABI/provenance and update the Windows support manifest/package inventory with the produced hash and evidence. Physical Windows 10/11/controller qualification remains later evidence and must not be falsely marked complete by hosted CI.

### Gambatte quality gates

- No source patch or network feature is introduced without explicit provenance.
- `gambatte_core.dll` exports only the approved Libretro API and has a closed UCRT64 dependency set.
- Generated test content is original, reproducible, and hash-pinned.
- Real-core video/audio/frame execution and SRAM round-trip pass in the Windows player.
- Windows support remains disabled in shared domain data until the candidate binary and E2E gates pass.

### Gambatte candidate result

- Added a shared, guarded 46-source Gambatte inventory used by Android, Linux, and the Windows candidate fragment; network support remains disabled.
- Added canonical `gambatte_core.dll` output with a 22-symbol `.def` export allowlist, PE/dependency/export audits, repeated native init/deinit smoke, and candidate-only staging.
- Added an original reproducible 32 KiB Game Boy test cartridge generator. It contains no Nintendo logo/boot-ROM/third-party bytes, has canonical header/global checksums, uses MBC1+8 KiB battery SRAM, and is pinned at SHA-256 `7eeb9a0ab9bf958dc98b0d04378529dd4687259a1f644e1dbb7e46973e18707d`.
- Added a compile-time qualification-only player frame bound, unavailable in normal builds and controlled by `ROMM_PLAYER_MAX_FRAMES` without extending the launch protocol or CLI.
- Local real-core E2E passes initial execution, 8 KiB SRAM checkpoint, restore/adoption, repeated load, force-kill lock recovery, Unicode paths, and cleanup. MinGW cross-build produces PE32+ with the exact exports and a closed staged runtime.
- Gambatte remains a candidate and is not present in `windows-x86_64` supported ABIs. Live `windows-2022` and physical Windows 10/11 evidence are still required before enablement.

### Live-CI blocker

- The current Windows workflow and implementation exist only in the local working tree on branch `zd/windows-build`; that branch and `.github/workflows/windows-x64.yml` do not exist on the remote.
- GitHub Actions cannot execute uncommitted local content. A live `windows-2022` run requires committing and pushing a remote ref, which is not authorized implicitly and therefore remains a hard external gate.
- Implementation continues with additional Tier 1 candidates, but no candidate may be promoted to `windows-x86_64` support until the remote CI and physical qualification evidence exists.

## Next Tier 1 candidate — FCEUmm

### Selection rationale

- ProSystem and Handy are simple build candidates but expose no `RETRO_MEMORY_SAVE_RAM`, so they cannot exercise the current checkpoint/adoption/restore gate without first defining a separate SRAM-less-core policy.
- Mednafen WonderSwan is a strong later candidate with save-memory support, but original deterministic V30 cartridge content is more complex to author and review.
- FCEUmm is software-rendered, BIOS-free, pure C, MinGW-friendly, and exposes 8 KiB battery WRAM for a mapper-0 iNES cartridge with the battery flag. A fully original deterministic 6502/NES ROM can therefore cover video, audio, SRAM, restore, and crash-recovery gates using the established candidate harness.

### Candidate implementation order

1. Reconcile FCEUmm's exact vendored source inventory and extract one guarded source-list fragment shared by Android, Linux, and Windows to prevent drift.
2. Add `fceumm-windows.cmake` with the existing network/platform-neutral source subset, UCRT64-compatible flags, software rendering, canonical `fceumm_core.dll`, and a `.def` containing the exact Libretro exports required by `CoreLibrary`. FCEUmm auto-dllexports `RETRO_API` under MinGW, so neutralize that default or otherwise prove through a PE audit that the `.def` remains authoritative.
3. Stage the DLL only under `cores-candidate/`; audit PE x86_64 machine type, imports, exact exports, hash/provenance, and repeated no-content init/deinit. Do not add `windows-x86_64` to shared domain/package data.
4. Add an original deterministic iNES generator containing no third-party game/BIOS assets. Use mapper 0 with the iNES battery bit (`0x02`) to expose 8192-byte save RAM; the trainer flag is optional and should be omitted unless the original program needs it. Pin header/layout/program bytes and SHA-256.
5. Extend the qualification-only bounded-frame E2E to boot FCEUmm under software video/dummy audio and assert presented frames, exact SRAM marker/counter invariants, adoption/restore, repeated load, forced-close recovery, and cleanup.
6. Keep the core candidate-only until live `windows-2022` and physical Windows 10/11/controller evidence exists.

### FCEUmm gates

- Existing Android/Linux source list and behavior remain unchanged.
- FCEUmm's large source inventory is generated or mechanically validated rather than manually allowed to drift across three platform fragments.
- No ELF version script or Android-only library reaches the Windows target.
- The candidate exports only the approved Libretro ABI and has no undeclared runtime dependency.
- Test content is original, generated reproducibly, and hash-pinned.
- Real-core save-memory and recovery flows pass locally and are wired into Windows CI.
- No Windows support flag is added before target-runtime and physical qualification.

### Session handoff

This session ended after the FCEUmm candidate's local qualification passed. Continue from `.slim/deepwork/windows-handoff.md`; do not repeat completed investigation.

### Phase 2 step 1 result (portable/POSIX seam extraction)

- Split the engine atomic file store: `native/engine/src/atomic_file_store.h` remains the stable platform-neutral contract (API unchanged); the POSIX implementation moved verbatim to `native/platform/posix/src/posix_atomic_file_store.cpp`; the old engine `.cpp` was deleted. Selected by all three build sites: `native/cmake/engine.cmake` (Android), `native/player/CMakeLists.txt`, and `native/tests/CMakeLists.txt`.
- Extracted narrow player platform contracts (headers in `native/player/include/native/player/`, POSIX implementations in `native/platform/posix/src/`): `posix_dynamic_library.{h,cpp}` (renamed from `sdl_dynamic_library.*`; pure dlopen wrapper, class `PosixDynamicLibrary`), `path_security.h`/`posix_path_security.cpp` (canonicalPath, isSymlink, request-file ownership/mode, fileSize — validation.cpp now carries no OS headers), `session_lock.h`/`posix_session_lock.cpp` (flock held for process lifetime, fail-closed containment, verbatim warnings), `platform_paths.h`/`posix_platform_paths.cpp` (homeDirectory, XDG default roots, /proc/self/exe), `process_control.h`/`posix_process_control.cpp` (SIGTERM/SIGINT flag, 5-second _exit(0) teardown alarm, execv re-exec), `health_metrics.h`/`posix_health_metrics.cpp` (getrusage ru_maxrss).
- `main.cpp` no longer includes any OS header; result-before-teardown/watchdog order, lock lifetime, protocol fixtures, and all error messages are preserved. No Win32 code, workflow/toolchain, production-core, or desktop-Kotlin changes.
- Local validation on macOS: engine/player test suite 19/19 green (plain and ASan/UBSan); every touched/new translation unit compiles warning-clean with -Wall -Wextra, including the ROMM_STEAM_DECK_PLAYER variant of main.cpp. Host limitation: the standalone player CMake cannot configure on macOS (`find_library(GLESv2)` + `<GLES3/gl3.h>` are Linux-only; pre-existing — the prior local cache also shows GLESV2_LIBRARY-NOTFOUND), so the full player link remains gated to `linux-x64.yml` jobs. Android NDK build not runnable locally; the engine.cmake change is a source-path swap verified by inspection (same include paths as before).
- Next CMake/Win32 needs: WIN32 source/link split in `native/player/CMakeLists.txt` (drop unconditional GLESv2/m/dl/pthread, add platform-selected sources), `native/platform/windows/` implementations of the seven contracts, Windows preset/toolchain file, and mirrored selection in `native/tests/CMakeLists.txt`.

## FCEUmm final bounded review

- Independent review found no blocking FCEUmm issues. The guarded 505-source inventory is identical across Android, Linux, and Windows candidate integration; the Windows definition file and workflow agree on the exact 22-export Libretro allowlist.
- The generated 40,976-byte iNES image reproduced SHA-256 `d1d4869696dcf53aeb7f207890d6f0cc7ad87fdcbc3054064fd935f042c281ea`, with mapper 0, battery-backed RAM, 32 KiB PRG, 8 KiB CHR, and reset vector `$8000`.
- Focused Python validation passed 40/40. The existing local report at `build/reports/fceumm-e2e-local/e2e-report.json` records 15/15 scenarios passing, including all four FCEUmm scenarios.
- FCEUmm and Gambatte remain candidate-only. Neither advertises `windows-x86_64` in `CoreManifest.supportedAbis`; live `windows-2022` and physical Windows qualification remain mandatory before enablement.
- Working-tree inspection found both cached and uncached `git diff --check` clean. Intended-looking untracked files are source, tests, workflow, CMake, and documentation rather than generated build output. The pre-existing dirty Dolphin and lrps2 submodule worktrees remain unstaged and must be excluded from any future commit.
- The tree is ready for a carefully scoped commit and push only after explicit authorization. No commit or push has been performed.

## First live Windows CI run

- User authorization was received. Commit `55cec6a2` (`Support Windows x64 desktop build`) was pushed to `origin/zd/windows-build`; only the pre-existing dirty Dolphin and lrps2 submodule worktrees remain locally.
- Live workflow run `33883749464` targeted the exact commit and failed before player E2E: https://github.com/DEV-DUFORD/rommulus/actions/runs/33883749464
- Shared JVM failure: a storage-api path test compares Windows `Path.toString()` output against a forward-slash suffix.
- Desktop failure: Windows file-security initialization rejects test temporary paths outside production Known Folder roots, causing a large `NoClassDefFoundError` cascade; one save-sync test also needs separate confirmation after the cascade is fixed.
- Native test/player failures: the workflow does not fully provision or expose the UCRT64 compiler, Ninja, binutils, and `x86_64-w64-mingw32-objdump` expected by the toolchain/audit steps.
- These are actual target-runtime CI failures and will be repaired sequentially by one implementation owner before rerunning the workflow. Candidate ABI enablement remains prohibited.

### Second run after first repair

- Repair commit `5886950d` was pushed and live run `33893987422` targeted that exact SHA: https://github.com/DEV-DUFORD/rommulus/actions/runs/33893987422
- The UCRT64 binutils discovery issue was cleared, but the run still failed. Desktop tests continue to cascade from Windows file-security initialization despite the test extension, so extension registration/initialization timing requires direct log-based diagnosis.
- Shared network tests now expose three Windows-only `BearerAuthInterceptorTest` assertion failures that require platform-neutral URL/origin expectations rather than speculative fixes.
- SDL configuration fails inside CMake compiler probing due to a generated `CMakeSystem.cmake` include problem; the exact malformed toolchain path/escaping must be extracted from logs.
- Native CTest configured and built, proving the compiler/binutils repair progressed. It then exposed Windows test defects involving `/tmp`, path-security/ACL expectations, SDDL checks, and timing before cancellation. These need bounded, log-driven corrections rather than weakening production behavior.
- Player E2E remained skipped behind the player-build failure. Windows core ABI enablement remains prohibited.

### Live Windows CI accepted

- Another implementation pass landed 19 corrective commits through `1441a19ef0f5e5d68ef114d364f2c7b8497c616c`. Independent audit accepted the changes: they correct Windows ABI/path/ACL/process behavior, CMake and PE tooling, and FCEUmm response-file/archive limits without weakening security gates or enabling a Windows core ABI.
- Workflow run `33913649579` passed all five jobs on hosted `windows-2022`: shared JVM tests, desktop assembly/tests, native UCRT64 tests, SDL3 software-only player/candidate build, and player E2E.
- The hosted run built canonical `gambatte_core.dll` and `fceumm_core.dll` under `cores-candidate/`, passed recursive PE/import closure and exact 22-export audits, completed 50-cycle load/init/deinit smoke, and passed the synthetic, Gambatte, and FCEUmm E2E scenarios.
- Hosted CI is now accepted for these candidates. Physical Windows 10 and Windows 11, physical controller, interactive graphics/audio, sleep/resume, and longer soak evidence remain outstanding. Therefore Gambatte and FCEUmm remain absent from `CoreManifest.supportedAbis` for `windows-x86_64`.
- The branch and remote are synchronized at `1441a19e`. The pre-existing dirty Dolphin/lrps2 submodule worktrees remain untouched. An untracked `logs` file is only a downloaded GitHub Actions log and is not repository source.

## Phase 3 candidate — ProSystem

- ProSystem was selected as the next bounded candidate. Its external Atari 7800 BIOS is optional for normal cartridges; a 16 KiB cartridge maps at `$C000-$FFFF` and supplies the standard 6502 reset vector, allowing fully original BIOS-free qualification content.
- Added one guarded 32-source inventory shared by Android, Linux, and Windows, canonical `prosystem_core.dll`, exact 22-export definition, candidate-only workflow staging, recursive PE/import audit, provenance, and repeated load/init/deinit smoke.
- Added an original 16 KiB Atari 7800 ROM generator pinned at SHA-256 `1d6b8f17eb536b015f7f42fa6897aa765cfe4702b0681029bf625c9b868c8afc`. Local real-core execution confirmed deterministic Maria video construction and TIA audio without a BIOS.
- ProSystem exposes no `RETRO_MEMORY_SAVE_RAM`. Its explicit no-persistent-save gate requires `checkpointWritten=false`, null save size/hash, no `.srm` artifact, repeatable relaunch, and force-kill lock recovery rather than silently skipping save assertions.
- Independent audit accepted the candidate. Commit `5fbc49c0` was pushed, and hosted Windows run `33937047709` passed all five jobs. ProSystem passed canonical-name, PE32+/import closure, exact exports, provenance, 50-cycle load smoke, valid launch, repeated load, and force-kill recovery. Hosted DLL SHA-256: `de556f800395432eb3ab79e0594d56026e583d0949f6665be4f845f140a5935d`.
- ProSystem remains candidate-only and absent from `CoreManifest.supportedAbis`. Physical Windows 10/11 and controller/interactive qualification remain pending.

## Phase 3 candidate — WonderSwan (repair in progress)

- Added candidate-only `mednafen_wswan_core.dll` integration at pin `4b01295838ea89e3f1355bbe4cb5cf98aa6108cd`, with a guarded shared 16-source inventory and an original generated cartridge pinned at SHA-256 `6a0857a6f787ac650e3b3be4191a2db59fc6c06ff7ad353188149945a8074d38`.
- Independent source and real-core audit accepted the V30/banking/trailer construction and locally reproduced the proposed SRAM oracle. Commit `477fc1b5` was pushed.
- Hosted run `33951078858` passed shared JVM, native UCRT64 tests, and the player build. WonderSwan passed canonical-name, PE/import closure, exact exports, provenance, and 50-cycle load smoke; hosted DLL SHA-256 was `cb73f6db3265c22676af921ea13e2fd25a6e84fff528c8b72ee68a977ece26ea`.
- Hosted E2E disproved the exact SRAM-count formula under the real Windows player timing: three normal WonderSwan scenarios failed their expected counter/hash, while force-kill recovery passed. The candidate is not accepted until the oracle is repaired from player/runtime evidence rather than weakened.
- The same run exposed one intermittent desktop exit-watcher reconciliation timeout. It is independent of the WonderSwan native build and requires bounded diagnosis.
- WonderSwan remains candidate-only and non-advertised; physical qualification remains pending.

### WonderSwan repair accepted

- The hosted failure was caused by the core's upstream-default `wswan_60hz_mode=enabled`, which performs five emulation frames for every four presented frames, plus an incorrect local oracle that did not model instruction resumption across V30 execution chunks or the player's SRAM-only restore into fresh processes.
- Commit `e6f0f8c8` pins `wswan_60hz_mode=disabled` only in `ROMM_PLAYER_QUALIFICATION` builds. Ordinary Linux/Android/Windows production builds retain the upstream default. The revised original ROM is pinned at SHA-256 `285040a46a2902422495289d531d005b9940b3118201ade531151fe9eaf01696`.
- The corrected oracle models each relaunch as an independent power-on with prior SRAM copied in. It verifies the accumulated counter, frame marker, final mirror byte, exact 8 KiB save image/hash, adoption/restore, repeated load, and force-kill recovery.
- Hosted run `33969138491` passed all five jobs and all 22 E2E scenarios: https://github.com/DEV-DUFORD/rommulus/actions/runs/33969138491
- Hosted `mednafen_wswan_core.dll` SHA-256: `873e180f98f247980adf555d472b3e5fe9c762875e9ee9e9e75f0befe5fca637`. Canonical naming, PE/import closure, exact 22 exports, provenance, repeated load/init/deinit, and all four WonderSwan scenarios passed.
- The prior desktop exit-watcher timeout did not recur and is treated as a hosted scheduling flake unless future runs provide repeatable evidence.
- WonderSwan remains candidate-only. Physical Windows 10/11, controller, interactive audio/video, sleep/resume, and soak qualification remain required before any `CoreManifest.supportedAbis` enablement.

## Phase 3 candidate — Beetle PCE Fast (complete, candidate-only)

- Selected after confirming that `mednafen_ngp` and `handy` expose only
  core-owned flash/EEPROM files and no `RETRO_MEMORY_SAVE_RAM`; supporting
  them would not exercise the player's current checkpoint contract.
- Beetle PCE Fast is a pure-C, software-rendered, BIOS-free HuCard candidate
  and exposes a fixed 2048-byte BRAM region through the standard Libretro
  save-memory API.
- Added a guarded shared 60-source inventory, canonical
  `beetle_pce_fast_core.dll`, exact 22-export definition, and candidate-only
  Win32 target. Local MinGW cross-build produces PE32+ x86-64 with the exact
  export allowlist.
- Added an original deterministic 8192-byte HuCard generator pinned at
  SHA-256 `db6dce97515cb1730e927358dcbffb55acbadaecc9e320efdc07499d262b342f`.
  The HuC6280 program preserves the core's eight-byte `HUBM` BRAM header and
  counts VBlank events in BRAM.
- Focused local real-core lifecycle E2E passes checkpoint creation,
  adoption/restore, repeated load, and force-kill recovery.
- Hosted run `33972683164` passed all five jobs and all 26 E2E scenarios:
  https://github.com/DEV-DUFORD/rommulus/actions/runs/33972683164
- Hosted `beetle_pce_fast_core.dll` SHA-256:
  `72c2a79fbf7df452e3cffa76a2807e9e17164f7fb6dd42af118d350744d6ad75`.
  Canonical naming, recursive PE/import closure, exact 22 exports, provenance,
  50-cycle load/init/deinit smoke, and all four PCE lifecycle scenarios passed.
- The initial hosted run exposed a Windows-only test-harness race around the
  fake launcher's invalid PID. Commit `694b15b5` makes non-positive PID
  reconciliation synchronous; the full desktop suite and repaired hosted run
  pass.
- Beetle PCE Fast remains candidate-only and absent from
  `CoreManifest.supportedAbis`. Physical Windows 10/11, controller,
  interactive audio/video, sleep/resume, and soak qualification remain
  required before enablement.

## Phase 3 candidate — Genesis Plus GX (complete, candidate-only)

- Genesis Plus GX was selected for its software-rendered, BIOS-free Genesis
  cartridge path and standard cartridge SRAM. The shared guarded inventory
  contains 115 sources; the candidate target emits canonical
  `genesis_plus_gx_core.dll` exclusively under `cores-candidate/`.
- Its exact boundary has 22 `retro_*` symbols plus four existing ROMM
  save-image extensions. Those extensions normalize enabled cartridge SRAM to
  a fixed 65536-byte image for the player's checkpoint contract.
- The original deterministic 16 KiB 68000 cartridge is pinned at SHA-256
  `00a36ef98679e714baec5591af603a51e61579b1f5ab1749e802c3a76662fd2a`.
  It enables VDP display before polling VBlank and writes a persistent SRAM
  marker/counter. The lifecycle oracle accounts for the one trailing reported
  player frame per fresh core process.
- Hosted run `33976626930` passed all five jobs and all 30 E2E scenarios:
  https://github.com/DEV-DUFORD/rommulus/actions/runs/33976626930
- Hosted `genesis_plus_gx_core.dll` SHA-256:
  `05fdf7b5e3311a2f6b25b904c4c13e5c492cae56b8ebd890f7a07b01a11c08c0`.
  Canonical naming, recursive PE/import closure, the exact 26-export audit,
  50-cycle load/init/deinit smoke, checkpoint creation, SRAM-only
  adoption/restore, repeated load, and force-kill lock recovery passed.
- Genesis Plus GX remains candidate-only and absent from
  `CoreManifest.supportedAbis`. Physical Windows 10/11, controller,
  interactive audio/video, sleep/resume, and soak qualification remain
  required before enablement.

## Phase 3 candidate — mGBA (complete, candidate-only)

- mGBA uses the guarded shared 98-source inventory and upstream's
  MinGW-compatible file VFS backend. The candidate emits canonical
  `mgba_core.dll` only under `cores-candidate/`.
- The original 4 KiB ARM GBA fixture is pinned at SHA-256
  `53b1633bdabf63b59f169d2ba971ed361e3d4018c3a368e17272fe6e5415745d`.
  It selects the standard 32 KiB SRAM medium and counts VBlank transitions.
- The core's deferred save loading required three ROMM extensions to retain a
  restored variable-sized image until the actual save medium is detected.
  Its exact export boundary is 22 `retro_*` symbols plus those three
  extensions.
- Hosted run `33985740463` passed all five jobs and all 34 E2E scenarios:
  https://github.com/DEV-DUFORD/rommulus/actions/runs/33985740463
- Hosted `mgba_core.dll` SHA-256:
  `555e7e43bd3351ca126753409657b6710d574c4c67690b2cfa31b6c08dc5bb63`.
  Canonical naming, recursive PE/import closure, exact exports, 50-cycle
  smoke, checkpoint/adoption, repeated load, and force-kill recovery passed.
- mGBA remains candidate-only and absent from `CoreManifest.supportedAbis`.
  Physical Windows 10/11, controller, interactive audio/video, sleep/resume,
  and soak qualification remain required before enablement.

## Phase 3 candidate — Snes9x (complete, candidate-only)

- Snes9x 1.63 (`921f9f7b83660eb44ad263022a57a4a029057c37`) uses a guarded
  shared 54-source inventory for Android, Linux, and the MinGW/UCRT64
  candidate. The canonical `snes9x_core.dll` is staged exclusively under
  `cores-candidate/`.
- The original deterministic 32 KiB LoROM fixture is pinned at SHA-256
  `74718b64e00e86a26058d08c6b0bb2f9ff82d67ba495e0b294b6cd437172c8a7`.
  It exposes standard 2 KiB battery SRAM and preserves its VBlank counter
  over player SRAM-only adoption/restore.
- The exact Windows boundary is the standard 22 `retro_*` exports. No ROMM
  save extension was required.
- Hosted run `33989376162` passed all five jobs and the full player E2E
  suite: https://github.com/DEV-DUFORD/rommulus/actions/runs/33989376162
- Hosted `snes9x_core.dll` SHA-256:
  `54aeef176ac87c61d452af95be1f144bbe9c287556cc571895361fa8fbb497ac`.
  Canonical naming, recursive PE/import closure, exact exports, provenance,
  50-cycle load/init/deinit smoke, valid launch, checkpoint/adoption restore,
  repeated load, and force-kill lock recovery passed.
- A separate read-only audit found no candidate defects. Snes9x remains
  candidate-only and absent from `CoreManifest.supportedAbis`; physical
  Windows 10/11, controller, interactive audio/video, sleep/resume, and soak
  qualification remain required before enablement.

## Phase 3 candidate — Stella (complete, candidate-only)

- Stella 7.0 (`d55b1aec0d067a4c901a6dcdf81cb8f579685659`) uses a guarded
  148-source inventory shared by Android, Linux, and the MinGW/UCRT64
  candidate. Canonical `stella_core.dll` is staged exclusively under
  `cores-candidate/`.
- The original BIOS-free 4 KiB Atari 2600 fixture is pinned at SHA-256
  `a6456779ea64bf28bae72e4842bb896b7994b38e86c8074b5c4f5e0172fdc2c3`.
  It emits valid repeated NTSC VSYNC frame intervals, video, and audio.
- Stella exposes `RETRO_MEMORY_SYSTEM_RAM` only, not
  `RETRO_MEMORY_SAVE_RAM`; its rigorous E2E gate therefore asserts no
  checkpoint, null save fields, and no candidate/session `.srm` artifacts
  across valid launch, repeated load, and force-kill lock recovery.
- Hosted run `33992192421` passed all five jobs:
  https://github.com/DEV-DUFORD/rommulus/actions/runs/33992192421
- Hosted `stella_core.dll` SHA-256:
  `300c89d2ca6a74c5d1cfa061b345300663c4c9483b199dc28935d84ebdd628a2`.
  Canonical naming, recursive PE/import closure, exact 22 exports,
  provenance, 50-cycle load/init/deinit smoke, and all no-save E2E scenarios
  passed.
- Independent audit corrected the fixture's post-first-frame VSYNC loop before
  the final hosted run. Stella remains candidate-only and absent from
  `CoreManifest.supportedAbis`; physical Windows 10/11, controller,
  interactive audio/video, sleep/resume, and soak qualification remain
  required before enablement.

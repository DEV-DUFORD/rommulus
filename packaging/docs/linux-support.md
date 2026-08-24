# RomMulus on Linux — Support, GPU expectations, and diagnostics

User/operator-facing companion to the self-contained `rommulus-<version>-linux-x86_64.tar.zst`
bundle (see `packaging/README.md` for package layout and the launcher contract). This document
states the supported scope honestly per `plans/LINUX_X64.md` §19 criterion 20: what works, where
it was proven, and what is not yet available.

## Supported distributions

- **Ubuntu 24.04 (x86_64) — supported.** This is the build baseline and the machine where the
  desktop end-to-end flow and every per-core gate were actually run
  (`docs/linux-support-manifest.md`). If you hit a problem, this is the environment it was
  proven in.
- **Steam Deck desktop mode (x86_64) — targeted, NOT yet validated.** The release archive
  statically links SDL3 and bundles its JVM, so it does not require either component to be
  installed through SteamOS. Physical Deck validation is still required before claiming full
  support.
- **Fedora (current release) and Arch-class systems — targeted, NOT yet validated.** The plan's
  physical Linux matrix (`plans/LINUX_X64.md` §17.5) requires validation on one current Fedora
  and one Arch-class system before the first release is complete. That validation has not been
  done yet, so we do **not** claim these distributions as supported today. They are expected to
  work but are unproven; treat them as "best effort until validated".
- **Not supported (by design):** Windows, macOS, ARM64 Linux, and musl-based Linux
  (`plans/LINUX_X64.md` §4 non-goals). There is no plan in the initial port to build or ship for
  these platforms.

Session/display scope from the same matrix: Wayland (and X11/XWayland where supported), Intel
and AMD x86_64 CPUs, 1080p/4K/fractional scaling/multi-monitor are required test cases — but
again, only Ubuntu 24.04 has been exercised so far.

## GPU and graphics expectations

**Nintendo 64, GameCube, and PS2 use GPU-accelerated OpenGL ES rendering; the other current cores use software rendering.**

- The player (`bin/rommulus-player`) runs a separate SDL3 window. For software-rendered cores it uses
  the **software frame path** (`plans/LINUX_X64.md` §11.6): each frame produced by the core is
  converted and uploaded to a reusable SDL texture; there is no Vulkan for software presentation.
- **N64 hardware rendering:** Mupen64Plus-Next uses GLideN64 through an SDL3-managed OpenGL ES 3
  context, with the x86_64 new dynarec and HLE RSP. This is the same GPU renderer used by the
  Android build and is the default on Linux, including Steam Deck. The package intentionally uses
  the host's OpenGL ES/EGL dispatch libraries so they remain compatible with the installed GPU
  driver rather than bundling the Ubuntu build machine's graphics stack.
- **GameCube hardware rendering:** Dolphin uses the same SDL3-managed OpenGL ES 3 frontend.
  Ubuntu uses the offscreen compositor path; Steam Deck uses the isolated direct-framebuffer
  player, matching the N64 platform split. Dolphin remains Linux-only.
- **PS2 hardware rendering:** lrps2 (a PCSX2 fork) uses the same SDL3-managed OpenGL ES 3
  frontend, with the x86_64 EE/IOP/VU recompiler. Like the other hardware cores it is
  Linux-only. PS2 requires a console BIOS (PS2 BIOS images are not shipped; point the
  player's BIOS directory at your own copy); the core's `GameIndex.yaml` (PS2 game
  database) ships in the package at `share/rommulus/lrps2/resources/GameIndex.yaml`.
- The other enabled cores remain software-rendered by the player (see
  `docs/linux-support-manifest.md`):
  - `pcsx_rearmed` — Lightrec x86_64 dynarec with a software renderer;
  - the remaining real cores (gambatte, fceumm, prosystem, handy, mednafen_ngp, mednafen_wswan,
    stella, beetle_pce_fast, mgba, snes9x, genesis_plus_gx) plus the synthetic `test_core` used
    for end-to-end testing are natively software cores.
- **CPU performance still matters** for CPU emulation and software-rendered systems such as
  PlayStation via `pcsx_rearmed`, while N64, GameCube, and PS2 graphics scale with GPU
  performance (PS2 CPU emulation via lrps2's recompiler is also CPU-bound).
- **NVIDIA proprietary-driver qualification is deferred.** This preview targets Steam Deck's AMD
  Mesa stack; the new hardware path is not yet qualified on proprietary NVIDIA drivers.

## Diagnostics and troubleshooting

### Launcher exit codes (`bin/rommulus`)

| Code | Meaning | What to do |
| --- | --- | --- |
| `0` | success | — |
| `3` | required bundle file missing (incomplete or corrupted extraction) | Re-extract a pristine tarball. The launcher names the missing file on stderr. |
| `4` | checksum/manifest mismatch, or manifest entry missing (bundle modified after packaging) | Re-extract a pristine tarball; do not run from a partially modified tree. |
| `5` | no usable JVM — bundled `lib/runtime/bin/java`, `$JAVA_HOME`, and `java` on PATH all failed | The bundle is incomplete; re-extract the tarball (it ships its own jlink runtime). |

Run `bin/rommulus --verify-full` to check **every** file in `share/rommulus/PACKAGE.sha256`
(the default startup check covers the security-critical set: player, app jar, all `.so` files,
core manifest, and bundled JVM runtime natives).

### Where your data lives (XDG)

Per `plans/LINUX_X64.md` §9 (defaults shown where the `XDG_*` variable is unset):

| Path | Contents |
| --- | --- |
| `$XDG_STATE_HOME/rommulus/journals/` (default `~/.local/state/rommulus/journals/`) | launch journals — **first place to look on any problem** |
| `$XDG_STATE_HOME/rommulus/logs/` (default `~/.local/state/rommulus/logs/`) | application logs |
| `$XDG_STATE_HOME/rommulus/credentials/client-tokens.properties` | Owner-only (`0600`) client-token fallback used only when no Secret Service provider exists, including Steam Deck Gaming Mode |
| `$XDG_DATA_HOME/rommulus/saves/` (default `~/.local/share/rommulus/saves/`) | save files |
| `$XDG_DATA_HOME/rommulus/cores/` (default `~/.local/share/rommulus/cores/`) | installed cores (`lib<core>_core.so`); the launcher seeds bundled cores here on first run, additively only |

The launcher never overwrites existing files in these locations; install/update/uninstall must
not delete XDG user data (§19 criterion 17).

### "A game won't launch" — checklist

1. **Check the journal** in `$XDG_STATE_HOME/rommulus/journals/` for the most recent launch
   request/result and any error. Launch request/result files are mode `0600`.
2. **Check the cores directory** `$XDG_DATA_HOME/rommulus/cores/`: the desktop derives which
   cores may run from this directory at launch time, so the core must actually be present there.
3. **Verify the core `.so` exists** — the file should be named `lib<core>_core.so` (e.g.
   `libgambatte_core.so`). If it is missing, re-run the launcher to re-seed bundled cores, or
   copy the built core into that directory. A core that is not in the installed-cores directory
   is unavailable, not silently substituted (§19 criterion 18).
4. **Check the launcher stderr** for exit codes 3/4/5 above — a missing or modified bundle file
   will stop everything before the app even starts.

### Save round-trip caveat (be aware)

Saves are checkpointed on quit and on crash recovery, but per-core save round-trip verification
(§13.2 criterion 9) is **DEFERRED** for the 12 real cores still pending it: the Linux desktop
saves UI does not exist yet, so there is no easy way to exercise it
(`docs/linux-support-manifest.md`). gambatte's save round-trip was verified in its own gate
(SRAM save adoption + restore across two Play sessions on Ubuntu). Until criterion 9 is re-run
per core with a real saves UI, treat save round-tripping as "checkpointed and expected to work,
not yet verified end-to-end for every core".

### Reporting a crash

If the player crashes (or is killed): the desktop client stays open and presents recovery/save
status rather than closing (§8.2). Forced termination (SIGKILL) is treated as a crash and
reconciled from the journal/candidate files (§8.3). When reporting a crash, attach:

- the most recent journal file(s) from `$XDG_STATE_HOME/rommulus/journals/`;
- the log files from `$XDG_STATE_HOME/rommulus/logs/`;
- your distribution + version (e.g. "Ubuntu 24.04.2"), GPU/model, and desktop session
  (Wayland/X11);
- the exact command you ran and its exit code if it came from `bin/rommulus`.

## Scope honesty notes — what is and isn't supported right now

**Supported today:**

- Ubuntu 24.04 x86_64, self-contained tarball (bundled jlink JVM runtime, player, cores,
  manifests, desktop entry).
- All 15 enabled cores; N64 and GameCube are hardware-rendered (see the GPU section and
  `docs/linux-support-manifest.md`).
- Controller input via SDL3 in the native player; the desktop client uses JInput for controller
  input (`plans/LINUX_X64.md` §11.9 amendment). Keyboard and mouse work everywhere.
- Fullscreen (default) and windowed play, integer scaling, pause menu, clean quit/checkpoint.

**Not supported yet (honest list):**

- **No Flatpak.** Flatpak is Phase 14 work item 9 — only after tarball stability and sandbox
  spikes pass. Not available now.
- **AppImage is available** for direct desktop and Steam Deck launching. Snap, `.deb`, `.rpm`,
  and native Steam packages remain unavailable.
- **No Windows, macOS, ARM64 Linux, or musl Linux builds** (§4 non-goals).
- **No save UI.** Saves are checkpointed on disk, but there is no Linux desktop saves screen yet;
  per-core criterion 9 round-trip verification is deferred (see above).
- **No Vulkan negotiation.** The N64 and GameCube hardware paths use OpenGL ES 3.
- **No save states, rewind, netplay, or shaders** — explicitly out of scope (§4 non-goals).

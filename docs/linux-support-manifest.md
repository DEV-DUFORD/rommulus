# Linux x86_64 Core Support Manifest

Explicit enablement record for the Linux x86_64 desktop player (plans/LINUX_X64.md §13.2
criterion 14: "explicit enablement in the Linux support manifest"). A core is launchable on
the Linux desktop only when it appears in this manifest AND its
`CoreManifest.supportedAbis` contains `linux-x86_64` — the desktop launcher derives
`ROMM_PLAYER_ALLOWED_CORES` from the installed cores directory at launch time (approved,
`linux-x86_64`-capable entries only), and core resolution rejects any platform core without
the `linux-x86_64` ABI.

A successful compile is not approval (§13.2): every core must pass the full 14-step per-core
gate before its gate status below may move to PASSED.

## Core status

| Core | Linux build target | Gate status | Enabled |
| --- | --- | --- | --- |
| `test_core` | `native/player/CMakeLists.txt` (`add_library(test_core SHARED …)`) | PASSED — Phase 8 desktop E2E passed on Ubuntu | yes |
| `gambatte` | `native/cmake/cores/gambatte-linux.cmake` (included by `native/player/CMakeLists.txt`) | PASSED — §13.2 per-core gate completed on the Ubuntu box | yes |
| `fceumm` | `native/cmake/cores/fceumm-linux.cmake` (included by `native/player/CMakeLists.txt`) | PENDING — §13.2 gate on Ubuntu box | no |

## gambatte — §13.1 Linux build identity

Provenance fields from the `CoreManifest` entry (coreId `gambatte`):

| Field | Value |
| --- | --- |
| Upstream repository | https://github.com/libretro/gambatte-libretro |
| Exact commit | `96174369b3c30d9fc57c926fa3379c273dc6a9a5` (no upstream release tags; commitSha is the exact pin) |
| Vendored source subset | `third_party/cores/gambatte/{libretro,src,libretro-common}` — see `third_party/cores/gambatte/VENDORING.md` (network code, intl scripts, CI/docs excluded) |
| Local patches | none |
| Compiler / build | standard Linux Clang, `-O2 -DNDEBUG`, `-std=c++11`, upstream `link.T` version script (exports only the `retro_*` Libretro ABI) |
| Build command | `cmake -S native/player -B build/player && cmake --build build/player` (see below) |
| License | GPL-2.0-only (root COPYING; no non-commercial/no-sale restriction; separately-licensed dynamically-loaded `.so` posture) |
| Supported systems | `gb`, `gbc` |
| Supported extensions | `.gb`, `.gbc` |
| Required firmware | none |
| Renderer | software, RGB565 (XRGB8888 fallback) |
| Frame geometry / rate | 160x144 @ 59.73 fps |
| Audio | ~32768 Hz int16 stereo |
| Saves | SRAM via `RETRO_MEMORY_SAVE_RAM` |
| `valid_extensions` | `gb\|gbc\|dmg` |
| No-game support | none — the core needs a real ROM (no no-game boot) |
| Linux runtime qualification | PASSED — §13.2 per-core gate completed on the Ubuntu box |

## Ubuntu box: build and install

```sh
# From the repository root (Ubuntu, x86_64; SDL3 dev package required):
cmake -S native/player -B build/player && cmake --build build/player

# Install the core into the player's cores root:
mkdir -p "${XDG_DATA_HOME:-$HOME/.local/share}/rommulus/cores"
cp build/player/libgambatte_core.so "${XDG_DATA_HOME:-$HOME/.local/share}/rommulus/cores/"
```

### §13.2 gate note: ASan/UBSan (gambatte) under clang-18 — criterion 11 PASSED via `-shared-libasan`

Criterion 11 (build with `-fsanitize=address,undefined`) **passes** on Ubuntu/clang-18, using
the `-shared-libasan` workaround. The plain spec flags do not link cleanly: the linker pulls in
`libclang_rt.asan_static-x86_64.a` via the automatic `-shared` link, but that static runtime does
not define `__asan_init`; the core's `-Wl,--no-undefined` (`-z defs`) then rejects every
sanitizer symbol, producing errors such as `undefined reference to '__asan_init'`,
`__asan_report_load8`, and `__ubsan_handle_type_mismatch_v1`. **Verified workaround:** append
`-shared-libasan` to the sanitizer link flags — `clang++ … -fsanitize=address,undefined -shared
-shared-libasan -Wl,--no-undefined` — yields a clean link. Criterion 11 is recorded as PASSED on
this basis: a consistent-runtime rebuild (executable and shared core both linked with
`-shared-libasan`) runs clean on the Ubuntu box with no ASan memory errors. Three UBSan warnings
remain — `left shift of negative value` at `third_party/cores/gambatte/src/video/ppu.cpp:265:41`
and `sound/channel3.cpp:155:34`, `170:38` — which are pre-existing upstream Gambatte code, not
host regressions.

The desktop launcher picks the core up automatically on the next launch: it scans
$XDG_DATA_HOME/rommulus/cores/ for `lib*.so`, and `libgambatte_core.so` yields
`gambatte=96174369b3c30d9fc57c926fa3379c273dc6a9a5` in `ROMM_PLAYER_ALLOWED_CORES`.
gambatte is now explicitly enabled by this manifest (criterion 14): the §13.2 per-core gate
passed, including Criterion 13 (E2E boot, controller input, and SRAM save adoption + restore
across two Play sessions on the Ubuntu box).

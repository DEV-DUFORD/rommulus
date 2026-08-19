# RomMulus Linux x86_64 packaging (Phase 14)

Source of truth for the self-contained release tarball defined in
`plans/LINUX_X64.md` §16.1. `build-tarball.sh` assembles the tarball from this
directory plus the built player, cores, app jar, and jlink JVM runtime.

## Tarball layout

```text
rommulus-<version>-linux-x86_64.tar.zst
├── bin/
│   ├── rommulus                 launcher (this directory: packaging/bin/)
│   ├── rommulus-player          native player ELF (from build/player/rommulus_player)
│   └── rommulus_player          compatibility symlink -> rommulus-player
│                                (the desktop app resolves "rommulus_player" via PATH)
├── lib/
│   ├── runtime/                 jlink JVM 17 image (bin/java, lib/, conf/)
│   └── rommulus/
│       ├── app.jar              Compose Multiplatform desktop application
│       ├── *.so                 player runtime shared libraries (if any)
│       └── cores/*.so           bundled Libretro cores (lib<core>_core.so)
└── share/
    ├── applications/com.devduford.rommulus.desktop
    ├── icons/                   hicolor theme tree (icons PENDING — see its README)
    ├── licenses/rommulus/       libretro.txt, third_party_license_metadata,
    │                            third_party_licenses, NOTICE
    └── rommulus/
        ├── core-manifest.json   §13.1 Linux build identity for all 14 cores
        └── PACKAGE.sha256       generated at package time (every file except itself)
```

Notes:

- `lib/rommulus/app.jar` is the home of the JVM application; §16.1's content
  list implies `lib/rommulus/` as a directory, and this is where the app jar
  belongs.
- The `bin/rommulus_player` symlink exists only because the desktop app's
  player lookup is `Path.of("rommulus_player")` resolved via PATH
  (`desktop/src/main/kotlin/com/romm/desktop/player/PlayerProcessLauncher.kt`).
  Remove it once the desktop resolves the bundled player path directly.
- `PACKAGE.sha256` provides internal integrity checking (the launcher verifies
  against it at startup). It is NOT a substitute for the release signature —
  GPG signing of the tarball is a later Phase 14 sub-unit (§16 work item 4).

## Assembling the tarball (Linux only)

Prereqs on the Ubuntu 24.04 box: `zstd`, GNU tar, coreutils, clang-18/cmake/
ninja/nasm + SDL3 (for the player build), JDK 17 (Gradle + jlink).

```sh
# 1. Build the player and cores (Release):
cmake -S native/player -B build/player \
  -DCMAKE_C_COMPILER=clang-18 -DCMAKE_CXX_COMPILER=clang++-18 \
  -DCMAKE_BUILD_TYPE=Release
cmake --build build/player

# 2. Build the desktop app jar:
./gradlew :desktop:jar          # -> desktop/build/libs/desktop.jar

# 3. Build the jlink JVM runtime (see below):
jlink ...                       # -> build/runtime/

# 4. Assemble:
packaging/build-tarball.sh 0.1.0
# -> dist/rommulus-0.1.0-linux-x86_64.tar.zst
```

Overrides: `PLAYER_BUILD_DIR`, `APP_JAR`, `RUNTIME_DIR`, `OUT_DIR`,
`ROMMULUS_VERSION` (or the positional `VERSION` argument).

The script fails fast if any input is missing, validates
`core-manifest.json` as JSON, asserts no world-writable files in the package,
prints the executable inventory (for the §15 "no undeclared executable"
review), and writes a deterministic tar (`--sort=name --owner=0 --group=0
--numeric-owner --mtime=@0`) compressed with zstd pinned to `-T1` (single
thread) so the artifact is byte-for-byte reproducible across hosts — `-T0`
would vary the thread count with the machine and change the compressed bytes.

## JVM runtime (jlink)

`lib/runtime/` must be a jlink image from JDK 17 (Temurin in CI):

```sh
# The ENTIRE --add-modules list must stay on ONE line: a backslash
# continuation after the trailing comma collapses to a single space and
# jlink fails with "Error: invalid argument".
jlink --add-modules java.base,java.datatransfer,java.desktop,java.logging,java.prefs,java.sql,java.xml,jdk.unsupported --output build/runtime --strip-debug --no-header-files --no-man-pages
```

**TRACKED FOLLOW-UP (release-blocking):** the module list above is
provisional. It lacks `java.net.http`, `jdk.crypto.ec`, `java.naming`, and
`java.management`, which the desktop app needs for OkHttp/HTTPS.
(`java.datatransfer` covers clipboard; `jdk.unsupported` covers sun.misc
usage by some libraries.) Before ANY release artifact ships, complete the module
list against the app's actual runtime needs and exercise the built runtime
with the real app (run the desktop app against `build/runtime/bin/java`) to
confirm no `java.lang.ModuleNotFoundException` /
`NoClassDefFoundError`. Landing the CI job does not block on this.

## Launcher contract (`bin/rommulus`)

1. **Path resolution** — resolves its own location with `readlink -f`
   (symlink-safe) and derives `APP_ROOT` as the parent of `bin/`. The whole
   bundle is relocatable; nothing hard-codes an install prefix.
2. **JVM location** — uses `$APP_ROOT/lib/runtime/bin/java`; documented
   fallbacks are `$JAVA_HOME/bin/java`, then `java` on PATH (each with a
   warning that the bundle is incomplete). If none exist: exit 5.
3. **Native library paths** — prepends `$APP_ROOT/lib/rommulus` and
   `$APP_ROOT/lib/rommulus/cores` to `LD_LIBRARY_PATH` (preserving any user
   value), and prepends `$APP_ROOT/bin` to `PATH` so the desktop app can find
   the player. No other environment is set.
4. **No credentials in env** — RomM server tokens live in the freedesktop
   Secret Service (§10); the launcher sets no credential variables, ever.
5. **exec** — replaces itself with `java -cp lib/rommulus/app.jar
   com.romm.desktop.MainKt [args]`.
6. **XDG preservation** — `XDG_DATA_HOME` (and friends) are read with spec
   defaults but never modified. On first run the launcher seeds bundled cores
   into `$XDG_DATA_HOME/rommulus/cores/` ADDITIVELY only (existing user files
   are never overwritten), because the desktop derives
   `ROMM_PLAYER_ALLOWED_CORES` from that directory at launch. Disable with
   `ROMMULUS_NO_CORE_SEED=1`.
7. **Fail-fast integrity** — required files (`bin/rommulus-player`,
   `lib/rommulus/app.jar`, `share/rommulus/core-manifest.json`,
   `share/rommulus/PACKAGE.sha256`, at least one core) must exist, else exit 3.
    Checksums are verified against `PACKAGE.sha256`: by default the critical set
    (player, app jar, all `lib/rommulus/*.so` + cores, core-manifest.json, and
    the bundled JVM runtime natives — `lib/runtime/bin/java` plus
    `lib/runtime/lib/*.so`, since a tampered JVM is code-execution-critical);
    `--verify-full` verifies every file in the manifest. Mismatch or a missing
    manifest entry is exit 4 with an explicit message.

Exit codes: `0` ok · `3` missing bundle file · `4` checksum/manifest problem ·
`5` no usable JVM.

## Desktop entry and icons

`share/applications/com.devduford.rommulus.desktop` ships with
`Exec=__ROMMULUS_INSTALL_PREFIX__/bin/rommulus`. The placeholder is NOT a
freedesktop field code (the spec defines no `%h`) and desktop environments do
not expand shell variables in `Exec`, so the install step MUST rewrite it to
the actual absolute prefix before installing the file:
`<abs $HOME>/.local/share/rommulus` for a per-user install, `/opt/rommulus`
for a system-wide one. The launcher itself is fully relocatable. Icons are
pending — see `share/icons/README.md`.

## Known follow-ups (later Phase 14 sub-units)

- GPG signature over the tarball + publish flow (`release.yml`).
- Pin Linux x86_64 core `.so` SHA-256s into `CoreManifest.kt` and
  `core-manifest.json` (`binaryChecksums."linux-x86_64"` is currently null).
- The `package-tarball` / `license-and-provenance-audit` CI jobs in
  `.github/workflows/linux-x64.yml` are wired (Phase 14 sub-unit 2): the former
  builds player + cores (Release), the desktop app jar, and a jlink runtime
  (command above), assembles via this script, verifies/uploads the tarball, and
  smoke-tests the launcher fail-fast (exit 5 with a clear message when the
  bundled runtime is absent); the latter runs the executable §15 assertions —
  shared-module Android imports, native-engine JNI/Android/SDL references,
  required licenses, exact executable inventory, world-writable files,
  credential/token scan, core provenance (every manifested core ships), and a
  `ldd` check that the player has no JVM/Android dependency — against this tree
  and the packaged artifact. Remaining gaps: verify the jlink module list
  against the app's runtime needs, pin `binaryChecksums."linux-x86_64"` for the
  full core SHA/provenance match, add a symbol allowlist, and start the desktop
  bundle headlessly in CI.
- `desktop/build.gradle.kts` still carries the `TODO(phase 14)` comment on
  `nativeDistributions`; update it when Gradle-side packaging is wired up.
- Finalize `share/licenses/rommulus/third_party_license_metadata`: it now lists
  the desktop module's verifiable DIRECT dependencies (the file previously
  listed the Android app's dependency tree by mistake), but the full transitive
  closure — Compose Multiplatform runtime jars, Skiko natives, JNR FFI stack,
  okio, Kotlin stdlib versions — must be enumerated from a Gradle
  `:desktop` runtimeClasspath report, with license texts added to
  `third_party_licenses`, BEFORE RELEASE.

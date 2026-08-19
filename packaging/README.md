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
        ├── PACKAGE.sha256       generated at package time (every file except itself)
        └── PACKAGE.sha256.asc   OPTIONAL detached GPG signature over PACKAGE.sha256
                                 (present only when built with ROMMULUS_SIGN_KEY / --sign)
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
  against it at startup). The optional GPG signature (`PACKAGE.sha256.asc`,
  §"Signing") is NOT checked by the launcher — it exists for distribution-
  integrity verification by users and inspectors (§16 work item 4).
- User-facing documentation: see [docs/linux-support.md](docs/linux-support.md) — supported distributions, GPU expectations, and diagnostics.

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
`ROMMULUS_VERSION` (or the positional `VERSION` argument), and
`ROMMULUS_SIGN_KEY` (or the `--sign <keyid>` flag) for optional GPG signing.

The script fails fast if any input is missing, validates
`core-manifest.json` as JSON, asserts no world-writable files in the package,
prints the executable inventory (for the §15 "no undeclared executable"
review), and writes a deterministic tar (`--sort=name --owner=0 --group=0
--numeric-owner --mtime=@0`) compressed with zstd pinned to `-T1` (single
thread) so the artifact is byte-for-byte reproducible across hosts — `-T0`
would vary the thread count with the machine and change the compressed bytes.

## Signing (optional GPG)

Builds are **unsigned by default** (a note is logged). To sign a release:

```sh
# One-time setup on the signing machine / CI: create or import the release key
gpg --full-generate-key          # e.g. "RomMulus Release <release@example.com>"

# Sign at package time (key id or full fingerprint):
ROMMULUS_SIGN_KEY=<keyid-or-fingerprint> packaging/build-tarball.sh 0.1.0
# equivalent flag form:
packaging/build-tarball.sh --sign <keyid-or-fingerprint> 0.1.0
```

Behavior:

- The script signs `share/rommulus/PACKAGE.sha256` (the checksum manifest),
  not the tarball bytes: `gpg --detach-sign --armor --local-user <key>
  -o PACKAGE.sha256.asc`. The `.asc` is written into the staging tree after
  the manifest exists, so it ships inside the tarball but — like
  `PACKAGE.sha256` itself — is not listed in the manifest.
- **Fail-closed:** if `ROMMULUS_SIGN_KEY`/`--sign` is set but gpg is missing or
  cannot resolve the key in the local keyring, the build FAILS with a clear
  message. An unsigned artifact is never produced when signing was explicitly
  requested; "no key configured" is the only path to an unsigned build.
- A PGP signature embeds a creation timestamp, so **signed artifacts are not
  byte-for-byte reproducible** (unsigned builds remain deterministic).
- The launcher does NOT need the signature and never checks it at runtime —
  it verifies checksums against `PACKAGE.sha256` (exit 4 on mismatch), which
  is what protects users at launch. The signature is for distribution-integrity
  verification by users/inspectors:

  ```sh
  tar --zstd -xf rommulus-<version>-linux-x86_64.tar.zst \
      share/rommulus/PACKAGE.sha256 share/rommulus/PACKAGE.sha256.asc
  gpg --verify share/rommulus/PACKAGE.sha256.asc share/rommulus/PACKAGE.sha256
  ```

  Because `PACKAGE.sha256` covers every shipped file, a valid signature over
  it transitively covers the whole tarball.
- CI note: the script does not manage passphrases. A passphrase-protected key
  needs an unlocked `gpg-agent` (e.g. secret key imported with the agent
  loaded, or pinentry loopback) at build time.

## JVM runtime (jlink)

`lib/runtime/` must be a jlink image from JDK 17 (Temurin in CI):

```sh
# The ENTIRE --add-modules list must stay on ONE line: a backslash
# continuation after the trailing comma collapses to a single space and
# jlink fails with "Error: invalid argument".
jlink --add-modules java.base,java.datatransfer,java.desktop,java.logging,java.management,java.naming,java.net.http,java.prefs,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported --output build/runtime --strip-debug --no-header-files --no-man-pages
```

**MODULE LIST COMPLETE (verified):** the module list above now includes
`java.net.http`, `jdk.crypto.ec`, `java.naming`, and `java.management`, which
the desktop app needs for OkHttp/HTTPS. Verified with a TLS smoke test: a
jlink runtime built from this exact module list performed a live HTTPS request
via `java.net.http.HttpClient` (TLS handshake succeeded, HTTP status received).
(`java.datatransfer` covers clipboard; `jdk.unsupported` covers sun.misc usage
by some libraries.) REMAINING before ANY release artifact ships: exercise the
built runtime with the real app (run the desktop app against
`build/runtime/bin/java`) to confirm no `java.lang.ModuleNotFoundException` /
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

- Release publish flow (`release.yml`): upload the tarball +
  `PACKAGE.sha256.asc` and publish the release key fingerprint. (GPG signing
  itself is implemented in `build-tarball.sh` — see §"Signing".)
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
  and the packaged artifact. Remaining gaps: exercise the jlink runtime with
  the real app (module list is now complete and TLS-verified), pin
  `binaryChecksums."linux-x86_64"` for the full core SHA/provenance match, add
  a symbol allowlist, and start the desktop bundle headlessly in CI.
- `desktop/build.gradle.kts` still carries the `TODO(phase 14)` comment on
  `nativeDistributions`; update it when Gradle-side packaging is wired up.
- Finalize `share/licenses/rommulus/third_party_license_metadata`: it now lists
  the desktop module's verifiable DIRECT dependencies (the file previously
  listed the Android app's dependency tree by mistake), but the full transitive
  closure — Compose Multiplatform runtime jars, Skiko natives, JNR FFI stack,
  okio, Kotlin stdlib versions — must be enumerated from a Gradle
  `:desktop` runtimeClasspath report, with license texts added to
  `third_party_licenses`, BEFORE RELEASE.

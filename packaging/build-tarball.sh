#!/usr/bin/env bash
# ============================================================================
# RomMulus — assemble rommulus-<version>-linux-x86_64.tar.zst
# (plans/LINUX_X64.md §16.1 "Initial artifact")
#
# LINUX ONLY (Ubuntu 24.04 dev box / CI). Requires: GNU tar (>=1.28 for
# --sort=name), coreutils (sha256sum, install), zstd. Does not run on macOS.
#
# Inputs (all overridable via environment):
#   PLAYER_BUILD_DIR  build/player            CMake player build tree; must
#                                             contain rommulus_player and the
#                                             core shared libraries (lib*_core.so)
#   APP_JAR           desktop/build/libs/desktop.jar
#                     Compose Multiplatform desktop application jar
#                     (./gradlew :desktop:jar)
#   RUNTIME_DIR       build/runtime           jlink JVM image with bin/java
#                                             (see packaging/README.md §"JVM runtime")
#
# Usage:
#   packaging/build-tarball.sh [--sign <keyid|fingerprint>] [VERSION]
#       VERSION defaults to 0.1.0
#   OUT_DIR=dist ROMMULUS_VERSION=0.1.0 packaging/build-tarball.sh
#
# Optional GPG signature (§16 work item 4, "Add checksums and signature"):
#   Set ROMMULUS_SIGN_KEY=<keyid|fingerprint> or pass --sign <keyid>. The
#   build then signs share/rommulus/PACKAGE.sha256 with
#     gpg --detach-sign --armor --local-user <key> -o PACKAGE.sha256.asc
#   and ships the .asc inside the tarball. FAIL-CLOSED: if a key is set but
#   gpg is missing or cannot resolve it in the local keyring, the build fails
#   — an unsigned artifact is never produced when signing was requested. With
#   no key configured the tarball is unsigned and a note is logged (dev
#   builds are intentionally unsigned). Note: a PGP signature embeds a
#   creation timestamp, so signed artifacts are NOT byte-for-byte
#   reproducible (unsigned builds remain deterministic). The launcher never
#   checks the signature at runtime — see packaging/README.md §"Signing".
#
# Output:
#   $OUT_DIR/rommulus-<version>-linux-x86_64.tar.zst  (deterministic tar)
# ============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"

die() { printf 'build-tarball: error: %s\n' "$*" >&2; exit 1; }

# Flags before the optional positional VERSION (e.g. --sign <keyid>).
SIGN_KEY=""
while [ $# -gt 0 ]; do
  case "$1" in
    --sign)   [ $# -ge 2 ] || die "--sign requires a key id or fingerprint argument"; SIGN_KEY="$2"; shift 2 ;;
    --sign=*) SIGN_KEY="${1#--sign=}"; shift ;;
    *)        break ;;
  esac
done

VERSION="${1:-${ROMMULUS_VERSION:-0.1.0}}"
SIGN_KEY="${SIGN_KEY:-${ROMMULUS_SIGN_KEY:-}}"
PLAYER_BUILD_DIR="${PLAYER_BUILD_DIR:-$REPO_ROOT/build/player}"
APP_JAR="${APP_JAR:-$REPO_ROOT/desktop/build/libs/desktop.jar}"
RUNTIME_DIR="${RUNTIME_DIR:-$REPO_ROOT/build/runtime}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dist}"

command -v zstd >/dev/null 2>&1 || die "zstd is required (apt-get install zstd)"
[ -x "$PLAYER_BUILD_DIR/rommulus_player" ] \
  || die "player binary not found: $PLAYER_BUILD_DIR/rommulus_player — run 'cmake -S native/player -B build/player && cmake --build build/player' first"
[ -f "$APP_JAR" ] \
  || die "desktop app jar not found: $APP_JAR — run './gradlew :desktop:jar' first (override with APP_JAR=...)"
[ -x "$RUNTIME_DIR/bin/java" ] \
  || die "jlink JVM runtime not found at $RUNTIME_DIR (needs bin/java) — build the jlink image per packaging/README.md, or override with RUNTIME_DIR=..."

# Validate the core manifest before it ships.
if command -v python3 >/dev/null 2>&1; then
  python3 -m json.tool "$SCRIPT_DIR/share/rommulus/core-manifest.json" >/dev/null \
    || die "share/rommulus/core-manifest.json is not valid JSON"
fi

STAGE="$(mktemp -d "${TMPDIR:-/tmp}/rommulus-stage.XXXXXX")"
trap 'rm -rf "$STAGE"' EXIT

mkdir -p "$STAGE"/bin "$STAGE"/lib/runtime "$STAGE"/lib/rommulus/cores "$STAGE"/share

# --- bin/ ------------------------------------------------------------------
install -m 0755 "$SCRIPT_DIR/bin/rommulus" "$STAGE/bin/rommulus"
# Steam's Non-Steam Game picker recognizes shell launchers by extension.
ln -s rommulus "$STAGE/bin/rommulus.sh"
install -m 0755 "$PLAYER_BUILD_DIR/rommulus_player" "$STAGE/bin/rommulus-player"
# Compatibility shim: the desktop app resolves the player as "rommulus_player"
# (underscore) via PATH; the canonical tarball name is "rommulus-player".
ln -s rommulus-player "$STAGE/bin/rommulus_player"

# --- lib/runtime/ (jlink JVM image) -----------------------------------------
cp -a "$RUNTIME_DIR"/. "$STAGE/lib/runtime/"

# --- lib/rommulus/: app jar + player runtime libs; cores in cores/ ----------
install -m 0644 "$APP_JAR" "$STAGE/lib/rommulus/app.jar"
core_count=0
for f in "$PLAYER_BUILD_DIR"/lib*.so; do
  [ -f "$f" ] || continue
  case "$(basename -- "$f")" in
    lib*_core.so) install -m 0644 "$f" "$STAGE/lib/rommulus/cores/"; core_count=$((core_count + 1)) ;;
    *)            install -m 0644 "$f" "$STAGE/lib/rommulus/" ;;
  esac
done
[ "$core_count" -gt 0 ] \
  || die "no core shared libraries (lib*_core.so) found in $PLAYER_BUILD_DIR — build the player with cores enabled"

# --- share/ (desktop entry, icons, licenses, core manifest) ------------------
cp -a "$SCRIPT_DIR/share"/. "$STAGE/share/"
install -m 0644 \
  "$REPO_ROOT/third_party/cores/mupen64plus_next/mupen64plus-core/data/font.ttf" \
  "$STAGE/share/rommulus/font.ttf"
mkdir -p "$STAGE/share/rommulus/controllers"
install -m 0644 "$REPO_ROOT"/assets/controllers/*.png \
  "$STAGE/share/rommulus/controllers/"

# --- Checksum manifest over every shipped file (itself excluded) -------------
( cd "$STAGE" \
  && find . -type f ! -path './share/rommulus/PACKAGE.sha256' -print0 \
    | LC_ALL=C sort -z \
    | xargs -0 sha256sum \
    | sed 's|  \./|  |' ) > "$STAGE/share/rommulus/PACKAGE.sha256"

# --- Optional GPG signature over PACKAGE.sha256 (§16 work item 4) ------------
# Signs the checksum manifest, not the tarball bytes: the .asc is written into
# the stage AFTER the manifest exists, so it ships in the tarball but — like
# PACKAGE.sha256 itself — is not listed in the manifest. The launcher never
# checks it at runtime (it verifies checksums); users/inspectors verify with
# `gpg --verify PACKAGE.sha256.asc PACKAGE.sha256`.
if [ -n "$SIGN_KEY" ]; then
  command -v gpg >/dev/null 2>&1 \
    || die "ROMMULUS_SIGN_KEY is set but gpg is not installed — install gpg, or unset ROMMULUS_SIGN_KEY / drop --sign to build unsigned"
  gpg --batch --list-keys --with-colons "$SIGN_KEY" >/dev/null 2>&1 \
    || die "signing key '$SIGN_KEY' not found in the local GPG keyring (check 'gpg --list-keys') — refusing to ship an unsigned artifact when signing was requested"
  gpg --batch --yes --detach-sign --armor \
      --local-user "$SIGN_KEY" \
      -o "$STAGE/share/rommulus/PACKAGE.sha256.asc" \
      "$STAGE/share/rommulus/PACKAGE.sha256" \
    || die "gpg detach-sign failed for key '$SIGN_KEY'"
  echo "✓ signed share/rommulus/PACKAGE.sha256 with key $SIGN_KEY (PACKAGE.sha256.asc ships in the tarball)"
else
  echo "note: no signing key configured (ROMMULUS_SIGN_KEY / --sign) — artifact is UNSIGNED"
fi

# --- Release assertions (plans/LINUX_X64.md §15) -----------------------------
# Symlinks conventionally report mode 0777, but their mode is ignored by
# Linux. Only regular files can violate the package write-permission policy.
ww="$(find "$STAGE" -type f -perm -o+w -print)"
[ -z "$ww" ] || die "world-writable files found in package: $ww"
echo "=== Executable inventory (review for undeclared executables) ==="
find "$STAGE" -type f -perm -u+x | sed "s|^$STAGE/||" | LC_ALL=C sort

# --- Deterministic tar.zst ---------------------------------------------------
# zstd is pinned to a single thread (-T1): with -T0 the thread count depends on
# the host's core count, and zstd's multi-threaded frame format encodes the job
# split, so output bytes would differ across hosts. -T1 keeps the artifact
# byte-for-byte reproducible (slower compression is acceptable for packaging).
mkdir -p "$OUT_DIR"
ARTIFACT="$OUT_DIR/rommulus-$VERSION-linux-x86_64.tar.zst"
tar --sort=name --owner=0 --group=0 --numeric-owner --mtime='@0' \
    -C "$STAGE" -cf - . | zstd -q -T1 > "$ARTIFACT"

echo "✓ $ARTIFACT ($(du -h "$ARTIFACT" | cut -f1))"
sha256sum "$ARTIFACT"

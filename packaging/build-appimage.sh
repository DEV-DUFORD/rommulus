#!/usr/bin/env bash
set -euo pipefail

die() { printf 'build-appimage: error: %s\n' "$*" >&2; exit 1; }

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd -P)"
VERSION="${1:-${ROMMULUS_VERSION:-0.1.0}}"
TARBALL="${TARBALL:-$REPO_ROOT/dist/rommulus-$VERSION-linux-x86_64.tar.zst}"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dist}"
APPIMAGETOOL="${APPIMAGETOOL:-appimagetool}"
APPIMAGE_RUNTIME="${APPIMAGE_RUNTIME:-}"

[ -f "$TARBALL" ] || die "bundle not found: $TARBALL"
command -v "$APPIMAGETOOL" >/dev/null 2>&1 || [ -x "$APPIMAGETOOL" ] \
  || die "appimagetool not found: $APPIMAGETOOL"

APPDIR="$(mktemp -d "${TMPDIR:-/tmp}/rommulus-appdir.XXXXXX")"
trap 'rm -rf "$APPDIR"' EXIT

tar --zstd -xf "$TARBALL" -C "$APPDIR"
ln -s bin/rommulus "$APPDIR/AppRun"

cp "$SCRIPT_DIR/share/applications/com.devduford.rommulus.desktop" \
  "$APPDIR/com.devduford.rommulus.desktop"
sed -i \
  's|^Exec=.*$|Exec=rommulus|' \
  "$APPDIR/com.devduford.rommulus.desktop"

install -m 0644 "$REPO_ROOT/desktop/src/main/resources/icons/rommulus_icon.svg" \
  "$APPDIR/com.devduford.rommulus.svg"
mkdir -p "$APPDIR/usr/share/icons/hicolor/scalable/apps"
install -m 0644 "$REPO_ROOT/desktop/src/main/resources/icons/rommulus_icon.svg" \
  "$APPDIR/usr/share/icons/hicolor/scalable/apps/com.devduford.rommulus.svg"

mkdir -p "$OUT_DIR"
OUTPUT="$OUT_DIR/rommulus-$VERSION-linux-x86_64.AppImage"
runtime_args=()
if [ -n "$APPIMAGE_RUNTIME" ]; then
  [ -f "$APPIMAGE_RUNTIME" ] || die "AppImage runtime not found: $APPIMAGE_RUNTIME"
  runtime_args=(--runtime-file "$APPIMAGE_RUNTIME")
fi
ARCH=x86_64 "$APPIMAGETOOL" --appimage-extract-and-run \
  "${runtime_args[@]}" "$APPDIR" "$OUTPUT"
chmod 0755 "$OUTPUT"

echo "✓ $OUTPUT ($(du -h "$OUTPUT" | cut -f1))"
sha256sum "$OUTPUT"

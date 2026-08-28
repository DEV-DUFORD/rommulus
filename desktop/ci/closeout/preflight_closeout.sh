#!/usr/bin/env bash
#
# preflight_closeout.sh — RomMulus Linux desktop port, Phase 6 closeout gate.
#
# Turn-key preflight / evidence runner for the manual closeout gate on
# Ubuntu 24.04 (pure bash). Runs environment checks plus an always-on
# credential-leak scan over the rommulus XDG directories, prints a delimited
# PASS/WARN/FAIL report to stdout, and mirrors it to an evidence log:
#
#   $XDG_STATE_HOME/rommulus/closeout-YYYYmmdd-HHMMSS.log
#
# Flags: --build (alias --x) also builds :desktop and launches the app under a
# 60s watchdog. No positional args. Exit 0 if no check FAILs, 1 otherwise.

set -euo pipefail

SCRIPT_DIR=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
REPO_ROOT=$(cd -- "$SCRIPT_DIR/../../.." && pwd)

BUILD=0

usage() {
  cat <<'EOF'
Usage: preflight_closeout.sh [--build] [--help]

RomMulus Linux desktop — Phase 6 closeout preflight & evidence runner.

Options:
  --build, --x   After the checks, also run (both commands are shown first):
                   ./gradlew :desktop:build --configure-on-demand
                   timeout 60 ./gradlew :desktop:run --configure-on-demand
                 Launching the app needs a DISPLAY or WAYLAND_DISPLAY.
  --help         Show this help and exit.

With no flags the script runs all preflight checks plus the always-on
security scan, then writes an evidence log to:
  $XDG_STATE_HOME/rommulus/closeout-YYYYmmdd-HHMMSS.log

Exit status: 0 if no check FAILs (WARN is acceptable), 1 otherwise.
EOF
}

for arg in "$@"; do
  case "$arg" in
    --build | --x) BUILD=1 ;;
    --help) usage; exit 0 ;;
    *) printf 'error: unknown argument: %s\n\n' "$arg" >&2; usage >&2; exit 2 ;;
  esac
done

# --- XDG dirs (resolved before the log path) -------------------------------
XDG_CONFIG_HOME=${XDG_CONFIG_HOME:-$HOME/.config}
XDG_DATA_HOME=${XDG_DATA_HOME:-$HOME/.local/share}
XDG_STATE_HOME=${XDG_STATE_HOME:-$HOME/.local/state}
XDG_CACHE_HOME=${XDG_CACHE_HOME:-$HOME/.cache}

LOG_DIR="$XDG_STATE_HOME/rommulus"
mkdir -p -- "$LOG_DIR"
LOG_FILE="$LOG_DIR/closeout-$(date +%Y%m%d-%H%M%S).log"

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

# report LINE... — print to stdout and append to the evidence log.
report() {
  local line
  for line in "$@"; do
    printf '%s\n' "$line"
    printf '%s\n' "$line" >>"$LOG_FILE"
  done
}

pass() { PASS_COUNT=$((PASS_COUNT + 1)); report "PASS: $*"; }
warn() { WARN_COUNT=$((WARN_COUNT + 1)); report "WARN: $*"; }
fail() { FAIL_COUNT=$((FAIL_COUNT + 1)); report "FAIL: $*"; }

heading() { report "" "## $1"; }

# --- Checks -----------------------------------------------------------------

check_java() {
  heading "Java"
  if ! command -v java >/dev/null 2>&1; then
    fail "java not found on PATH (JDK 17+ required)"
    return 0
  fi
  local out ver major="" a b
  out=$(java -version 2>&1) || true
  report "$out"
  ver=$(printf '%s\n' "$out" | sed -nE 's/.*version "([^"]+)".*/\1/p' | head -n 1) || true
  if [[ -n "$ver" ]]; then
    IFS=. read -r a b _ <<<"$ver"
    # Legacy versioning: "1.8.0_x" means Java 8, not 1.
    if [[ "${a:-}" == "1" && -n "${b:-}" ]]; then major=$b; else major=${a:-}; fi
  fi
  report "parsed major version: ${major:-unknown} (raw: ${ver:-unparseable})"
  if [[ -n "${JAVA_HOME:-}" ]]; then
    report "JAVA_HOME=$JAVA_HOME"
    if [[ -x "$JAVA_HOME/bin/java" ]]; then
      report "JAVA_HOME/bin/java: present and executable"
    else
      report "note: \$JAVA_HOME/bin/java not found or not executable"
    fi
  else
    report "JAVA_HOME: (not set)"
  fi
  if [[ "$major" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
    pass "java major version $major satisfies the JDK 17+ requirement"
  else
    fail "java major version '${major:-?}' does not satisfy the JDK 17+ requirement"
  fi
}

check_gradle_wrapper() {
  heading "Gradle wrapper"
  if [[ -f "$REPO_ROOT/gradlew" ]]; then
    if [[ -x "$REPO_ROOT/gradlew" ]]; then
      pass "./gradlew present at repo root ($REPO_ROOT)"
    else
      warn "./gradlew present but not executable — fix: chmod +x $REPO_ROOT/gradlew"
    fi
  else
    fail "./gradlew missing at repo root ($REPO_ROOT)"
  fi
}

check_xdg() {
  heading "XDG paths"
  report "XDG_CONFIG_HOME=$XDG_CONFIG_HOME"
  report "XDG_DATA_HOME=$XDG_DATA_HOME"
  report "XDG_STATE_HOME=$XDG_STATE_HOME"
  report "XDG_CACHE_HOME=$XDG_CACHE_HOME"
  report "rommulus dirs:"
  report "  config: $XDG_CONFIG_HOME/rommulus"
  report "  data:   $XDG_DATA_HOME/rommulus"
  report "  state:  $XDG_STATE_HOME/rommulus"
  report "  cache:  $XDG_CACHE_HOME/rommulus"
  pass "XDG paths resolved (defaults applied where unset)"
}

check_secret_service() {
  heading "Secret Service"
  local user keyring_running="unknown" rc=0 got=""
  user=${USER:-$(id -un)}
  report "user: $user"

  if command -v pgrep >/dev/null 2>&1; then
    if pgrep -f gnome-keyring >/dev/null 2>&1; then
      keyring_running="running"
    else
      keyring_running="not running"
    fi
  fi
  report "gnome-keyring-daemon: $keyring_running"
  if [[ "$keyring_running" == "not running" ]]; then
    report "hint: start the keyring daemon with:"
    report "gnome-keyring-daemon --daemonize --control"
  fi

  if ! command -v secret-tool >/dev/null 2>&1; then
    fail "secret-tool not found (install: sudo apt install libsecret-tools gnome-keyring)"
    return 0
  fi

  if [[ -z "${DBUS_SESSION_BUS_ADDRESS:-}" ]]; then
    warn "DBUS_SESSION_BUS_ADDRESS is not set — cannot reach the session Secret Service; round-trip skipped"
    return 0
  fi

  # Temporary probe item: label=rommulus-preflight, attribute probe=v1, payload
  # v1. secret-tool store reads the payload from stdin; lookup matches on the
  # attribute pair and prints the payload; forget deletes it. All values are
  # fixed literals — nothing secret is ever written to the log.
  printf '%s' "v1" | secret-tool store --label=rommulus-preflight probe v1 >/dev/null 2>&1 || rc=$?
  if [[ $rc -eq 0 ]]; then
    got=$(secret-tool lookup probe v1 2>/dev/null) || got=""
  fi
  # Best-effort cleanup of the probe item.
  secret-tool forget probe v1 >/dev/null 2>&1 || true

  if [[ $rc -eq 0 && "$got" == "v1" ]]; then
    pass "secret-tool round-trip OK (store -> lookup -> forget, label rommulus-preflight)"
  else
    fail "secret-tool round-trip failed (store rc=$rc, lookup returned '${got:-(empty)}')"
    if [[ "$keyring_running" != "running" ]]; then
      report "the keyring daemon is not running — start it with:"
      report "gnome-keyring-daemon --daemonize --control"
    else
      report "the keyring daemon is running but the probe failed — the login collection may be locked; unlock it and re-run"
    fi
  fi
}

check_input_group() {
  heading "input group"
  local groups
  groups=$(id -nG)
  report "current groups: $groups"
  if [[ " $groups " == *" input "* ]]; then
    pass "user is in the 'input' group (raw /dev/input access for controllers)"
  else
    warn "user is NOT in the 'input' group — fix: sudo usermod -aG input ${USER:-$(id -un)} (log out/in required)"
  fi
}

check_display() {
  heading "Display"
  if [[ -n "${DISPLAY:-}" ]]; then
    if [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
      pass "display available (DISPLAY=$DISPLAY, WAYLAND_DISPLAY=$WAYLAND_DISPLAY)"
    else
      pass "display available (DISPLAY=$DISPLAY)"
    fi
  elif [[ -n "${WAYLAND_DISPLAY:-}" ]]; then
    pass "display available (WAYLAND_DISPLAY=$WAYLAND_DISPLAY)"
  else
    warn "headless — controller gates cannot run, launch needs a display"
  fi
}

check_workspace() {
  heading "Workspace clean"
  if ! git -C "$REPO_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    warn "$REPO_ROOT is not a git checkout — cannot verify cleanliness"
    return 0
  fi
  local status n
  status=$(git -C "$REPO_ROOT" status --porcelain) || true
  if [[ -z "$status" ]]; then
    pass "working tree clean (git status --porcelain empty)"
  else
    n=$(printf '%s\n' "$status" | wc -l | tr -d '[:space:]')
    warn "working tree dirty ($n path(s) modified — informational, does not block closeout)"
    report "dirty entries (first 10):"
    report "$(printf '%s\n' "$status" | head -n 10 | sed 's/^/  /')"
  fi
}

check_security_scan() {
  heading "Security scan"
  local pattern='bearer|client.?token|refresh.?token|password'
  local targets=(
    "$XDG_CONFIG_HOME/rommulus"
    "$XDG_DATA_HOME/rommulus"
    "$XDG_STATE_HOME/rommulus"
  )
  # rg exits 2 on missing dirs; pre-filter to existing ones so a fresh
  # machine (no rommulus dirs yet) is not a scan error.
  local existing=() d
  for d in "${targets[@]}"; do
    if [[ -d "$d" ]]; then existing+=("$d"); fi
  done

  if [[ ${#existing[@]} -eq 0 ]]; then
    pass "no rommulus data directories exist yet — nothing to scan (zero matches)"
    return 0
  fi

  local tool="rg" out=""
  if command -v rg >/dev/null 2>&1; then
    # || true only here: rg exits 1 on no matches and may exit 2 on races;
    # both are handled by inspecting the output below.
    out=$(rg -n -i "$pattern" "${existing[@]}" --glob '!**/.so' --glob '!**/cache/**' 2>/dev/null) || true
  else
    tool="grep (fallback — rg not found)"
    out=$(grep -rniE "$pattern" --exclude='*.so' --exclude-dir=cache "${existing[@]}" 2>/dev/null) || true
  fi

  report "scanner: $tool"
  report "scanned: ${existing[*]}"

  if [[ -z "$out" ]]; then
    pass "zero matches for credential-like strings (bearer|client.?token|refresh.?token|password)"
  else
    local n
    n=$(printf '%s\n' "$out" | wc -l | tr -d '[:space:]')
    fail "$n match(es) for credential-like strings under rommulus dirs — inspect before closeout:"
    report "$(printf '%s\n' "$out" | head -n 100 | sed 's/^/  /')"
    if (( n > 100 )); then
      report "  ... ($((n - 100)) more match(es) truncated)"
    fi
  fi
}

run_build_and_launch() {
  heading "Build & launch (--build)"
  report "commands to run:"
  report "  \$ ./gradlew :desktop:build --configure-on-demand"
  report "  \$ timeout 60 ./gradlew :desktop:run --configure-on-demand"
  report ""

  if [[ ! -x "$REPO_ROOT/gradlew" ]]; then
    fail "cannot build: $REPO_ROOT/gradlew missing or not executable"
    return 0
  fi

  cd -- "$REPO_ROOT"

  local rc=0
  report "\$ ./gradlew :desktop:build --configure-on-demand"
  ./gradlew :desktop:build --configure-on-demand 2>&1 | tee -a "$LOG_FILE" || rc=$?
  report "exit code: $rc"
  if [[ $rc -eq 0 ]]; then
    pass ":desktop:build succeeded"
  else
    fail ":desktop:build failed (exit $rc)"
    report "skipping :desktop:run because the build failed"
    return 0
  fi

  local rrc=0
  report ""
  report "\$ timeout 60 ./gradlew :desktop:run --configure-on-demand"
  timeout 60 ./gradlew :desktop:run --configure-on-demand 2>&1 | tee -a "$LOG_FILE" || rrc=$?
  report "exit code: $rrc"
  report "note: launching the app requires a DISPLAY or WAYLAND_DISPLAY (see Display check)"
  if [[ $rrc -eq 0 ]]; then
    pass ":desktop:run exited cleanly within the 60s window"
  elif [[ $rrc -eq 124 ]]; then
    pass ":desktop:run stayed alive for the full 60s watchdog (killed by timeout, as expected for a GUI app)"
  else
    fail ":desktop:run exited early with code $rrc — check the display and keyring prerequisites above"
  fi
}

main() {
  report "RomMulus desktop — Phase 6 closeout preflight"
  report "repo root: $REPO_ROOT"
  report "host user: ${USER:-$(id -un)} @ $(hostname 2>/dev/null || echo unknown)"
  report "started:   $(date '+%Y-%m-%d %H:%M:%S %Z')"

  check_java
  check_gradle_wrapper
  check_xdg
  check_secret_service
  check_input_group
  check_display
  check_workspace
  check_security_scan

  if [[ $BUILD -eq 1 ]]; then
    run_build_and_launch
  fi

  report ""
  report "evidence log: $LOG_FILE"
  report "$PASS_COUNT passed, $WARN_COUNT warnings, $FAIL_COUNT failed"

  if [[ $FAIL_COUNT -gt 0 ]]; then
    exit 1
  fi
  exit 0
}

main

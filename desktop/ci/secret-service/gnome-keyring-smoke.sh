#!/usr/bin/env bash
# REAL-daemon smoke test (Ubuntu 24.04): runs the conformance suite against an actual
# gnome-keyring secrets daemon inside a private D-Bus session, then locks the collection
# and verifies the backend fails closed.
#
# Steps (each phase gets its own `dbus-run-session`):
#   1. Start and unlock `gnome-keyring-daemon --components=secrets`.
#   2. Create the "default" collection manually via gdbus (documents the raw API).
#   3. In the available phase, run set/get/overwrite/delete/clearAll against the
#      real daemon.
#   4. In a fresh locked phase, create an item and lock the collection via the
#      Secret Service.
#   5. Show SearchItems now returns nothing (metadata hidden while locked).
#   6. Run the locked conformance test -> state()==Locked,
#      store() false, retrieve() null against the REAL daemon.
#
# NOTE: Unlock is UI-gated — gnome-keyring shows a password prompt (polkit/seahorse) that
# cannot be answered headless. We deliberately do NOT unlock; the backend's contract is to
# fail closed on a locked keyring instead of blocking on a host-side dialog.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

dbus_method() { # dbus_method <object-path> <method> [args...]
    gdbus call --session \
        --dest org.freedesktop.secrets \
        --object-path "$1" \
        --method "$2" "${@:3}"
}

# --- inner phase: runs INSIDE dbus-run-session ---------------------------------
if [[ "${1:-}" == "--inner-available" || "${1:-}" == "--inner-locked" ]]; then
    PHASE="${1#--inner-}"
    cd "$REPO_ROOT" # ./gradlew below must resolve from the repo root regardless of caller CWD
    KEYRING_HOME="$(mktemp -d)"
    export HOME="$KEYRING_HOME"
    export XDG_DATA_HOME="$KEYRING_HOME/.local/share"
    mkdir -p "$XDG_DATA_HOME"
    # Mirror PAM startup: --login captures the password and creates the
    # control socket; --start completes initialization in the same process.
    # A single --foreground --unlock invocation can exit between D-Bus calls.
    eval "$(printf '%s' "rommulus-ci-keyring" |
        gnome-keyring-daemon --components=secrets --login)"
    eval "$(gnome-keyring-daemon --components=secrets --start)"
    trap '[[ -n "${GNOME_KEYRING_PID:-}" ]] && kill "$GNOME_KEYRING_PID" 2>/dev/null || true; rm -rf "$KEYRING_HOME"' EXIT

    # Wait (<=15s) for the daemon to own org.freedesktop.secrets.
    for _ in $(seq 1 75); do
        if python3 -c '
import dbus, sys
bus = dbus.SessionBus()
m = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
sys.exit(0 if m.NameHasOwner("org.freedesktop.secrets") else 1)'; then
            break
        fi
        sleep 0.2
    done
    python3 -c '
import dbus, sys
bus = dbus.SessionBus()
m = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
sys.exit(0 if m.NameHasOwner("org.freedesktop.secrets") else 1)' \
        || { echo "FAIL: gnome-keyring-daemon did not own the secrets name" >&2; exit 1; }
    echo "smoke: daemon owns org.freedesktop.secrets"

    # Create the collection manually (idempotent) — same call the backend makes.
    ALIAS_PATH="$(dbus_method /org/freedesktop/secrets \
        org.freedesktop.Secret.Service.ReadAlias "default" |
        sed -n "s/.*objectpath '\([^']*\)'.*/\1/p")"
    if [[ -z "$ALIAS_PATH" || "$ALIAS_PATH" == "/" ]]; then
        dbus_method /org/freedesktop/secrets \
            org.freedesktop.Secret.Service.CreateCollection \
            "{'org.freedesktop.Secret.Collection.Label': <'rommulus-smoke'>}" "default"
        ALIAS_PATH="$(dbus_method /org/freedesktop/secrets \
            org.freedesktop.Secret.Service.ReadAlias "default" |
            sed -n "s/.*objectpath '\([^']*\)'.*/\1/p")"
    fi
    [[ -n "$ALIAS_PATH" && "$ALIAS_PATH" != "/" ]] \
        || { echo "FAIL: default collection was not created without a prompt" >&2; exit 1; }
    echo "smoke: collection at $ALIAS_PATH"

    if [[ "$PHASE" == "available" ]]; then
        ROM_SECRET_MODE=available ./gradlew --no-daemon --configure-on-demand :desktop:test \
            --tests '*SecretServiceDbusBackendConformanceTest*'
        echo "smoke: PASS (real gnome-keyring, available)" >&2
        exit 0
    fi

    # Store one item directly so the post-lock metadata check is meaningful.
    ALIAS_PATH="$ALIAS_PATH" python3 - <<'EOF'
import os, dbus
bus = dbus.SessionBus()
service = dbus.Interface(
    bus.get_object("org.freedesktop.secrets", "/org/freedesktop/secrets"),
    "org.freedesktop.Secret.Service")
_, session = service.OpenSession("plain", dbus.String(""), signature="sv")
collection = dbus.Interface(
    bus.get_object("org.freedesktop.secrets", os.environ["ALIAS_PATH"]),
    "org.freedesktop.Secret.Collection")
item, _ = collection.CreateItem(
    {"org.freedesktop.Secret.Item.Label": dbus.String("smoke"),
     "org.freedesktop.Secret.Item.Attributes": dbus.Dictionary(
         {"application": "rommulus", "scope": "smoke"}, signature="ss")},
    (session, b"", b"smoke-secret", "text/plain"),
    True,
    signature="a{sv}(oayays)b")
print(f"smoke: stored item {item}", flush=True)
EOF

    # Lock the collection.
    dbus_method /org/freedesktop/secrets org.freedesktop.Secret.Service.Lock \
        "[objectpath '$ALIAS_PATH']"
    echo "smoke: collection locked"

    # The service must classify the item as locked, never unlocked.
    python3 - <<'EOF'
import dbus
bus = dbus.SessionBus()
service = dbus.Interface(
    bus.get_object("org.freedesktop.secrets", "/org/freedesktop/secrets"),
    "org.freedesktop.Secret.Service")
unlocked, locked = service.SearchItems({"application": "rommulus"})
if unlocked or not locked:
    raise SystemExit(f"FAIL: expected only locked items, got unlocked={unlocked}, locked={locked}")
print(f"smoke: locked SearchItems -> unlocked={list(unlocked)}, locked={list(locked)}", flush=True)
EOF

    # Fail-closed conformance against the real locked daemon.
    ROM_SECRET_MODE=locked ./gradlew --no-daemon --configure-on-demand :desktop:test \
        --tests '*SecretServiceDbusBackendConformanceTest*'

    echo "smoke: PASS (real gnome-keyring, locked)" >&2
    exit 0
fi

# --- outer phase ---------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
MISSING=0
for pkg in gnome-keyring dbus libglib2.0-bin python3-dbus; do
    dpkg -s "$pkg" >/dev/null 2>&1 || MISSING=1
done
if [[ "$MISSING" == "1" ]]; then
    echo "Installing gnome-keyring dbus libglib2.0-bin python3-dbus ..." >&2
    if command -v sudo >/dev/null 2>&1; then
        SUDO=sudo
    elif [[ "$(id -u)" == "0" ]]; then
        SUDO=""
    else
        echo "ERROR: missing packages and no sudo/root available." >&2; exit 1
    fi
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq gnome-keyring dbus libglib2.0-bin python3-dbus
fi

cd "$REPO_ROOT"
dbus-run-session -- "$SCRIPT_DIR/gnome-keyring-smoke.sh" --inner-available
dbus-run-session -- "$SCRIPT_DIR/gnome-keyring-smoke.sh" --inner-locked

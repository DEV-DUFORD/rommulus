#!/usr/bin/env bash
# REAL-daemon smoke test (Ubuntu 24.04): runs the conformance suite against an actual
# gnome-keyring secrets daemon inside a private D-Bus session, then locks the collection
# and verifies the backend fails closed.
#
# Steps (inside `dbus-run-session`):
#   1. Start `gnome-keyring-daemon --components=secrets --daemonize`.
#   2. Create the "default" collection manually via gdbus (documents the raw API).
#   3. Run the conformance test with ROM_SECRET_MODE=available -> set/get/overwrite/
#      delete/clearAll against the REAL daemon.
#   4. Lock the collection via gdbus (`org.freedesktop.Secret.Collection.Lock`).
#   5. Show SearchItems now returns nothing (metadata hidden while locked).
#   6. Run the conformance test with ROM_SECRET_MODE=locked -> state()==Locked,
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
if [[ "${1:-}" == "--inner" ]]; then
    cd "$REPO_ROOT" # ./gradlew below must resolve from the repo root regardless of caller CWD
    gnome-keyring-daemon --components=secrets --daemonize 2>/dev/null || true
    trap 'pkill -f gnome-keyring-daemon 2>/dev/null || true' EXIT

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
        org.freedesktop.Secret.Service.ReadAlias "default" | sed -n "s/^\('[^']*'\).*/\1/p")"
    if [[ -z "$ALIAS_PATH" || "$ALIAS_PATH" == "/" ]]; then
        dbus_method /org/freedesktop/secrets \
            org.freedesktop.Secret.Service.CreateCollection "{Label: <s>rommulus-smoke}" "default"
        ALIAS_PATH="$(dbus_method /org/freedesktop/secrets \
            org.freedesktop.Secret.Service.ReadAlias "default" | sed -n "s/^\('[^']*'\).*/\1/p")"
    fi
    echo "smoke: collection at $ALIAS_PATH"

    # 3. Real-daemon data-path conformance (set/get/overwrite/delete/clearAll).
    ROM_SECRET_MODE=available ./gradlew --no-daemon --configure-on-demand :desktop:test \
        --tests '*SecretServiceDbusBackendConformanceTest*'

    # Store one item directly via python3-dbus so the post-lock check below is meaningful
    # (the available run ends with clearAll, leaving the collection empty).
    ALIAS_PATH="$ALIAS_PATH" python3 - <<'EOF'
import os, dbus
bus = dbus.SessionBus()
svc = bus.get_object("org.freedesktop.secrets", "/org/freedesktop/secrets")
_, session = svc.OpenSession("plain", dbus.String(""), signature="sv")
coll = bus.get_object("org.freedesktop.secrets", os.environ["ALIAS_PATH"])
item, prompt = coll.CreateItem(
    {dbus.String("org.freedesktop.Secret.Item.Label"): dbus.String("smoke"),
     dbus.String("org.freedesktop.Secret.Item.Attributes"): dbus.Dictionary(
         {dbus.String("application"): dbus.String("rommulus"),
          dbus.String("scope"): dbus.String("smoke")}, signature="ss")},
    (session, b"", b"smoke-secret", "text/plain"),
    True,
    signature="a{sv}(oayays)b")
print(f"smoke: stored item {item}", flush=True)
EOF

    # 4. Lock the collection.
    dbus_method "$ALIAS_PATH" org.freedesktop.Secret.Collection.Lock
    echo "smoke: collection locked"

    # 5. Metadata is hidden while locked: SearchItems must return ([], []).
    HIDDEN="$(dbus_method "$ALIAS_PATH" org.freedesktop.Secret.Collection.SearchItems \
        "{application: <s>rommulus}")"
    echo "smoke: locked SearchItems -> $HIDDEN"
    [[ "$HIDDEN" == *"([], [])"* ]] || { echo "FAIL: expected ([], []) while locked" >&2; exit 1; }

    # 6. Fail-closed conformance against the REAL locked daemon.
    ROM_SECRET_MODE=locked ./gradlew --no-daemon --configure-on-demand :desktop:test \
        --tests '*SecretServiceDbusBackendConformanceTest*'

    echo "smoke: PASS (real gnome-keyring, unlocked + locked)" >&2
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
dbus-run-session -- "$SCRIPT_DIR/gnome-keyring-smoke.sh" --inner

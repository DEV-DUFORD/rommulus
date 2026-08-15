#!/usr/bin/env bash
# Conformance runner for the Linux Secret Service spike (Ubuntu 24.04).
#
# Launches mock_secret_service.py under `dbus-run-session` (so the mock owns a private
# session bus), then runs the REAL SecretServiceDbusBackend conformance test against it.
# The test JVM picks up DBUS_SESSION_BUS_ADDRESS from the environment (already wired in
# desktop/build.gradle.kts as rommulus.secretServiceBus / rommulus.secretServiceMode).
#
# Runs twice: ROM_SECRET_MODE=available (data-path tests) and ROM_SECRET_MODE=locked
# (fail-closed test). Both must pass; the script exits non-zero on any failure.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
MOCK="$SCRIPT_DIR/mock_secret_service.py"
RESULTS_XML="$REPO_ROOT/desktop/build/test-results/test/TEST-com.romm.desktop.storage.secret.dbus.SecretServiceDbusBackendConformanceTest.xml"

# --- inner phase: runs INSIDE dbus-run-session ---------------------------------
if [[ "${1:-}" == "--inner" ]]; then
    python3 "$MOCK" &
    MOCK_PID=$!
    trap 'kill "$MOCK_PID" 2>/dev/null || true' EXIT

    if [[ "$ROM_SECRET_MODE" != "unavailable" ]]; then
        # Wait (<=10s) until the mock owns org.freedesktop.secrets.
        for _ in $(seq 1 50); do
            if python3 -c '
import dbus, sys
bus = dbus.SessionBus()
m = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
sys.exit(0 if m.NameHasOwner("org.freedesktop.secrets") else 1)'; then
                break
            fi
            sleep 0.2
        done
    else
        # unavailable: the mock must NOT own the name. Give it a moment to exit, then verify.
        sleep 1
        if python3 -c '
import dbus, sys
bus = dbus.SessionBus()
m = bus.get_object("org.freedesktop.DBus", "/org/freedesktop/DBus")
sys.exit(0 if not m.NameHasOwner("org.freedesktop.secrets") else 1)'; then
            echo "inner: OK — name unowned as expected" >&2
        else
            echo "inner: FAIL — mock owns the name in unavailable mode" >&2
            exit 1
        fi
    fi

    # --no-daemon on purpose: a reused Gradle daemon captured its environment at startup
    # and would NOT see DBUS_SESSION_BUS_ADDRESS / ROM_SECRET_MODE exported here.
    (cd "$REPO_ROOT" && ./gradlew --no-daemon :desktop:test \
        --tests '*SecretServiceDbusBackendConformanceTest*')

    # Assert the run actually executed at least one test (not all skipped) and had 0 failures.
    python3 - "$RESULTS_XML" <<'EOF'
import sys, xml.etree.ElementTree as ET
tree = ET.parse(sys.argv[1])
root = tree.getroot()
tests = int(root.get("tests", "0"))
skipped = int(root.get("skipped", "0"))
failures = int(root.get("failures", "0"))
errors = int(root.get("errors", "0"))
print(f"results: tests={tests} skipped={skipped} failures={failures} errors={errors}")
assert failures == 0 and errors == 0, "conformance test reported failures/errors"
assert tests - skipped >= 1, f"no conformance test actually ran (all {tests} skipped)"
EOF
    echo "inner: PASS (ROM_SECRET_MODE=$ROM_SECRET_MODE)" >&2
    exit 0
fi

# --- outer phase ---------------------------------------------------------------
command -v java >/dev/null 2>&1 || {
    echo "ERROR: no JDK on PATH; install openjdk-17-jdk first." >&2; exit 1; }

export DEBIAN_FRONTEND=noninteractive
MISSING=0
for pkg in python3-dbus python3-gi dbus; do
    dpkg -s "$pkg" >/dev/null 2>&1 || MISSING=1
done
if [[ "$MISSING" == "1" ]]; then
    echo "Installing python3-dbus python3-gi dbus ..." >&2
    if command -v sudo >/dev/null 2>&1; then
        SUDO=sudo
    elif [[ "$(id -u)" == "0" ]]; then
        SUDO=""
    else
        echo "ERROR: missing packages and no sudo/root available." >&2; exit 1
    fi
    $SUDO apt-get update -qq
    $SUDO apt-get install -y -qq python3-dbus python3-gi dbus
fi

for MODE in available locked; do
    echo "=== conformance run: ROM_SECRET_MODE=$MODE ===" >&2
    ROM_SECRET_MODE="$MODE" dbus-run-session -- "$BASH_SOURCE[0]" --inner
done

echo "Conformance suite passed (available + locked)." >&2

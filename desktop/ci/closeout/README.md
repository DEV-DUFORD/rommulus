# RomMulus desktop — Phase 6 closeout preflight

`preflight_closeout.sh` is a turn-key, pure-bash evidence runner for the Phase 6
closeout manual gate on Ubuntu 24.04. It checks Java 17+, the Gradle wrapper,
effective XDG paths (and the rommulus dirs under them), Secret Service
round-trip via `secret-tool`, `input` group membership, display availability,
and git cleanliness; it then always scans the rommulus config/data/state dirs
for credential-like strings. Every run prints a delimited PASS/WARN/FAIL report
and mirrors it to an evidence log:

    $XDG_STATE_HOME/rommulus/closeout-YYYYmmdd-HHMMSS.log

## Run

    ./preflight_closeout.sh           # checks + security scan only
    ./preflight_closeout.sh --build   # also builds :desktop and launches it under a 60s watchdog (--x is an alias)

Exit code is 0 unless any check FAILs. The full manual gate steps live in
`plans/PHASE6_CLOSEOUT.md`.

Fail-closed note: sign-up cannot persist without a running, unlocked keyring —
the app deliberately shows the "could not store device credentials on this
machine" banner until Secret Service round-trips succeed.

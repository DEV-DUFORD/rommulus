#!/usr/bin/env python3
"""Cross-source Linux x86_64 target agreement guard — RomMulus packaging.

Validates, against the working tree, that the authoritative sources of the Linux
desktop core inventory agree on exactly the same set of cores, and that no
approved / production core advertises the Windows x86_64 build identity:

  * native   — CoreManifest.kt approved cores advertising the linux-x86_64
                build identity (the source of truth; consumes the same
                NativeBuildIdentities constants the desktop launch code filters on).
  * package  — packaging/share/rommulus/core-manifest.json `cores` array. Every
                entry must be enabled and carry the linux-x86_64 ABI.
  * doc      — docs/linux-support-manifest.md "Core status" table.
  * approved — no approved / production core may advertise windows-x86_64
                (plans/WINDOWS_IMPL.md §3.1); the no-Windows assertion is scoped
                to approved entries only, matching the approved/linux checks.

Exit code 0 == all assertions hold; non-zero == at least one mismatch. Designed
to run from the repo root (`packaging/validate-linux-targets.py`) or any CWD, and
to be drop-in wired into `.github/workflows/linux-x64.yml` like the existing
`core provenance` python assertion.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACKAGE_MANIFEST = ROOT / "packaging" / "share" / "rommulus" / "core-manifest.json"
DOC_MANIFEST = ROOT / "docs" / "linux-support-manifest.md"
CORE_MANIFEST_KT = ROOT / "shared" / "domain" / "src" / "main" / "kotlin" / "com" / "romm" / "androidtv" / "emulation" / "model" / "CoreManifest.kt"

LINUX_SLUG = "linux-x86_64"
WINDOWS_SLUG = "windows-x86_64"
LINUX_CONST = "NativeBuildIdentities.LINUX_X86_64"
WINDOWS_CONST = "NativeBuildIdentities.WINDOWS_X86_64"

CORE_STATUS_HEADING = "## Core status"


class GuardError(Exception):
    """Raised when an assertion fails; the message is reported to the caller."""


def load_package_core_ids() -> set[str]:
    data = json.loads(PACKAGE_MANIFEST.read_text())
    cores = data.get("cores")
    if not isinstance(cores, list) or not cores:
        raise GuardError("package core-manifest.json has no 'cores' array")
    ids: set[str] = set()
    for core in cores:
        cid = core.get("coreId")
        if not cid:
            raise GuardError("package core-manifest.json entry is missing 'coreId'")
        if core.get("enabled") is not True:
            raise GuardError(
                f"package core-manifest.json entry '{cid}' is not enabled (enabled != true)"
            )
        abis = core.get("supportedAbis") or []
        if LINUX_SLUG not in abis:
            raise GuardError(
                f"package core-manifest.json entry '{cid}' does not include {LINUX_SLUG} "
                f"in supportedAbis: {abis}"
            )
        ids.add(cid)
    windows = [
        core["coreId"]
        for core in cores
        if WINDOWS_SLUG in (core.get("supportedAbis") or [])
    ]
    if windows:
        raise GuardError(
            f"package core-manifest.json advertises windows-x86_64 for: {sorted(windows)}"
        )
    return ids


def load_doc_core_ids() -> set[str]:
    text = DOC_MANIFEST.read_text()
    if CORE_STATUS_HEADING not in text:
        raise GuardError(
            f"doc linux-support-manifest.md is missing the {CORE_STATUS_HEADING!r} heading"
        )
    # The "## Core status" table lists cores one-per-row as `` `slug` ``; the
    # section ends at the next top-level heading.
    status_section = text.split(CORE_STATUS_HEADING, 1)[1].split("## ", 1)[0]
    ids: set[str] = set()
    for line in status_section.splitlines():
        if line.lstrip().startswith("| ---"):
            continue
        match = re.match(r"^\s*\|\s*`([^`]+)`\s*\|", line)
        if match:
            ids.add(match.group(1))
    if not ids:
        raise GuardError("doc linux-support-manifest.md Core status table parsed no cores")
    return ids


def _core_blocks(kotlin_text: str) -> list[tuple[str, str]]:
    """Yield (coreId, block_text) for each CoreLicenseFinding(...) entry.

    A block spans one CoreLicenseFinding( constructor call to the next, so every
    field of the entry (coreId, approved, supportedAbis, ...) is contained in
    exactly one block regardless of field order or surrounding prose. Segments
    without a ``coreId = "..."`` (e.g. the data-class definition) are skipped.
    """
    blocks: list[tuple[str, str]] = []
    starts = [m.start() for m in re.finditer(r"CoreLicenseFinding\(", kotlin_text)]
    for i, start in enumerate(starts):
        end = starts[i + 1] if i + 1 < len(starts) else len(kotlin_text)
        segment = kotlin_text[start:end]
        core_id_match = re.search(r'coreId\s*=\s*"([^"]+)"', segment)
        if not core_id_match:
            continue
        blocks.append((core_id_match.group(1), segment))
    return blocks


def load_kotlin_targets() -> dict[str, object]:
    kotlin_text = CORE_MANIFEST_KT.read_text()
    approved_linux: set[str] = set()
    windows_offenders: list[str] = []
    for core_id, block in _core_blocks(kotlin_text):
        approved = re.search(r"approved\s*=\s*true", block) is not None
        # No-Windows semantics apply to approved / production entries only.
        if approved and (WINDOWS_SLUG in block or WINDOWS_CONST in block):
            windows_offenders.append(core_id)
        if approved and (LINUX_SLUG in block or LINUX_CONST in block):
            approved_linux.add(core_id)
    return {
        "approved_linux": approved_linux,
        "windows_offenders": windows_offenders,
    }


def main() -> int:
    package_ids = load_package_core_ids()
    doc_ids = load_doc_core_ids()
    kotlin = load_kotlin_targets()
    approved_linux = kotlin["approved_linux"]
    windows_offenders = kotlin["windows_offenders"]

    failures: list[str] = []

    if approved_linux != package_ids:
        failures.append(
            "approved Linux targets (CoreManifest.kt) != package targets (core-manifest.json)\n"
            f"  only in CoreManifest.kt: {sorted(approved_linux - package_ids)}\n"
            f"  only in package manifest: {sorted(package_ids - approved_linux)}"
        )
    if package_ids != doc_ids:
        failures.append(
            "package targets (core-manifest.json) != doc targets (linux-support-manifest.md)\n"
            f"  only in package manifest: {sorted(package_ids - doc_ids)}\n"
            f"  only in doc table: {sorted(doc_ids - package_ids)}"
        )
    if windows_offenders:
        failures.append(
            f"approved/production core advertises windows-x86_64 (must be empty): {sorted(windows_offenders)}"
        )

    print(f"package targets : {len(package_ids)} cores")
    print(f"doc targets     : {len(doc_ids)} cores")
    print(f"approved linux  : {len(approved_linux)} cores")
    print(f"windows offenders: {len(windows_offenders)}")

    if failures:
        print("\nGUARD FAILED:")
        for message in failures:
            print(message)
        return 1

    print("\nOK: approved Linux / package / doc targets agree and no core advertises windows-x86_64")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except GuardError as exc:
        print(f"::error::Linux target agreement guard errored: {exc}", file=sys.stderr)
        sys.exit(2)
    except FileNotFoundError as exc:
        print(f"::error::required source missing: {exc}", file=sys.stderr)
        sys.exit(2)

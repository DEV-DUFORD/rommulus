#!/usr/bin/env python3
"""Cross-source desktop target agreement guard — RomMulus packaging.

Validates, against the working tree, that the authoritative sources of the Linux
desktop core inventory agree on exactly the same set of cores, and that the
separate experimental Windows inventory agrees with Kotlin and its adapters:

  * native   — CoreManifest.kt approved cores advertising the linux-x86_64
                build identity (the source of truth; consumes the same
                NativeBuildIdentities constants the desktop launch code filters on).
  * package  — packaging/share/rommulus/core-manifest.json `cores` array. Every
                entry must be enabled and carry the linux-x86_64 ABI.
  * doc      — docs/linux-support-manifest.md "Core status" table.
  * windows  — exactly thirteen approved game cores in Kotlin, the Windows
                package manifest, the enabled documentation rows, and adapter files.
                Linux packaging remains independent and must not contain Windows ABIs.

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
WINDOWS_PACKAGE_MANIFEST = ROOT / "packaging" / "share" / "rommulus" / "windows-core-manifest.json"
WINDOWS_DOC_MANIFEST = ROOT / "docs" / "windows-support-manifest.md"
WINDOWS_ADAPTER_DIR = ROOT / "native" / "cmake" / "cores"

LINUX_SLUG = "linux-x86_64"
WINDOWS_SLUG = "windows-x86_64"
LINUX_CONST = "NativeBuildIdentities.LINUX_X86_64"
WINDOWS_CONST = "NativeBuildIdentities.WINDOWS_X86_64"

CORE_STATUS_HEADING = "## Core status"
WINDOWS_CORE_IDS = {
    "gambatte", "fceumm", "prosystem", "mednafen_wswan", "stella",
    "beetle_pce_fast", "genesis_plus_gx", "mgba", "snes9x", "pcsx_rearmed",
    "handy", "mednafen_ngp", "mupen64plus_next",
}
WINDOWS_EXCLUDED_IDS = {"sameboy", "picodrive", "test_core", "dolphin", "lrps2"}


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
        if cid in ids:
            raise GuardError(f"package core-manifest.json has duplicate coreId '{cid}'")
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


def load_doc_core_ids(path: Path | None = None, *, enabled_only: bool = False) -> set[str]:
    path = path or DOC_MANIFEST
    text = path.read_text()
    if CORE_STATUS_HEADING not in text:
        raise GuardError(
            f"doc {path.name} is missing the {CORE_STATUS_HEADING!r} heading"
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
            if enabled_only and line.rstrip().rstrip("|").split("|")[-1].strip() != "yes":
                continue
            if match.group(1) in ids:
                raise GuardError(f"doc {path.name} has duplicate coreId '{match.group(1)}'")
            ids.add(match.group(1))
    if not ids:
        raise GuardError(f"doc {path.name} Core status table parsed no cores")
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
    approved_windows: set[str] = set()
    all_windows: set[str] = set()
    for core_id, block in _core_blocks(kotlin_text):
        approved = re.search(r"approved\s*=\s*true", block) is not None
        abis_match = re.search(r"supportedAbis\s*=\s*listOf\(([^)]*)\)", block)
        abis = abis_match.group(1) if abis_match else ""
        if WINDOWS_SLUG in abis or WINDOWS_CONST in abis:
            all_windows.add(core_id)
            if approved:
                approved_windows.add(core_id)
        if approved and (LINUX_SLUG in abis or LINUX_CONST in abis):
            approved_linux.add(core_id)
    return {
        "approved_linux": approved_linux,
        "approved_windows": approved_windows,
        "all_windows": all_windows,
    }


def _kotlin_string(block: str, field: str) -> str:
    match = re.search(rf'\b{field}\s*=\s*"([^"]*)"', block)
    return match.group(1) if match else ""


def load_windows_package_core_ids() -> set[str]:
    data = json.loads(WINDOWS_PACKAGE_MANIFEST.read_text())
    if data.get("schemaVersion") != 1 or data.get("platform") != WINDOWS_SLUG:
        raise GuardError("Windows manifest must use schemaVersion 1 and platform windows-x86_64")
    cores = data.get("cores")
    if not isinstance(cores, list) or not cores:
        raise GuardError("Windows manifest has no 'cores' array")
    blocks = dict(_core_blocks(CORE_MANIFEST_KT.read_text()))
    ids: set[str] = set()
    for core in cores:
        cid = core.get("coreId")
        if not cid or cid in ids:
            raise GuardError(f"Windows manifest missing or duplicate coreId: {cid!r}")
        ids.add(cid)
        if cid not in WINDOWS_CORE_IDS or cid in WINDOWS_EXCLUDED_IDS:
            raise GuardError(f"Windows manifest includes excluded or unexpected core: {cid}")
        if core.get("enabled") is not True:
            raise GuardError(f"Windows core '{cid}' is not enabled")
        if core.get("supportedAbis") != [WINDOWS_SLUG]:
            raise GuardError(f"Windows core '{cid}' must advertise only {WINDOWS_SLUG}")
        if core.get("coreLibraryFile") != f"{cid}_core.dll":
            raise GuardError(f"Windows core '{cid}' has noncanonical coreLibraryFile")
        adapter = f"native/cmake/cores/{cid}-windows.cmake"
        if core.get("adapterFile") != adapter:
            raise GuardError(f"Windows core '{cid}' has incorrect adapterFile")
        for suffix in ("cmake", "def"):
            path = WINDOWS_ADAPTER_DIR / f"{cid}-windows.{suffix}"
            if not path.is_file():
                raise GuardError(f"Windows core '{cid}' is missing adapter/export file: {path}")
        adapter_text = (WINDOWS_ADAPTER_DIR / f"{cid}-windows.cmake").read_text()
        adapter_text = re.sub(r"#[^\n]*", "", adapter_text)
        if not re.search(rf"\badd_library\(\s*{re.escape(cid)}_core\s+SHARED\b", adapter_text):
            raise GuardError(f"Windows adapter '{cid}' does not define its canonical shared target")
        if f"{cid}-windows.def" not in adapter_text:
            raise GuardError(f"Windows adapter '{cid}' does not reference its export file")
        block = blocks.get(cid)
        if block is None:
            raise GuardError(f"Windows core '{cid}' is missing from CoreManifest.kt")
        for field in ("coreName", "upstreamRepository", "commitSha", "releaseTag"):
            if core.get(field) != _kotlin_string(block, field):
                raise GuardError(f"Windows core '{cid}' {field} disagrees with CoreManifest.kt")
        for field in ("supportedSystems", "supportedExtensions", "requiredFirmware"):
            match = re.search(rf"\b{field}\s*=\s*listOf\(([^)]*)\)", block)
            expected = re.findall(r'"([^"]*)"', match.group(1)) if match else []
            if core.get(field) != expected:
                raise GuardError(f"Windows core '{cid}' {field} disagrees with CoreManifest.kt")
        license_info = core.get("license") or {}
        for field in ("reviewedBy", "reviewedOn", "ownerRiskAcceptedBy", "ownerRiskAcceptedOn"):
            if license_info.get(field, "") != _kotlin_string(block, field):
                raise GuardError(f"Windows core '{cid}' license.{field} disagrees with CoreManifest.kt")
        for field in ("sourceOfferSatisfied", "attributionSatisfied"):
            expected = re.search(rf"\b{field}\s*=\s*true", block) is not None
            if license_info.get(field) is not expected:
                raise GuardError(f"Windows core '{cid}' license.{field} disagrees with CoreManifest.kt")
        finding = re.search(r"commercialUseFinding\s*=\s*CommercialUseFinding\.(\w+)", block)
        if not finding or license_info.get("commercialUseFinding") != finding.group(1):
            raise GuardError(f"Windows core '{cid}' license finding disagrees with CoreManifest.kt")
    return ids


def main() -> int:
    package_ids = load_package_core_ids()
    doc_ids = load_doc_core_ids()
    kotlin = load_kotlin_targets()
    approved_linux = kotlin["approved_linux"]
    windows_ids = load_windows_package_core_ids()
    windows_doc_ids = load_doc_core_ids(WINDOWS_DOC_MANIFEST, enabled_only=True)
    approved_windows = kotlin["approved_windows"]

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
    for source, ids in (
        ("CoreManifest.kt approved Windows", approved_windows),
        ("CoreManifest.kt all Windows", kotlin["all_windows"]),
        ("Windows package", windows_ids),
        ("Windows documentation enabled", windows_doc_ids),
    ):
        if ids != WINDOWS_CORE_IDS:
            failures.append(
                f"{source} targets != required thirteen Windows game cores\n"
                f"  missing: {sorted(WINDOWS_CORE_IDS - ids)}\n"
                f"  unexpected: {sorted(ids - WINDOWS_CORE_IDS)}"
            )

    print(f"package targets : {len(package_ids)} cores")
    print(f"doc targets     : {len(doc_ids)} cores")
    print(f"approved linux  : {len(approved_linux)} cores")
    print(f"approved windows: {len(approved_windows)} cores")
    print(f"windows package : {len(windows_ids)} cores")

    if failures:
        print("\nGUARD FAILED:")
        for message in failures:
            print(message)
        return 1

    print("\nOK: Linux targets unchanged in scope; Windows Kotlin / package / doc / adapters agree")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except GuardError as exc:
        print(f"::error::Desktop target agreement guard errored: {exc}", file=sys.stderr)
        sys.exit(2)
    except FileNotFoundError as exc:
        print(f"::error::required source missing: {exc}", file=sys.stderr)
        sys.exit(2)

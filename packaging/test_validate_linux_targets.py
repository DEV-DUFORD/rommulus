#!/usr/bin/env python3
"""Targeted tests for packaging/validate-linux-targets.py.

Exercises the guard's hardened error paths against synthetic sources written to
a temp dir, plus the live-data happy path. Run with either:

    python3 packaging/test_validate_linux_targets.py
    python3 -m pytest packaging/test_validate_linux_targets.py -q
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

# Import the validator as a module so its load_* helpers can be exercised
# directly (its __main__ guard prevents main() from running on import).
_VALIDATOR_PATH = Path(__file__).resolve().parent / "validate-linux-targets.py"
_spec = importlib.util.spec_from_file_location("validate_linux_targets", _VALIDATOR_PATH)
v = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v)

# Snapshot the real working-tree paths so the live-data test can run regardless
# of test order (patching tests reassign these module globals).
_ORIG_PATHS = (v.PACKAGE_MANIFEST, v.DOC_MANIFEST, v.CORE_MANIFEST_KT)


def _restore_original_paths() -> None:
    v.PACKAGE_MANIFEST, v.DOC_MANIFEST, v.CORE_MANIFEST_KT = _ORIG_PATHS


LINUX_SLUG = "linux-x86_64"
WINDOWS_SLUG = "windows-x86_64"


def _write_manifest(tmp: Path, cores: list[dict]) -> None:
    (tmp / "core-manifest.json").write_text(
        json.dumps({"schemaVersion": 1, "cores": cores})
    )


def _write_doc(tmp: Path, body: str) -> None:
    (tmp / "linux-support-manifest.md").write_text(body)


def _write_kotlin(tmp: Path, body: str) -> None:
    (tmp / "CoreManifest.kt").write_text(body)


def _patch(tmp: Path) -> None:
    v.PACKAGE_MANIFEST = tmp / "core-manifest.json"
    v.DOC_MANIFEST = tmp / "linux-support-manifest.md"
    v.CORE_MANIFEST_KT = tmp / "CoreManifest.kt"


def _enabled_core(cid: str, *, windows: bool = False, abis: list[str] | None = None) -> dict:
    return {
        "coreId": cid,
        "enabled": True,
        "supportedAbis": ([WINDOWS_SLUG] if windows else [LINUX_SLUG]) if abis is None else abis,
    }


class TestDataHappyPath(unittest.TestCase):
    def test_live_repo_agreement(self) -> None:
        # Restore the real working-tree paths, then run against live data.
        _restore_original_paths()
        self.assertEqual(v.load_package_core_ids(), v.load_doc_core_ids())
        kotlin = v.load_kotlin_targets()
        self.assertEqual(kotlin["approved_linux"], v.load_package_core_ids())
        self.assertEqual(kotlin["windows_offenders"], [])
        self.assertEqual(v.main(), 0)


class PackageAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        _patch(self.tmp)

    def test_all_enabled_linux_passes(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a"), _enabled_core("b")])
        _write_doc(self.tmp, "## Core status\n\n| Core | T | G | E |\n| --- | --- | --- | --- |\n| `a` | x | y | z | yes |\n| `b` | x | y | z | yes |\n")
        _write_kotlin(self.tmp, 'CoreLicenseFinding(coreId = "a", approved = true, supportedAbis = listOf("linux-x86_64"))\nCoreLicenseFinding(coreId = "b", approved = true, supportedAbis = listOf("linux-x86_64"))\n')
        self.assertEqual(v.load_package_core_ids(), {"a", "b"})

    def test_disabled_entry_raises(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a"), {**_enabled_core("b"), "enabled": False}])
        with self.assertRaises(v.GuardError) as ctx:
            v.load_package_core_ids()
        self.assertIn("not enabled", str(ctx.exception))

    def test_missing_linux_abis_raises(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a", abis=["arm64-v8a"])])
        with self.assertRaises(v.GuardError) as ctx:
            v.load_package_core_ids()
        self.assertIn(LINUX_SLUG, str(ctx.exception))

    def test_windows_package_entry_raises(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a", windows=True)])
        with self.assertRaises(v.GuardError) as ctx:
            v.load_package_core_ids()
        self.assertIn(WINDOWS_SLUG, str(ctx.exception))


class DocAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        _patch(self.tmp)

    def test_missing_heading_raises_controlled(self) -> None:
        _write_doc(self.tmp, "# Title\n\nno core status table here\n")
        with self.assertRaises(v.GuardError) as ctx:
            v.load_doc_core_ids()
        self.assertIn("Core status", str(ctx.exception))

    def test_empty_table_raises_controlled(self) -> None:
        _write_doc(self.tmp, "## Core status\n\nnothing to see\n")
        with self.assertRaises(v.GuardError) as ctx:
            v.load_doc_core_ids()
        self.assertIn("parsed no cores", str(ctx.exception))


class KotlinAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        _patch(self.tmp)

    def test_windows_only_flagged_when_approved(self) -> None:
        # An unapproved core advertising windows is NOT an offender; an approved
        # one IS. Block delimiting must stay correct across both.
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(coreId = "unappr", approved = false, supportedAbis = listOf("windows-x86_64"))\n'
            'CoreLicenseFinding(coreId = "appr", approved = true, supportedAbis = listOf("windows-x86_64"))\n'
        ))
        kotlin = v.load_kotlin_targets()
        self.assertEqual(kotlin["windows_offenders"], ["appr"])
        self.assertEqual(kotlin["approved_linux"], set())

    def test_block_delimiting_independent_of_field_order(self) -> None:
        # supportedAbis precedes coreId in this entry; the block must still be
        # attributed to the right coreId.
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(supportedAbis = listOf("linux-x86_64"), approved = true, coreId = "ordered")\n'
        ))
        kotlin = v.load_kotlin_targets()
        self.assertEqual(kotlin["approved_linux"], {"ordered"})


class InventoryEquality(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp())
        _patch(self.tmp)

    def _seed_valid(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a"), _enabled_core("b")])
        _write_doc(self.tmp, "## Core status\n\n| Core | T | G | E |\n| --- | --- | --- | --- |\n| `a` | x | y | z | yes |\n| `b` | x | y | z | yes |\n")
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(coreId = "a", approved = true, supportedAbis = listOf("linux-x86_64"))\n'
            'CoreLicenseFinding(coreId = "b", approved = true, supportedAbis = listOf("linux-x86_64"))\n'
        ))

    def test_mismatch_reports_both_directions(self) -> None:
        self._seed_valid()
        # Add an approved linux core that only exists in CoreManifest.kt.
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(coreId = "a", approved = true, supportedAbis = listOf("linux-x86_64"))\n'
            'CoreLicenseFinding(coreId = "b", approved = true, supportedAbis = listOf("linux-x86_64"))\n'
            'CoreLicenseFinding(coreId = "c", approved = true, supportedAbis = listOf("linux-x86_64"))\n'
        ))
        rc = v.main()
        self.assertEqual(rc, 1)


if __name__ == "__main__":
    unittest.main(verbosity=2)

#!/usr/bin/env python3
"""Targeted tests for packaging/validate-linux-targets.py.

Exercises the guard's hardened error paths against synthetic sources written to
a project-local fixture directory, plus the live-data happy path. Run with either:

    python3 packaging/test_validate_linux_targets.py
    python3 -m pytest packaging/test_validate_linux_targets.py -q
"""

from __future__ import annotations

import importlib.util
import io
import json
import shutil
import unittest
import uuid
from pathlib import Path
from contextlib import redirect_stdout
from unittest.mock import patch

# Import the validator as a module so its load_* helpers can be exercised
# directly (its __main__ guard prevents main() from running on import).
_VALIDATOR_PATH = Path(__file__).resolve().parent / "validate-linux-targets.py"
_spec = importlib.util.spec_from_file_location("validate_linux_targets", _VALIDATOR_PATH)
v = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v)

# Snapshot the real working-tree paths so the live-data test can run regardless
# of test order (patching tests reassign these module globals).
_PATH_NAMES = (
    "PACKAGE_MANIFEST", "DOC_MANIFEST", "CORE_MANIFEST_KT",
    "WINDOWS_PACKAGE_MANIFEST", "WINDOWS_DOC_MANIFEST", "WINDOWS_ADAPTER_DIR",
)
_ORIG_PATHS = {name: getattr(v, name) for name in _PATH_NAMES}


def _restore_original_paths() -> None:
    for name, path in _ORIG_PATHS.items():
        setattr(v, name, path)


def _fixture_dir(test: unittest.TestCase) -> Path:
    _restore_original_paths()
    path = v.ROOT / "build" / "target-validation-tests" / uuid.uuid4().hex
    path.mkdir(parents=True)
    test.addCleanup(_restore_original_paths)
    test.addCleanup(shutil.rmtree, path)
    return path


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
        self.assertEqual(kotlin["approved_windows"], v.WINDOWS_CORE_IDS)
        self.assertEqual(kotlin["all_windows"], v.WINDOWS_CORE_IDS)
        self.assertEqual(v.load_windows_package_core_ids(), v.WINDOWS_CORE_IDS)
        self.assertEqual(v.main(), 0)


class PackageAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = _fixture_dir(self)
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

    def test_duplicate_linux_core_raises(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a"), _enabled_core("a")])
        with self.assertRaisesRegex(v.GuardError, "duplicate"):
            v.load_package_core_ids()

    def test_linux_package_cannot_also_advertise_windows(self) -> None:
        _write_manifest(self.tmp, [_enabled_core("a", abis=[LINUX_SLUG, WINDOWS_SLUG])])
        with self.assertRaisesRegex(v.GuardError, "advertises windows"):
            v.load_package_core_ids()


class DocAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = _fixture_dir(self)
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
        self.tmp = _fixture_dir(self)
        _patch(self.tmp)

    def test_windows_inventory_distinguishes_approved_and_all_entries(self) -> None:
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(coreId = "unappr", approved = false, supportedAbis = listOf("windows-x86_64"))\n'
            'CoreLicenseFinding(coreId = "appr", approved = true, supportedAbis = listOf("windows-x86_64"))\n'
        ))
        kotlin = v.load_kotlin_targets()
        self.assertEqual(kotlin["approved_windows"], {"appr"})
        self.assertEqual(kotlin["all_windows"], {"appr", "unappr"})
        self.assertEqual(kotlin["approved_linux"], set())

    def test_platform_mentions_outside_supported_abis_do_not_enable_core(self) -> None:
        _write_kotlin(self.tmp, (
            'CoreLicenseFinding(coreId = "a", approved = true, '
            'supportedAbis = listOf("linux-x86_64"), buildCommand = "windows-x86_64")\n'
        ))
        self.assertEqual(v.load_kotlin_targets()["all_windows"], set())

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
        self.tmp = _fixture_dir(self)
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
        output = io.StringIO()
        with patch.object(v, "load_windows_package_core_ids", return_value=v.WINDOWS_CORE_IDS):
            with redirect_stdout(output):
                rc = v.main()
        self.assertEqual(rc, 1)
        self.assertIn("only in CoreManifest.kt: ['c']", output.getvalue())
        self.assertIn("only in package manifest: []", output.getvalue())


class WindowsAssertions(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = _fixture_dir(self)
        self.data = json.loads(v.WINDOWS_PACKAGE_MANIFEST.read_text())
        v.WINDOWS_PACKAGE_MANIFEST = self.tmp / "windows-core-manifest.json"
        # Isolate fixture metadata checks from native adapters being built in parallel.
        v.WINDOWS_ADAPTER_DIR = self.tmp / "adapters"
        v.WINDOWS_ADAPTER_DIR.mkdir()
        for cid in v.WINDOWS_CORE_IDS:
            (v.WINDOWS_ADAPTER_DIR / f"{cid}-windows.cmake").write_text(
                f"add_library({cid}_core SHARED source.c {cid}-windows.def)\n"
            )
            (v.WINDOWS_ADAPTER_DIR / f"{cid}-windows.def").write_text("EXPORTS\nretro_api_version\n")
        self._save()

    def _save(self) -> None:
        v.WINDOWS_PACKAGE_MANIFEST.write_text(json.dumps(self.data))

    def test_windows_metadata_matches_kotlin(self) -> None:
        self.assertEqual(v.load_windows_package_core_ids(), v.WINDOWS_CORE_IDS)

    def test_windows_inventory_exclusions(self) -> None:
        for cid in sorted(v.WINDOWS_EXCLUDED_IDS):
            with self.subTest(core=cid):
                self.data["cores"][0]["coreId"] = cid
                self._save()
                with self.assertRaisesRegex(v.GuardError, "excluded or unexpected"):
                    v.load_windows_package_core_ids()

    def test_duplicate_core_rejected(self) -> None:
        self.data["cores"].append(self.data["cores"][0])
        self._save()
        with self.assertRaisesRegex(v.GuardError, "duplicate"):
            v.load_windows_package_core_ids()

    def test_disabled_core_rejected(self) -> None:
        self.data["cores"][0]["enabled"] = False
        self._save()
        with self.assertRaisesRegex(v.GuardError, "not enabled"):
            v.load_windows_package_core_ids()

    def test_windows_abi_is_exclusive(self) -> None:
        self.data["cores"][0]["supportedAbis"].append(LINUX_SLUG)
        self._save()
        with self.assertRaisesRegex(v.GuardError, "only windows"):
            v.load_windows_package_core_ids()

    def test_canonical_dll_name_required(self) -> None:
        self.data["cores"][0]["coreLibraryFile"] = "libgambatte_core.so"
        self._save()
        with self.assertRaisesRegex(v.GuardError, "noncanonical"):
            v.load_windows_package_core_ids()

    def test_adapter_path_required(self) -> None:
        self.data["cores"][0]["adapterFile"] = "native/cmake/cores/gambatte-linux.cmake"
        self._save()
        with self.assertRaisesRegex(v.GuardError, "incorrect adapter"):
            v.load_windows_package_core_ids()

    def test_missing_adapter_or_exports_rejected(self) -> None:
        for suffix in ("cmake", "def"):
            with self.subTest(suffix=suffix):
                path = v.WINDOWS_ADAPTER_DIR / f"gambatte-windows.{suffix}"
                content = path.read_text()
                path.unlink()
                with self.assertRaisesRegex(v.GuardError, "missing adapter/export"):
                    v.load_windows_package_core_ids()
                path.write_text(content)

    def test_wrong_adapter_target_rejected(self) -> None:
        (v.WINDOWS_ADAPTER_DIR / "gambatte-windows.cmake").write_text(
            "# add_library(gambatte_core SHARED gambatte-windows.def)\n"
            "add_library(wrong_core SHARED gambatte-windows.def)\n"
        )
        with self.assertRaisesRegex(v.GuardError, "canonical shared target"):
            v.load_windows_package_core_ids()

    def test_adapter_must_reference_export_file(self) -> None:
        (v.WINDOWS_ADAPTER_DIR / "gambatte-windows.cmake").write_text(
            "add_library(gambatte_core SHARED source.c)\n"
        )
        with self.assertRaisesRegex(v.GuardError, "reference its export file"):
            v.load_windows_package_core_ids()

    def test_revision_drift_rejected(self) -> None:
        self.data["cores"][0]["commitSha"] = "0" * 40
        self._save()
        with self.assertRaisesRegex(v.GuardError, "commitSha disagrees"):
            v.load_windows_package_core_ids()

    def test_license_risk_acceptance_drift_rejected(self) -> None:
        core = next(c for c in self.data["cores"] if c["coreId"] == "snes9x")
        core["license"].pop("ownerRiskAcceptedBy")
        self._save()
        with self.assertRaisesRegex(v.GuardError, "ownerRiskAcceptedBy disagrees"):
            v.load_windows_package_core_ids()

    def test_windows_doc_counts_enabled_rows_only(self) -> None:
        path = self.tmp / "windows-support-manifest.md"
        path.write_text("## Core status\n| `gambatte` | ready | yes |\n| `test_core` | synthetic | no |\n")
        self.assertEqual(v.load_doc_core_ids(path, enabled_only=True), {"gambatte"})

    def test_missing_windows_core_fails_agreement(self) -> None:
        self.data["cores"].pop()
        self._save()
        output = io.StringIO()
        with redirect_stdout(output):
            self.assertEqual(v.main(), 1)
        self.assertIn("missing: ['mupen64plus_next']", output.getvalue())

    def test_unapproved_or_excluded_kotlin_windows_core_fails_agreement(self) -> None:
        kotlin = v.load_kotlin_targets()
        kotlin["all_windows"] = kotlin["all_windows"] | {"sameboy"}
        with patch.object(v, "load_kotlin_targets", return_value=kotlin):
            output = io.StringIO()
            with redirect_stdout(output):
                self.assertEqual(v.main(), 1)
        self.assertIn("unexpected: ['sameboy']", output.getvalue())


if __name__ == "__main__":
    unittest.main(verbosity=2)

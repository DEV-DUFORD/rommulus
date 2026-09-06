"""Regression tests for strict handheld authoritative-save diagnostics."""

import hashlib
from pathlib import Path
import shutil
import unittest
import uuid

from handheld_player_e2e import assert_save_unchanged


class SaveIntegrityTests(unittest.TestCase):
    def setUp(self):
        self.root = Path("build/handheld-harness-tests") / uuid.uuid4().hex
        self.root.mkdir(parents=True)
        self.report = {}

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_unchanged_authoritative_save_is_recorded(self):
        image = b"\x3b" * 1024
        assert_save_unchanged("unchanged", image, image, self.report, self.root)
        details = self.report["authoritativeSaveChecks"][0]
        self.assertEqual(details["beforeSha256"], hashlib.sha256(image).hexdigest())
        self.assertEqual(details["beforeSha256"], details["afterSha256"])
        self.assertFalse(list(self.root.iterdir()))

    def test_focus_loss_overwrite_still_fails_and_preserves_evidence(self):
        before = b"\x3b" * 1024
        after = b"\x5a\xa5" + before[2:]
        with self.assertRaisesRegex(AssertionError, "authoritative save overwritten"):
            assert_save_unchanged("focus-loss", before, after, self.report, self.root)
        details = self.report["authoritativeSaveChecks"][0]
        self.assertEqual(details["firstDifferences"], [
            {"offset": 0, "before": 0x3B, "after": 0x5A},
            {"offset": 1, "before": 0x3B, "after": 0xA5},
        ])
        self.assertEqual((self.root / "focus-loss.save-before.bin").read_bytes(), before)
        self.assertEqual((self.root / "focus-loss.save-after.bin").read_bytes(), after)

    def test_truncated_save_records_missing_bytes(self):
        with self.assertRaises(AssertionError):
            assert_save_unchanged("truncated", b"abc", b"a", self.report, self.root)
        details = self.report["authoritativeSaveChecks"][0]
        self.assertEqual(details["beforeSize"], 3)
        self.assertEqual(details["afterSize"], 1)
        self.assertEqual(details["firstDifferences"], [
            {"offset": 1, "before": ord("b"), "after": None},
            {"offset": 2, "before": ord("c"), "after": None},
        ])

    def test_large_difference_report_is_bounded_but_bytes_are_preserved(self):
        with self.assertRaises(AssertionError):
            assert_save_unchanged("large", bytes(1024), b"\xff" * 1024,
                                  self.report, self.root)
        self.assertEqual(len(self.report["authoritativeSaveChecks"][0]["firstDifferences"]), 32)
        self.assertEqual((self.root / "large.save-after.bin").read_bytes(), b"\xff" * 1024)


if __name__ == "__main__":
    unittest.main()

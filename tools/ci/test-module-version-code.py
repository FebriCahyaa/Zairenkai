#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
from __future__ import annotations
import unittest
from module_version_code import compute


class ModuleVersionCodeTest(unittest.TestCase):
    def test_codes_are_monotonic_across_build_timestamps(self):
        self.assertLess(compute(1_800_000_000), compute(1_800_000_001))

    def test_code_is_higher_than_legacy_release_and_canary_ranges(self):
        self.assertGreater(compute(1_800_000_000), 1_000_000_000 + 999_999)
        self.assertGreater(compute(1_800_000_000), 1_001_000)

    def test_rejects_timestamp_before_epoch(self):
        with self.assertRaisesRegex(ValueError, "predates"):
            compute(1_499_999_999)

    def test_rejects_android_version_code_overflow(self):
        self.assertEqual(compute(2_600_000_000), 2_100_000_000)
        with self.assertRaisesRegex(ValueError, "exceeds Android"):
            compute(2_600_000_001)


if __name__ == "__main__":
    unittest.main(verbosity=2)

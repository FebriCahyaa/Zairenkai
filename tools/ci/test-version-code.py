#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
from __future__ import annotations
import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).with_name("version-code.py")
SPEC = importlib.util.spec_from_file_location("version_code", SCRIPT)
assert SPEC and SPEC.loader
module = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(module)


class VersionCodeTest(unittest.TestCase):
    def test_release_codes_follow_semver_order(self):
        self.assertEqual(1_001_000, module.compute("1.1.0", "release"))
        self.assertGreater(module.compute("1.2.0", "release"), module.compute("1.1.9", "release"))

    def test_canary_codes_follow_run_number_and_are_isolated(self):
        self.assertEqual(1_000_000_042, module.compute("1.1.0", "canary", 42))
        self.assertGreater(module.compute("1.1.0", "canary", 43), module.compute("1.1.0", "canary", 42))

    def test_rejects_invalid_release_version(self):
        with self.assertRaises(ValueError):
            module.compute("1.2", "release")

    def test_rejects_missing_canary_run_number(self):
        with self.assertRaises(ValueError):
            module.compute("1.2.3", "canary")

    def test_release_rejects_prerelease_suffixes(self) -> None:
        with self.assertRaises(ValueError):
            module.compute("1.2.3-rc.1", "release")


if __name__ == "__main__":
    unittest.main(verbosity=2)

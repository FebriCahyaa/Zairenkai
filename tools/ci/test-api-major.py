#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Regression test for a single API-major source of truth."""
from __future__ import annotations
import importlib.util
import unittest
from pathlib import Path
SCRIPT = Path(__file__).with_name("check-api-major.py")
SPEC = importlib.util.spec_from_file_location("check_api_major", SCRIPT)
assert SPEC and SPEC.loader
checker = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(checker)
class ApiMajorTest(unittest.TestCase):
    def test_repository_api_major_definitions_match(self):
        script = checker.LICENSE_SCRIPT.read_text(encoding="utf-8")
        header = checker.UAPI_HEADER.read_text(encoding="utf-8")
        import re
        a = re.search(r"^API_MAJOR\s*=\s*(\d+)\s*$", script, re.M)
        b = re.search(r"^#define\s+ZKFC_API_MAJOR\s+(\d+)\s*$", header, re.M)
        self.assertIsNotNone(a)
        self.assertIsNotNone(b)
        self.assertEqual(a.group(1), b.group(1))
        self.assertGreaterEqual(int(a.group(1)), 1)
if __name__ == "__main__": unittest.main(verbosity=2)

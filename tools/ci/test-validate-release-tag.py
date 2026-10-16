#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
from __future__ import annotations
import unittest
from release_tag import validate_release_tag


class ValidateReleaseTagTest(unittest.TestCase):
    def test_allows_first_stable_tag(self):
        self.assertTrue(validate_release_tag("v1.0.0", [])[0])

    def test_allows_newer_stable_tag(self):
        self.assertTrue(validate_release_tag("v1.3.0", ["v1.2.0", "v1.2.1-rc.1"])[0])

    def test_rejects_older_stable_tag(self):
        self.assertFalse(validate_release_tag("v1.1.8", ["v1.1.9", "v1.2.0-rc.1"])[0])

    def test_allows_rerunning_the_current_tag(self):
        self.assertTrue(validate_release_tag("v1.2.0", ["v1.1.9", "v1.2.0"])[0])

    def test_rejects_prerelease_or_malformed_tag(self):
        self.assertFalse(validate_release_tag("v1.2.0-rc.1", [])[0])
        self.assertFalse(validate_release_tag("v01.2.0", [])[0])


if __name__ == "__main__":
    unittest.main(verbosity=2)

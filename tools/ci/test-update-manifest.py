#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Tests for release update metadata generation."""
from __future__ import annotations
import hashlib
import json
import subprocess
import tempfile
import unittest
from pathlib import Path
SCRIPT = Path(__file__).with_name("generate-update-manifest.py")
class UpdateManifestGeneratorTest(unittest.TestCase):
    def test_generates_api_version_build_identity_and_hash(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td); apk = root / "app.apk"; out = root / "version-release.json"
            apk.write_bytes(b"fake apk bytes")
            subprocess.run(["python3", str(SCRIPT), "--apk", str(apk), "--output", str(out),
                "--version-name", "1.2.3", "--version-code", "123", "--apk-url",
                "https://github.com/FebriCahyaa/Zairenkai/releases/latest/download/Zairenkai-release.apk",
                "--api-major", "1", "--build-id", "deadbeef-123-1", "--commit", "deadbeef1234567"], check=True, capture_output=True, text=True)
            data = json.loads(out.read_text())
            self.assertEqual(data["apiMajor"], 1)
            self.assertEqual(data["buildId"], "deadbeef-123-1")
            self.assertEqual(data["sha256"], hashlib.sha256(b"fake apk bytes").hexdigest())
            self.assertEqual(data["commit"], "deadbeef1234567")
    def test_rejects_non_github_url(self):
        with tempfile.TemporaryDirectory() as td:
            root = Path(td); apk = root / "app.apk"; out = root / "manifest.json"; apk.write_bytes(b"x")
            result = subprocess.run(["python3", str(SCRIPT), "--apk", str(apk), "--output", str(out),
                "--version-name", "1.2.3", "--version-code", "1", "--apk-url", "https://example.com/app.apk",
                "--api-major", "1", "--build-id", "deadbeef-1-1", "--commit", "deadbeef1234567"], capture_output=True)
            self.assertEqual(result.returncode, 2)
            self.assertFalse(out.exists())
if __name__ == "__main__": unittest.main(verbosity=2)

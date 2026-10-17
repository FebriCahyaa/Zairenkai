#!/usr/bin/env python3
# SPDX-License-Identifier: LicenseRef-Zairenkai-Proprietary
"""Unit tests for the release-manifest generator without a Gradle build."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

SCRIPT = Path(__file__).with_name("generate-release-manifest.py")
SPEC = importlib.util.spec_from_file_location("release_manifest", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
release_manifest = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(release_manifest)


class ReleaseManifestTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        release_manifest.ROOT = self.root
        (self.root / "tools/zkfc-license").mkdir(parents=True)
        (self.root / "tools/zkfc-license/zkfc_license.py").write_text("API_MAJOR = 2\n", encoding="utf-8")
        (self.root / "Zairenkai-standard-release.apk").write_bytes(b"test-standard-apk")
        (self.root / "Zairenkai-standard-release.apk.sha256").write_text("standard hash\n", encoding="utf-8")
        (self.root / "Zairenkai-module.zip").write_bytes(b"test-module-zip")
        (self.root / "Zairenkai-module-SHA256SUMS").write_text("test checksum list\n", encoding="utf-8")
        (self.root / "Zairenkai-spoofed-release.apk").write_bytes(b"test-spoof-apk")
        (self.root / "Zairenkai-spoofed-release.apk.sha256").write_text("spoof hash\n", encoding="utf-8")
        (self.root / "Zairenkai-KMI-modules.zip").write_bytes(b"test-kmi-zip")
        (self.root / "Zairenkai-KMI-modules.zip.sha256").write_text("kmi hash\n", encoding="utf-8")
        (self.root / "Zairenkai-KMI-SHA256SUMS").write_text("KMI checksum list\n", encoding="utf-8")
        (self.root / "Zairenkai-CHANGELOG.md").write_text("# Release changes\n", encoding="utf-8")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def test_manifest_contains_hashes_and_build_metadata(self) -> None:
        with patch.dict("os.environ", {
            "GITHUB_WORKFLOW": "unit-test",
        }, clear=False), patch("sys.argv", [str(SCRIPT), "--output", "manifest.json", "--version", "v9.9.9", "--commit", "deadbeef", "--run-id", "12", "--run-attempt", "2"]):
            self.assertEqual(release_manifest.main(), 0)

        manifest = json.loads((self.root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(manifest["version"], "v9.9.9")
        self.assertEqual(manifest["build"]["commit"], "deadbeef")
        self.assertEqual(manifest["build"]["run_id"], "12")
        self.assertEqual(manifest["api"]["major"], 2)
        entries = {item["path"]: item for item in manifest["artifacts"]}
        self.assertIn("Zairenkai-module.zip", entries)
        self.assertEqual(entries["Zairenkai-module.zip"]["sha256"], hashlib.sha256(b"test-module-zip").hexdigest())
        self.assertEqual(entries["Zairenkai-standard-release.apk"]["size_bytes"], len(b"test-standard-apk"))
        self.assertIn("Zairenkai-standard-release.apk.sha256", entries)
        self.assertIn("Zairenkai-spoofed-release.apk", entries)
        self.assertIn("Zairenkai-KMI-modules.zip", entries)
        self.assertIn("Zairenkai-KMI-SHA256SUMS", entries)
        self.assertIn("Zairenkai-CHANGELOG.md", entries)

    def test_manifest_supports_stable_updater_apk_filename(self) -> None:
        (self.root / "Zairenkai-standard-release.apk").unlink()
        (self.root / "Zairenkai-standard-release.apk.sha256").unlink()
        (self.root / "Zairenkai-release.apk").write_bytes(b"stable-updater-apk")
        (self.root / "Zairenkai-release.apk.sha256").write_text("stable hash\n", encoding="utf-8")
        with patch.dict("os.environ", {"GITHUB_WORKFLOW": "unit-test"}, clear=False), patch(
            "sys.argv", [str(SCRIPT), "--output", "manifest.json", "--version", "v1.2.3"]
        ):
            self.assertEqual(release_manifest.main(), 0)
        manifest = json.loads((self.root / "manifest.json").read_text(encoding="utf-8"))
        entries = {item["path"]: item for item in manifest["artifacts"]}
        self.assertIn("Zairenkai-release.apk", entries)
        self.assertNotIn("Zairenkai-standard-release.apk", entries)

    def test_manifest_fails_when_required_module_is_missing(self) -> None:
        (self.root / "Zairenkai-module.zip").unlink()
        with patch("sys.argv", [str(SCRIPT), "--output", "manifest.json"]):
            self.assertEqual(release_manifest.main(), 2)
        self.assertFalse((self.root / "manifest.json").exists())

    def test_manifest_refuses_output_path_escape(self) -> None:
        with patch("sys.argv", [str(SCRIPT), "--output", "../escape.json"]):
            self.assertEqual(release_manifest.main(), 2)
        self.assertFalse((self.root.parent / "escape.json").exists())


if __name__ == "__main__":
    unittest.main(verbosity=2)
